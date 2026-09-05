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
        connected.put(employee.getEmployeeNumber(), employee);
        endpoints.put(employee.getEmployeeNumber(), endpoint);
    }

    public synchronized void unregister(String employeeNumber) {
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
        if (isBusy(fromEmployeeNumber)) {
            return false;
        }
        String freeEmployee = findFreeEmployeeAtBranch(targetBranchId, fromEmployeeNumber);
        if (freeEmployee != null) {
            startSession(fromEmployeeNumber, freeEmployee);
        } else {
            pendingByBranch.computeIfAbsent(targetBranchId, id -> new LinkedBlockingQueue<>())
                    .offer(new ChatRequest(fromEmployeeNumber, targetBranchId));
            send(fromEmployeeNumber, MessageType.CHAT_QUEUED, new ChatQueuedNotice(targetBranchId));
        }
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
     *     False if {@code fromEmployeeNumber} is busy in a genuinely different session.
     */
    public synchronized boolean requestDirectChat(String fromEmployeeNumber, String targetEmployeeNumber) {
        ChatSession current = sessionByEmployeeNumber.get(fromEmployeeNumber);
        if (current != null) {
            return current == sessionByEmployeeNumber.get(targetEmployeeNumber);
        }
        if (connected.containsKey(targetEmployeeNumber) && !isBusy(targetEmployeeNumber)) {
            startSession(fromEmployeeNumber, targetEmployeeNumber);
        } else {
            Employee target = connected.get(targetEmployeeNumber);
            String branchId = target != null ? target.getBranchId() : null;
            pendingByBranch.computeIfAbsent(branchId, id -> new LinkedBlockingQueue<>())
                    .offer(new ChatRequest(fromEmployeeNumber, branchId));
            send(fromEmployeeNumber, MessageType.CHAT_QUEUED, new ChatQueuedNotice(branchId));
        }
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
        ChatSession session = sessionByEmployeeNumber.get(targetEmployeeNumber);
        if (session == null) {
            return false;
        }
        if (sessionByEmployeeNumber.get(shiftManagerEmployeeNumber) == session) {
            return true;
        }
        if (isBusy(shiftManagerEmployeeNumber)) {
            return false;
        }
        session.addParticipant(shiftManagerEmployeeNumber);
        busyEmployeeNumbers.add(shiftManagerEmployeeNumber);
        sessionByEmployeeNumber.put(shiftManagerEmployeeNumber, session);
        broadcastSessionStarted(session);
        return true;
    }

    public synchronized void sendMessage(String sessionEmployeeNumber, String text) {
        ChatSession session = sessionByEmployeeNumber.get(sessionEmployeeNumber);
        if (session == null) {
            return;
        }
        session.appendToTranscript(sessionEmployeeNumber, text);
        ChatMessageDto messageDto = new ChatMessageDto(session.getId(), sessionEmployeeNumber, text);
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
        ChatSession session = sessionByEmployeeNumber.get(employeeNumber);
        if (session == null) {
            return;
        }
        List<String> participants = new ArrayList<>(session.getParticipantEmployeeNumbers());
        for (String participant : participants) {
            session.removeParticipant(participant);
            sessionByEmployeeNumber.remove(participant);
            busyEmployeeNumbers.remove(participant);
            send(participant, MessageType.CHAT_END, new ChatEndNotice(session.getId()));
        }
        LogManager.getInstance().log(new LogEvent(LogType.CHAT, String.join(", ", participants),
                "Chat session " + session.getId() + " ended. Transcript: " + String.join(" | ", session.getTranscript())));
        for (String freedParticipant : participants) {
            notifyIfQueuedRequestWaiting(freedParticipant);
        }
    }

    private void startSession(String employeeA, String employeeB) {
        ChatSession session = new ChatSession();
        session.addParticipant(employeeA);
        session.addParticipant(employeeB);
        busyEmployeeNumbers.add(employeeA);
        busyEmployeeNumbers.add(employeeB);
        sessionByEmployeeNumber.put(employeeA, session);
        sessionByEmployeeNumber.put(employeeB, session);
        broadcastSessionStarted(session);
    }

    private void broadcastSessionStarted(ChatSession session) {
        ChatStartedNotice notice = new ChatStartedNotice(session.getId(), new ArrayList<>(session.getParticipantEmployeeNumbers()));
        for (String participant : session.getParticipantEmployeeNumbers()) {
            send(participant, MessageType.CHAT_STARTED, notice);
        }
    }

    private void notifyIfQueuedRequestWaiting(String freedEmployeeNumber) {
        Employee employee = connected.get(freedEmployeeNumber);
        if (employee == null || employee.getBranchId() == null) {
            return;
        }
        BlockingQueue<ChatRequest> queue = pendingByBranch.get(employee.getBranchId());
        if (queue == null) {
            return;
        }
        ChatRequest request = queue.poll();
        if (request == null) {
            return;
        }
        Employee requester = connected.get(request.getFromEmployeeNumber());
        String requesterName = requester != null ? requester.getFullName() : request.getFromEmployeeNumber();
        send(freedEmployeeNumber, MessageType.CHAT_FREE_NOTICE,
                new ChatFreeNotice(request.getFromEmployeeNumber(), requesterName));
    }

    private String findFreeEmployeeAtBranch(String branchId, String excludingEmployeeNumber) {
        for (Map.Entry<String, Employee> entry : connected.entrySet()) {
            String employeeNumber = entry.getKey();
            Employee employee = entry.getValue();
            if (employeeNumber.equals(excludingEmployeeNumber)) {
                continue;
            }
            if (branchId.equals(employee.getBranchId()) && !isBusy(employeeNumber)) {
                return employeeNumber;
            }
        }
        return null;
    }

    private void send(String employeeNumber, MessageType type, Object payload) {
        ChatEndpoint endpoint = endpoints.get(employeeNumber);
        if (endpoint != null) {
            endpoint.send(type, payload);
        }
    }
}
