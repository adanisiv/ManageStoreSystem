package managestore.server.service;

import managestore.common.model.Employee;
import managestore.common.model.LogEvent;
import managestore.common.model.LogType;
import managestore.common.protocol.ChatEndNotice;
import managestore.common.protocol.ChatFreeNotice;
import managestore.common.protocol.ChatMessageDto;
import managestore.common.protocol.ChatQueuedNotice;
import managestore.common.protocol.ChatStartedNotice;
import managestore.common.protocol.MessageType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Mediator pattern: employees never talk to each other directly or hold
 * references to one another. Every chat interaction — request, join,
 * message, end — goes through this single object, which is the only thing
 * that knows who's connected, who's busy, and who's waiting. That
 * indirection is what makes "employee in branch A wants any free employee
 * in branch B" and "notify whoever becomes free next" possible without
 * employees knowing about each other at all.
 *
 * <p>Each branch has its own {@link BlockingQueue} of {@link ChatRequest}s
 * that couldn't be matched immediately (no free employee). When any employee
 * at that branch becomes free (chat ends, or they just log in), the oldest
 * queued request is popped (FIFO) and that employee is told who tried to
 * reach them, so they can call back.
 */
public class ChatMediator {

    // All of these are only ever touched from inside a `synchronized` method on this instance
    // (a single monitor guarding all mediator state), so plain LinkedHash* collections are safe
    // here and — unlike ConcurrentHashMap — give deterministic, registration-order iteration,
    // which matters for "match the first free employee" to behave predictably.
    private final Map<String, ChatEndpoint> endpoints = new LinkedHashMap<>();
    private final Map<String, Employee> connected = new LinkedHashMap<>();
    private final Set<String> busyEmployeeNumbers = new LinkedHashSet<>();
    private final Map<String, ChatSession> sessionByEmployeeNumber = new LinkedHashMap<>();
    // LinkedHashMap, not ConcurrentHashMap: a disconnected/branchless (e.g. ADMIN) chat target
    // resolves to a null branchId, and ConcurrentHashMap throws NullPointerException on any
    // operation with a null key — plain HashMap accepts it fine, and thread-safety is already
    // handled by every access happening inside a `synchronized` method on this instance.
    private final Map<String, BlockingQueue<ChatRequest>> pendingByBranch = new LinkedHashMap<>();

    public synchronized void register(Employee employee, ChatEndpoint endpoint) {
        // Record both "who is this employee" and "how do I push a message to them" —
        // findFreeEmployeeAtBranch needs the former, send(...) needs the latter.
        connected.put(employee.getEmployeeNumber(), employee);
        endpoints.put(employee.getEmployeeNumber(), endpoint);
        // Logging in is the other way an employee becomes available, alongside ending a chat.
        // Without this, a request queued against a branch before anyone from it was online would
        // sit in the queue until some unrelated chat at that branch happened to end — and the
        // requester, whose client disables its Request button while queued, would be stuck.
        notifyIfQueuedRequestWaiting(employee.getEmployeeNumber());
    }

    public synchronized void unregister(String employeeNumber) {
        // If this employee was mid-chat, end that session first so the other participant(s)
        // get a proper CHAT_END notice and get freed up, instead of being left hanging with a
        // "busy" partner who silently vanished.
        endChatIfActive(employeeNumber);
        connected.remove(employeeNumber);
        endpoints.remove(employeeNumber);
        busyEmployeeNumbers.remove(employeeNumber);
        removePendingRequestsFrom(employeeNumber);
    }

    /**
     * Without this, a disconnected employee's own still-queued request lingers in
     * {@code pendingByBranch} forever: later, whoever at that branch next frees up gets a
     * CHAT_FREE_NOTICE saying this (now-gone) employee is waiting to hear from them — a ghost
     * notification for someone who isn't even connected to call back.
     */
    private void removePendingRequestsFrom(String employeeNumber) {
        // Scrub every branch's queue, not just the target branch's — the employee may have
        // multiple stale requests queued (e.g. requested, disconnected, reconnected, requested
        // again) or requests queued under different branch ids from direct-chat fallbacks.
        for (BlockingQueue<ChatRequest> queue : pendingByBranch.values()) {
            queue.removeIf(request -> request.getFromEmployeeNumber().equals(employeeNumber));
        }
    }

    public synchronized boolean isBusy(String employeeNumber) {
        return busyEmployeeNumbers.contains(employeeNumber);
    }

