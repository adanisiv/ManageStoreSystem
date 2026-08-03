package managestore.server.service;

import managestore.common.model.Employee;
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
import java.util.concurrent.ConcurrentHashMap;
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
 * that couldn't be matched immediately (no free employee) — the "queue
 * management" the brief asks you to research and apply. When any employee
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
    private final Map<String, BlockingQueue<ChatRequest>> pendingByBranch = new ConcurrentHashMap<>();

    public synchronized void register(Employee employee, ChatEndpoint endpoint) {
        connected.put(employee.getEmployeeNumber(), employee);
        endpoints.put(employee.getEmployeeNumber(), endpoint);
    }

    public synchronized void unregister(String employeeNumber) {
        endChatIfActive(employeeNumber);
        connected.remove(employeeNumber);
        endpoints.remove(employeeNumber);
        busyEmployeeNumbers.remove(employeeNumber);
    }

    public synchronized boolean isBusy(String employeeNumber) {
        return busyEmployeeNumbers.contains(employeeNumber);
    }

    /** Employee at {@code fromEmployeeNumber} wants to talk to any free employee at {@code targetBranchId}. */
    public synchronized void requestChat(String fromEmployeeNumber, String targetBranchId) {
        String freeEmployee = findFreeEmployeeAtBranch(targetBranchId, fromEmployeeNumber);
        if (freeEmployee != null) {
            startSession(fromEmployeeNumber, freeEmployee);
        } else {
            pendingByBranch.computeIfAbsent(targetBranchId, id -> new LinkedBlockingQueue<>())
                    .offer(new ChatRequest(fromEmployeeNumber, targetBranchId));
            send(fromEmployeeNumber, MessageType.CHAT_QUEUED, new ChatQueuedNotice(targetBranchId));
        }
    }

    /** Direct callback: {@code fromEmployeeNumber} calls a specific employee back (they must be free). */
    public synchronized void requestDirectChat(String fromEmployeeNumber, String targetEmployeeNumber) {
        if (connected.containsKey(targetEmployeeNumber) && !isBusy(targetEmployeeNumber)) {
            startSession(fromEmployeeNumber, targetEmployeeNumber);
        } else {
            Employee target = connected.get(targetEmployeeNumber);
            String branchId = target != null ? target.getBranchId() : null;
            pendingByBranch.computeIfAbsent(branchId, id -> new LinkedBlockingQueue<>())
                    .offer(new ChatRequest(fromEmployeeNumber, branchId));
            send(fromEmployeeNumber, MessageType.CHAT_QUEUED, new ChatQueuedNotice(branchId));
        }
    }

    public synchronized void joinChat(String shiftManagerEmployeeNumber, String targetEmployeeNumber) {
        ChatSession session = sessionByEmployeeNumber.get(targetEmployeeNumber);
        if (session == null) {
            return;
        }
        session.addParticipant(shiftManagerEmployeeNumber);
        busyEmployeeNumbers.add(shiftManagerEmployeeNumber);
        sessionByEmployeeNumber.put(shiftManagerEmployeeNumber, session);
        broadcastSessionStarted(session);
    }

    public synchronized void sendMessage(String sessionEmployeeNumber, String text) {
        ChatSession session = sessionByEmployeeNumber.get(sessionEmployeeNumber);
        if (session == null) {
            return;
        }
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
