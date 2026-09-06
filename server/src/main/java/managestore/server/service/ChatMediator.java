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
 * This class follows the Mediator pattern: employees never talk to each other directly
 * and never hold a reference to one another. Every chat action — request, join,
 * message, end — goes through this one object instead. It is the only thing that
 * knows who is connected, who is busy, and who is waiting.
 *
 * <p>This is what makes two things possible without employees knowing about each
 * other: an employee in branch A can ask for "any free employee in branch B", and
 * whoever becomes free next can be notified automatically.
 *
 * <p>Each branch has its own {@link BlockingQueue} of {@link ChatRequest}s. A request
 * lands here when it could not be matched right away (no one was free). When any
 * employee at that branch becomes free — because a chat ends, or they just log in —
 * we take the oldest waiting request (first in, first out) and tell that employee who
 * tried to reach them, so they can call back.
 */
public class ChatMediator {

    // Every field below is only ever touched from inside a `synchronized` method on this
    // instance. That means one lock protects all of this mediator's state, so plain
    // LinkedHash* collections are safe to use here — we don't need thread-safe collections.
    // A bonus of LinkedHash*: it iterates in registration order, which makes "match the
    // first free employee" behave predictably instead of picking a random one.
    private final Map<String, ChatEndpoint> endpoints = new LinkedHashMap<>();
    private final Map<String, Employee> connected = new LinkedHashMap<>();
    private final Set<String> busyEmployeeNumbers = new LinkedHashSet<>();
    private final Map<String, ChatSession> sessionByEmployeeNumber = new LinkedHashMap<>();
    // We use LinkedHashMap here, not ConcurrentHashMap, for a specific reason: a chat target
    // with no branch (a disconnected employee, or an ADMIN account) resolves to a null
    // branchId. ConcurrentHashMap throws a NullPointerException if you use a null key, but a
    // plain HashMap-based map accepts it fine. Thread-safety is not an issue here either way,
    // since every access already happens inside a `synchronized` method on this instance.
    private final Map<String, BlockingQueue<ChatRequest>> pendingByBranch = new LinkedHashMap<>();

    public synchronized void register(Employee employee, ChatEndpoint endpoint) {
        // We record two things here: who this employee is, and how to push a message to
        // them. findFreeEmployeeAtBranch needs the first; send(...) needs the second.
        connected.put(employee.getEmployeeNumber(), employee);
        endpoints.put(employee.getEmployeeNumber(), endpoint);
        // Logging in is the other way an employee can become available, besides ending a chat.
        // Without this call, a request queued for a branch before anyone from it was online
        // would just sit there until some unrelated chat at that branch happened to end. The
        // requester's Request button stays disabled while queued, so they would be stuck
        // waiting for no reason.
        notifyIfQueuedRequestWaiting(employee.getEmployeeNumber());
    }

    public synchronized void unregister(String employeeNumber) {
        // If this employee was mid-chat, end that session first. This way the other
        // participant(s) get a proper CHAT_END notice and are freed up, instead of being
        // left thinking they're still busy with a partner who silently vanished.
        endChatIfActive(employeeNumber);
        connected.remove(employeeNumber);
        endpoints.remove(employeeNumber);
        busyEmployeeNumbers.remove(employeeNumber);
        removePendingRequestsFrom(employeeNumber);
    }

    /**
     * Removes any request this employee still has waiting in a queue.
     *
     * <p>Without this, a disconnected employee's own queued request would stay in
     * {@code pendingByBranch} forever. Later, whoever at that branch next frees up would get
     * a CHAT_FREE_NOTICE saying this employee is waiting to hear from them — even though
     * they are gone and cannot be called back. This method clears out that kind of ghost
     * notification.
     */
    private void removePendingRequestsFrom(String employeeNumber) {
        // We clear every branch's queue, not just one. The employee could have more than one
        // stale request queued — for example if they requested a chat, disconnected,
        // reconnected, and requested again — and those requests could be filed under
        // different branch ids from direct-chat fallbacks.
        for (BlockingQueue<ChatRequest> queue : pendingByBranch.values()) {
            queue.removeIf(request -> request.getFromEmployeeNumber().equals(employeeNumber));
        }
    }

    public synchronized boolean isBusy(String employeeNumber) {
        return busyEmployeeNumbers.contains(employeeNumber);
    }