    /**
     * Employee at {@code fromEmployeeNumber} wants to talk to any free employee at
     * {@code targetBranchId}.
     *
     * @return true if the request was accepted (either matched immediately or queued). False if
     *     {@code fromEmployeeNumber} is already busy in an active session — without this guard,
     *     requesting a second chat while already in one would silently overwrite the requester's
     *     {@code sessionByEmployeeNumber} entry to point at the new session while the old one's
     *     participant list still lists them, corrupting both the same way an unguarded
     *     {@link #joinChat} would (see that method's javadoc) — and this path is reached by simply
     *     clicking "Request Chat" twice, not just the shift-manager join case.
     */
    public synchronized boolean requestChat(String fromEmployeeNumber, String targetBranchId) {
        // Busy-guard described in the javadoc above: reject outright rather than let a second
        // request silently clobber the requester's existing session mapping.
        if (isBusy(fromEmployeeNumber)) {
            return false;
        }
        // Look for anyone at the target branch who is connected, not the requester themselves,
        // and not already busy.
        String freeEmployee = findFreeEmployeeAtBranch(targetBranchId, fromEmployeeNumber);
        if (freeEmployee != null) {
            // Someone's available right now — connect them immediately.
            startSession(fromEmployeeNumber, freeEmployee);
        } else {
            // Nobody free at that branch: create the branch's queue on first use, then enqueue
            // this request so it can be picked up later (see notifyIfQueuedRequestWaiting) when
            // someone at that branch becomes free.
            // Drop any request this employee already has waiting first, so asking again — for
            // this branch or a different one — replaces their place in line rather than leaving
            // duplicates that would each fire a separate callback notice later.
            removePendingRequestsFrom(fromEmployeeNumber);
            pendingByBranch.computeIfAbsent(targetBranchId, id -> new LinkedBlockingQueue<>())
                    .offer(new ChatRequest(fromEmployeeNumber, targetBranchId));
            // Let the requester know they're waiting rather than leaving them guessing.
            send(fromEmployeeNumber, MessageType.CHAT_QUEUED, new ChatQueuedNotice(targetBranchId));
        }
        // Either path (matched or queued) counts as "accepted".
        return true;
    }

    /**
     * Direct callback: {@code fromEmployeeNumber} calls a specific employee back (they must be
     * free). Same busy-guard as {@link #requestChat}, with one addition: calling the exact person
     * you're already chatting with (e.g. a stale "Call back" button clicked again after the
     * callback already connected) is a harmless no-op, not a rejection — checked by reference,
     * the same way {@link #joinChat} tells "already in this one" apart from "busy elsewhere".
     *
     * @return true if accepted (matched, queued, or already talking to exactly this person).
     *     False if {@code fromEmployeeNumber} is busy in a genuinely different session, or if
     *     the target can't be queued for — see the branch check below.
     */
    public synchronized boolean requestDirectChat(String fromEmployeeNumber, String targetEmployeeNumber) {
        // If the requester is already in a session, this is only ever acceptable when that
        // session is the exact one shared with the target (reference equality, not just "some
        // session") — that's the harmless "call back the person you're already talking to"
        // no-op described in the javadoc. Any other existing session means "busy elsewhere".
        ChatSession current = sessionByEmployeeNumber.get(fromEmployeeNumber);
        if (current != null) {
            return current == sessionByEmployeeNumber.get(targetEmployeeNumber);
        }
        // Requester is free. Only start immediately if the target is actually connected and
        // not busy themselves — otherwise fall through to queueing.
        if (connected.containsKey(targetEmployeeNumber) && !isBusy(targetEmployeeNumber)) {
            startSession(fromEmployeeNumber, targetEmployeeNumber);
            return true;
        }
        // Target is connected but busy: queue under their branch, so the request surfaces the
        // next time anyone at that branch frees up — the same mechanism as branch-wide requests.
        Employee target = connected.get(targetEmployeeNumber);
        String branchId = target != null ? target.getBranchId() : null;
        // A queue is only ever drained by notifyIfQueuedRequestWaiting, which looks it up by a
        // freed employee's own branch id — so anything filed under a null key is unreachable and
        // would strand the requester forever. That happens when the target has since disconnected
        // (nothing to call back) or has no branch at all, as ADMIN doesn't. Refuse instead, so the
        // caller reports a real failure rather than a wait that can never end.
        if (branchId == null) {
            return false;
        }
        pendingByBranch.computeIfAbsent(branchId, id -> new LinkedBlockingQueue<>())
                .offer(new ChatRequest(fromEmployeeNumber, branchId));
        send(fromEmployeeNumber, MessageType.CHAT_QUEUED, new ChatQueuedNotice(branchId));
        return true;
    }

    /**
     * @return true if the shift manager actually joined. False (with nothing changed) if the
     *     target isn't in an active session, or the shift manager is already busy in a
     *     <em>different</em> one — without that second guard, joining a second session while
     *     still in a first would overwrite {@code sessionByEmployeeNumber}'s entry for the shift
     *     manager to point at the new session while the old session's participant list still
     *     lists them: ending the old session would then wrongly delete the shift manager's real,
     *     current mapping to the new one and tell their client the wrong chat ended.
     *
     *     <p>Re-joining the exact session the shift manager is already in (e.g. a double click on
     *     "Join") is a harmless no-op that returns true, not a rejection — {@code isBusy} alone
     *     can't tell "already in this one" apart from "busy in a different one", so that has to be
     *     checked first, by reference: every session lives as exactly one {@link ChatSession}
     *     instance shared by every participant's map entry, never copied.
     */
    public synchronized boolean joinChat(String shiftManagerEmployeeNumber, String targetEmployeeNumber) {
        // The target must actually be in an active session for there to be anything to join.
        ChatSession session = sessionByEmployeeNumber.get(targetEmployeeNumber);
        if (session == null) {
            return false;
        }
        // Reference equality on purpose (see javadoc): this tells "already a participant in
        // this exact session" apart from "busy in some other session" — isBusy alone can't
        // make that distinction. Re-joining the same session is a harmless success, not a
        // rejection.
        if (sessionByEmployeeNumber.get(shiftManagerEmployeeNumber) == session) {
            return true;
        }
        // Not in this session, so any existing session at all means "busy elsewhere" — refuse
        // rather than silently stealing the shift manager away from their current chat.
        if (isBusy(shiftManagerEmployeeNumber)) {
            return false;
        }
        // Clear to join: add as a third (or later) participant, mark them busy, and point their
        // entry in sessionByEmployeeNumber at this shared session instance.
        session.addParticipant(shiftManagerEmployeeNumber);
        busyEmployeeNumbers.add(shiftManagerEmployeeNumber);
        sessionByEmployeeNumber.put(shiftManagerEmployeeNumber, session);
        // Re-announce the (now larger) participant list to everyone in the session.
        broadcastSessionStarted(session);
        return true;
    }

    public synchronized void sendMessage(String sessionEmployeeNumber, String text) {
        // Not in any session (e.g. stale client, chat already ended) — nothing to send.
        ChatSession session = sessionByEmployeeNumber.get(sessionEmployeeNumber);
        if (session == null) {
            return;
        }
        // Record the message in the session's transcript before fanning it out, so it's
        // captured even if a send below fails or a participant is momentarily unreachable.
        session.appendToTranscript(sessionEmployeeNumber, text);
        ChatMessageDto messageDto = new ChatMessageDto(session.getId(), sessionEmployeeNumber, text);
        // Broadcast to every other participant (2 in the normal case, 3+ once a shift manager
        // has joined) — skip the sender so they don't get an echo of their own message.
        for (String participant : session.getParticipantEmployeeNumbers()) {
            if (!participant.equals(sessionEmployeeNumber)) {
                send(participant, MessageType.CHAT_MESSAGE, messageDto);
            }
        }
    }

    public synchronized void endChat(String employeeNumber) {
        endChatIfActive(employeeNumber);
    }