    /**
     * An employee asks to chat with any free employee at another branch.
     *
     * @return true if the request went through — either it connected right away, or it's
     *     now waiting in a queue. False if the employee is already in another chat. We check
     *     this so that clicking "Request Chat" a second time can't quietly break the
     *     employee's current session.
     */
    public synchronized boolean requestChat(String fromEmployeeNumber, String targetBranchId) {
        // See the busy-guard explanation in the javadoc above: we reject outright instead of
        // letting a second request silently overwrite the requester's existing session.
        if (isBusy(fromEmployeeNumber)) {
            return false;
        }
        // Look for anyone at the target branch who is connected, is not the requester
        // themselves, and is not already busy.
        String freeEmployee = findFreeEmployeeAtBranch(targetBranchId, fromEmployeeNumber);
        if (freeEmployee != null) {
            // Someone is available right now, so connect them immediately.
            startSession(fromEmployeeNumber, freeEmployee);
        } else {
            // Nobody is free at that branch. Create the branch's queue if this is its first
            // request, then add this request so it can be picked up later — see
            // notifyIfQueuedRequestWaiting — once someone at that branch becomes free.
            // First, drop any request this employee already has waiting. That way, asking
            // again (for this branch or a different one) replaces their place in line instead
            // of leaving duplicates that would each fire their own callback notice later.
            removePendingRequestsFrom(fromEmployeeNumber);
            pendingByBranch.computeIfAbsent(targetBranchId, id -> new LinkedBlockingQueue<>())
                    .offer(new ChatRequest(fromEmployeeNumber, targetBranchId));
            // Let the requester know they're waiting, rather than leaving them guessing.
            send(fromEmployeeNumber, MessageType.CHAT_QUEUED, new ChatQueuedNotice(targetBranchId));
        }
        // Both outcomes (matched right away, or queued) count as "accepted".
        return true;
    }

    /**
     * A direct callback: the employee calls a specific other employee back.
     *
     * <p>This has the same busy-guard as {@link #requestChat}, plus one extra case: calling
     * back the exact person you're already chatting with is treated as a harmless no-op, not
     * a rejection. This can happen if a stale "Call back" button gets clicked again after the
     * callback already connected. We detect this case by reference — the same way
     * {@link #joinChat} tells "already in this session" apart from "busy elsewhere".
     *
     * @return true if the call went through: it matched immediately, it was queued, or the
     *     employee was already talking to exactly this person. False if the employee is busy
     *     in a genuinely different session, or if the target can't be queued for (see the
     *     branch check below).
     */
    public synchronized boolean requestDirectChat(String fromEmployeeNumber, String targetEmployeeNumber) {
        // If the requester is already in a session, we only allow this call when that session
        // is the exact one shared with the target — checked by reference, not just "some
        // session". That is the harmless "call back the person you're already talking to"
        // case described in the javadoc above. Any other existing session means "busy
        // elsewhere", so we refuse.
        ChatSession current = sessionByEmployeeNumber.get(fromEmployeeNumber);
        if (current != null) {
            return current == sessionByEmployeeNumber.get(targetEmployeeNumber);
        }
        // The requester is free. We only start the chat immediately if the target is
        // connected and not busy. Otherwise we fall through to queueing below.
        if (connected.containsKey(targetEmployeeNumber) && !isBusy(targetEmployeeNumber)) {
            startSession(fromEmployeeNumber, targetEmployeeNumber);
            return true;
        }
        // The target is connected but busy. Queue the request under the target's branch, so
        // it surfaces the next time anyone at that branch frees up — this reuses the same
        // mechanism as branch-wide requests.
        Employee target = connected.get(targetEmployeeNumber);
        String branchId = target != null ? target.getBranchId() : null;
        // A queue is only ever drained by notifyIfQueuedRequestWaiting, which looks it up
        // using a freed employee's own branch id. So a request filed under a null key could
        // never be picked up, and the requester would be stuck waiting forever. That happens
        // when the target has since disconnected (there's nobody left to call back) or has no
        // branch at all, like an ADMIN account. In that case we refuse the request outright,
        // so the caller sees a real failure instead of a wait that never ends.
        if (branchId == null) {
            return false;
        }
        pendingByBranch.computeIfAbsent(branchId, id -> new LinkedBlockingQueue<>())
                .offer(new ChatRequest(fromEmployeeNumber, branchId));
        send(fromEmployeeNumber, MessageType.CHAT_QUEUED, new ChatQueuedNotice(branchId));
        return true;
    }