    private void endChatIfActive(String employeeNumber) {
        // Any participant (2 or 3+) can trigger the end — look up the shared session through
        // whichever employee number was passed in.
        ChatSession session = sessionByEmployeeNumber.get(employeeNumber);
        if (session == null) {
            return;
        }
        // Snapshot the participant list before mutating anything: the loop below removes
        // participants from the live session, so iterating a copy avoids modifying the
        // collection while walking it.
        List<String> participants = new ArrayList<>(session.getParticipantEmployeeNumbers());
        for (String participant : participants) {
            // Tear down every participant's state for this session: drop them from the
            // session itself, clear their session/busy bookkeeping, and tell their client the
            // chat is over — this ends the chat for ALL participants at once, not just the
            // one who asked to end it.
            session.removeParticipant(participant);
            sessionByEmployeeNumber.remove(participant);
            busyEmployeeNumbers.remove(participant);
            send(participant, MessageType.CHAT_END, new ChatEndNotice(session.getId()));
        }
        // Record the whole conversation as a single log entry once the session is fully torn
        // down, so the log reflects the final, complete transcript.
        LogManager.getInstance().log(new LogEvent(LogType.CHAT, String.join(", ", participants),
                "Chat session " + session.getId() + " ended. Transcript: " + String.join(" | ", session.getTranscript())));
        // Now that everyone from this session is free again, give each of them a chance to pick
        // up the next queued request waiting at their branch (if any).
        for (String freedParticipant : participants) {
            notifyIfQueuedRequestWaiting(freedParticipant);
        }
    }

    private void startSession(String employeeA, String employeeB) {
        // Brand-new session with exactly these two as its initial participants.
        ChatSession session = new ChatSession();
        session.addParticipant(employeeA);
        session.addParticipant(employeeB);
        // Both sides are now considered busy, and both map to this same session instance so
        // either one can later look it up, send messages through it, or end it.
        busyEmployeeNumbers.add(employeeA);
        busyEmployeeNumbers.add(employeeB);
        sessionByEmployeeNumber.put(employeeA, session);
        sessionByEmployeeNumber.put(employeeB, session);
        broadcastSessionStarted(session);
    }

    private void broadcastSessionStarted(ChatSession session) {
        // Snapshot the current participant list into the notice so later changes to the
        // session (e.g. a shift manager joining) don't retroactively alter a notice already
        // queued for delivery.
        ChatStartedNotice notice = new ChatStartedNotice(session.getId(), new ArrayList<>(session.getParticipantEmployeeNumbers()));
        // Tell every current participant (including the one who just joined) who's in the chat now.
        for (String participant : session.getParticipantEmployeeNumbers()) {
            send(participant, MessageType.CHAT_STARTED, notice);
        }
    }

    private void notifyIfQueuedRequestWaiting(String freedEmployeeNumber) {
        // Can't route a notification to someone with no known branch (e.g. an ADMIN account,
        // or someone who disconnected in the same instant).
        Employee employee = connected.get(freedEmployeeNumber);
        if (employee == null || employee.getBranchId() == null) {
            return;
        }
        // No queue was ever created for this branch, meaning nobody has ever waited there.
        BlockingQueue<ChatRequest> queue = pendingByBranch.get(employee.getBranchId());
        if (queue == null) {
            return;
        }
        // Pop the oldest waiting request (FIFO) for this branch, if any.
        ChatRequest request = queue.poll();
        if (request == null) {
            return;
        }
        // Resolve a human-readable name for the requester when possible, falling back to their
        // raw employee number if they've since disconnected.
        Employee requester = connected.get(request.getFromEmployeeNumber());
        String requesterName = requester != null ? requester.getFullName() : request.getFromEmployeeNumber();
        // This only informs the now-free employee that someone wants to talk — it does not
        // start a session automatically; the free employee still has to call back.
        send(freedEmployeeNumber, MessageType.CHAT_FREE_NOTICE,
                new ChatFreeNotice(request.getFromEmployeeNumber(), requesterName));
    }

    private String findFreeEmployeeAtBranch(String branchId, String excludingEmployeeNumber) {
        // LinkedHashMap iteration order is registration order, so this deterministically
        // returns the first-registered eligible employee rather than an arbitrary one.
        for (Map.Entry<String, Employee> entry : connected.entrySet()) {
            String employeeNumber = entry.getKey();
            Employee employee = entry.getValue();
            // Never match the requester to themselves.
            if (employeeNumber.equals(excludingEmployeeNumber)) {
                continue;
            }
            // Must be at the requested branch and not already in another chat.
            if (branchId.equals(employee.getBranchId()) && !isBusy(employeeNumber)) {
                return employeeNumber;
            }
        }
        // Nobody eligible found at this branch right now.
        return null;
    }

    private void send(String employeeNumber, MessageType type, Object payload) {
        // The endpoint can be absent if the employee disconnected between when this state
        // change was decided and when the notification is actually sent — silently drop it
        // rather than throwing, since there's no client left to receive it anyway.
        ChatEndpoint endpoint = endpoints.get(employeeNumber);
        if (endpoint != null) {
            endpoint.send(type, payload);
        }
    }
}