    /**
     * @return true if the shift manager actually joined. False, with nothing changed, if the
     *     target isn't in an active session, or if the shift manager is already busy in a
     *     <em>different</em> session.
     *
     *     <p>Why check for a different session: if the shift manager joined a second session
     *     while still in a first one, their entry in {@code sessionByEmployeeNumber} would be
     *     overwritten to point at the new session. But the old session's participant list
     *     would still list them too. Later, ending the old session would then wrongly erase
     *     the shift manager's real, current mapping to the new session, and their client
     *     would be told the wrong chat had ended.
     *
     *     <p>Re-joining the exact session the shift manager is already in — for example a
     *     double click on "Join" — is treated as a harmless no-op that returns true, not a
     *     rejection. {@code isBusy} alone can't tell "already in this session" apart from
     *     "busy in a different one", so we check that first, by reference: every session
     *     exists as exactly one {@link ChatSession} instance, shared by every participant's
     *     map entry and never copied.
     */
    public synchronized boolean joinChat(String shiftManagerEmployeeNumber, String targetEmployeeNumber) {
        // The target must actually be in an active session, or there is nothing to join.
        ChatSession session = sessionByEmployeeNumber.get(targetEmployeeNumber);
        if (session == null) {
            return false;
        }
        // We compare by reference on purpose (see javadoc above). This tells "already a
        // participant in this exact session" apart from "busy in some other session" —
        // isBusy alone cannot make that distinction. Re-joining the same session counts as a
        // harmless success, not a rejection.
        if (sessionByEmployeeNumber.get(shiftManagerEmployeeNumber) == session) {
            return true;
        }
        // The shift manager is not in this session, so being in any other session at all
        // means "busy elsewhere". We refuse rather than silently pulling them out of their
        // current chat.
        if (isBusy(shiftManagerEmployeeNumber)) {
            return false;
        }
        // Clear to join: add them as a third (or later) participant, mark them busy, and
        // point their entry in sessionByEmployeeNumber at this shared session instance.
        session.addParticipant(shiftManagerEmployeeNumber);
        busyEmployeeNumbers.add(shiftManagerEmployeeNumber);
        sessionByEmployeeNumber.put(shiftManagerEmployeeNumber, session);
        // Announce the now-larger participant list to everyone in the session again.
        broadcastSessionStarted(session);
        return true;
    }

    public synchronized void sendMessage(String sessionEmployeeNumber, String text) {
        // Not in any session — for example a stale client, or the chat already ended — so
        // there's nothing to send.
        ChatSession session = sessionByEmployeeNumber.get(sessionEmployeeNumber);
        if (session == null) {
            return;
        }
        // Save the message to the session's transcript before sending it out. This way it's
        // captured even if a send below fails or a participant is briefly unreachable.
        session.appendToTranscript(sessionEmployeeNumber, text);
        ChatMessageDto messageDto = new ChatMessageDto(session.getId(), sessionEmployeeNumber, text);
        // Send to every other participant — normally 2 people, or 3+ once a shift manager has
        // joined. We skip the sender so they don't get an echo of their own message.
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
        // Any participant (whether there are 2 or 3+) can trigger the end. We look up the
        // shared session through whichever employee number was passed in.
        ChatSession session = sessionByEmployeeNumber.get(employeeNumber);
        if (session == null) {
            return;
        }
        // Copy the participant list before changing anything. The loop below removes
        // participants from the live session, so iterating over a copy avoids modifying the
        // collection while we're still walking through it.
        List<String> participants = new ArrayList<>(session.getParticipantEmployeeNumbers());
        for (String participant : participants) {
            // Clean up each participant's state for this session: remove them from the
            // session itself, clear their session and busy bookkeeping, and tell their client
            // the chat is over. This ends the chat for ALL participants at once, not just the
            // one who asked to end it.
            session.removeParticipant(participant);
            sessionByEmployeeNumber.remove(participant);
            busyEmployeeNumbers.remove(participant);
            send(participant, MessageType.CHAT_END, new ChatEndNotice(session.getId()));
        }
        // Record the whole conversation as one log entry, once the session is fully torn
        // down, so the log holds the final, complete transcript.
        LogManager.getInstance().log(new LogEvent(LogType.CHAT, String.join(", ", participants),
                "Chat session " + session.getId() + " ended. Transcript: " + String.join(" | ", session.getTranscript())));
        // Everyone from this session is free again now, so give each of them a chance to pick
        // up the next queued request waiting at their branch, if there is one.
        for (String freedParticipant : participants) {
            notifyIfQueuedRequestWaiting(freedParticipant);
        }
    }

    private void startSession(String employeeA, String employeeB) {
        // A brand-new session with exactly these two as its starting participants.
        ChatSession session = new ChatSession();
        session.addParticipant(employeeA);
        session.addParticipant(employeeB);
        // Both sides are now considered busy. Both also map to this same session instance, so
        // either one can later look it up, send messages through it, or end it.
        busyEmployeeNumbers.add(employeeA);
        busyEmployeeNumbers.add(employeeB);
        sessionByEmployeeNumber.put(employeeA, session);
        sessionByEmployeeNumber.put(employeeB, session);
        broadcastSessionStarted(session);
    }

    private void broadcastSessionStarted(ChatSession session) {
        // Copy the current participant list into the notice. This way, if the session
        // changes later — for example a shift manager joining — it won't retroactively alter
        // a notice that's already queued for delivery.
        ChatStartedNotice notice = new ChatStartedNotice(session.getId(), new ArrayList<>(session.getParticipantEmployeeNumbers()));
        // Tell every current participant, including the one who just joined, who is in the
        // chat now.
        for (String participant : session.getParticipantEmployeeNumbers()) {
            send(participant, MessageType.CHAT_STARTED, notice);
        }
    }

    private void notifyIfQueuedRequestWaiting(String freedEmployeeNumber) {
        // We can't route a notification to someone with no known branch — for example an
        // ADMIN account, or someone who disconnected at this same instant.
        Employee employee = connected.get(freedEmployeeNumber);
        if (employee == null || employee.getBranchId() == null) {
            return;
        }
        // No queue was ever created for this branch, which means nobody has ever waited here.
        BlockingQueue<ChatRequest> queue = pendingByBranch.get(employee.getBranchId());
        if (queue == null) {
            return;
        }
        // Take the oldest waiting request for this branch (first in, first out), if any.
        ChatRequest request = queue.poll();
        if (request == null) {
            return;
        }
        // Look up a human-readable name for the requester when we can. If they've since
        // disconnected, fall back to their raw employee number.
        Employee requester = connected.get(request.getFromEmployeeNumber());
        String requesterName = requester != null ? requester.getFullName() : request.getFromEmployeeNumber();
        // This only tells the now-free employee that someone wants to talk. It does not start
        // a session automatically — the free employee still has to call back.
        send(freedEmployeeNumber, MessageType.CHAT_FREE_NOTICE,
                new ChatFreeNotice(request.getFromEmployeeNumber(), requesterName));
    }

    private String findFreeEmployeeAtBranch(String branchId, String excludingEmployeeNumber) {
        // LinkedHashMap iterates in registration order, so this always returns the
        // first-registered eligible employee, not an arbitrary one.
        for (Map.Entry<String, Employee> entry : connected.entrySet()) {
            String employeeNumber = entry.getKey();
            Employee employee = entry.getValue();
            // Never match the requester to themselves.
            if (employeeNumber.equals(excludingEmployeeNumber)) {
                continue;
            }
            // The employee must be at the requested branch and not already in another chat.
            if (branchId.equals(employee.getBranchId()) && !isBusy(employeeNumber)) {
                return employeeNumber;
            }
        }
        // Nobody eligible was found at this branch right now.
        return null;
    }

    private void send(String employeeNumber, MessageType type, Object payload) {
        // The endpoint can be missing if the employee disconnected between when we decided to
        // send this and when we actually try to send it. We drop the message quietly instead
        // of throwing an error, since there's no client left to receive it anyway.
        ChatEndpoint endpoint = endpoints.get(employeeNumber);
        if (endpoint != null) {
            endpoint.send(type, payload);
        }
    }
}
