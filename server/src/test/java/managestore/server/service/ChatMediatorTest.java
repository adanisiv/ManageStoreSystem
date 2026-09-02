package managestore.server.service;

import managestore.common.model.Employee;
import managestore.common.model.Role;
import managestore.common.protocol.ChatFreeNotice;
import managestore.common.protocol.ChatMessageDto;
import managestore.common.protocol.ChatQueuedNotice;
import managestore.common.protocol.ChatStartedNotice;
import managestore.common.protocol.MessageType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatMediatorTest {

    private ChatMediator mediator;
    private RecordingChatEndpoint sellerA;
    private RecordingChatEndpoint sellerB;
    private RecordingChatEndpoint cashierC;
    private RecordingChatEndpoint shiftManager;

    @BeforeEach
    void setUp() {
        // ChatMediator logs session-end events into the LogManager singleton, which other test
        // classes also write to — clear it so unrelated events from other tests can't leak in.
        LogManager.getInstance().clear();
        mediator = new ChatMediator();
        sellerA = new RecordingChatEndpoint();
        sellerB = new RecordingChatEndpoint();
        cashierC = new RecordingChatEndpoint();
        shiftManager = new RecordingChatEndpoint();

        mediator.register(employee("A", "BRANCH-1", Role.SELLER), sellerA);
        mediator.register(employee("B", "BRANCH-2", Role.SELLER), sellerB);
        mediator.register(employee("C", "BRANCH-2", Role.CASHIER), cashierC);
        mediator.register(employee("M", "BRANCH-2", Role.SHIFT_MANAGER), shiftManager);
    }

    private static Employee employee(String number, String branchId, Role role) {
        return new Employee(number, "Employee " + number, "id-" + number, "050-0", "acc-" + number, branchId, role);
    }

    @Test
    void requestChatConnectsToAFreeEmployeeAtTargetBranch() {
        mediator.requestChat("A", "BRANCH-2");

        assertEquals(MessageType.CHAT_STARTED, sellerA.lastType());
        ChatStartedNotice noticeOnA = sellerA.lastPayload(ChatStartedNotice.class);
        // B was registered before C, so B is picked first among the free employees at BRANCH-2.
        assertTrue(noticeOnA.getParticipantEmployeeNumbers().contains("A"));
        assertTrue(noticeOnA.getParticipantEmployeeNumbers().contains("B"));
        assertEquals(MessageType.CHAT_STARTED, sellerB.lastType());
        assertTrue(mediator.isBusy("A"));
        assertTrue(mediator.isBusy("B"));
    }

    @Test
    void requestChatQueuesWhenNobodyIsFreeAtTargetBranch() {
        RecordingChatEndpoint requester = new RecordingChatEndpoint();
        mediator.register(employee("Z", "BRANCH-1", Role.SELLER), requester);
        mediator.requestDirectChat("B", "C"); // occupy both free employees at BRANCH-2 except M
        mediator.joinChat("M", "C"); // M joins so it's busy too; now everyone at BRANCH-2 is busy

        mediator.requestChat("Z", "BRANCH-2");

        assertEquals(MessageType.CHAT_QUEUED, requester.lastType());
        assertEquals("BRANCH-2", requester.lastPayload(ChatQueuedNotice.class).getTargetBranchId());
    }

    @Test
    void freedEmployeeIsNotifiedOfQueuedRequestAndCanCallBack() {
        // Occupy both free employees at BRANCH-2 (B and C) with unrelated chats first.
        mediator.requestChat("A", "BRANCH-2"); // A <-> B
        RecordingChatEndpoint requester2 = new RecordingChatEndpoint();
        mediator.register(employee("D", "BRANCH-1", Role.SELLER), requester2);
        mediator.requestDirectChat("D", "C"); // D <-> C (direct, C was free)

        // Now a third employee (shift manager M) is the only one left at BRANCH-2, but let's
        // instead have a NEW requester ask BRANCH-2 while everyone there is busy -> should queue.
        RecordingChatEndpoint requester3 = new RecordingChatEndpoint();
        mediator.register(employee("E", "BRANCH-1", Role.SELLER), requester3);
        mediator.requestChat("E", "BRANCH-2");
        // M (shift manager) is still free at BRANCH-2, so this should actually connect to M, not queue.
        assertEquals(MessageType.CHAT_STARTED, requester3.lastType());

        // End A<->B so B becomes free again; nothing was queued for BRANCH-2 at this point, so B gets no notice.
        mediator.endChat("A");
        assertEquals(MessageType.CHAT_END, sellerB.lastType());

        // Now everyone at BRANCH-2 (B, C, M) is busy or freed-but-unqueued; queue a fresh request and free B up.
        RecordingChatEndpoint requester4 = new RecordingChatEndpoint();
        mediator.register(employee("F", "BRANCH-1", Role.SELLER), requester4);
        mediator.requestDirectChat("F", "C"); // C is busy (with D) -> queues under BRANCH-2
        assertEquals(MessageType.CHAT_QUEUED, requester4.lastType());

        mediator.endChat("D"); // frees C
        assertEquals(MessageType.CHAT_FREE_NOTICE, cashierC.lastType());
        ChatFreeNotice freeNotice = cashierC.lastPayload(ChatFreeNotice.class);
        assertEquals("F", freeNotice.getFromEmployeeNumber());

        // C calls back F directly.
        mediator.requestDirectChat("C", "F");
        assertEquals(MessageType.CHAT_STARTED, requester4.lastType());
        assertEquals(MessageType.CHAT_STARTED, cashierC.lastType());
    }

    @Test
    void shiftManagerCanJoinAnExistingSession() {
        mediator.requestChat("A", "BRANCH-2"); // A <-> B

        assertTrue(mediator.joinChat("M", "B"));

        ChatStartedNotice noticeOnManager = shiftManager.lastPayload(ChatStartedNotice.class);
        assertEquals(3, noticeOnManager.getParticipantEmployeeNumbers().size());
        assertTrue(mediator.isBusy("M"));
    }

    @Test
    void joinChatReturnsFalseWhenTargetHasNoActiveSession() {
        assertFalse(mediator.joinChat("M", "B")); // B isn't in any chat yet
        assertFalse(mediator.isBusy("M"));
    }

    @Test
    void shiftManagerAlreadyBusyCannotJoinASecondSessionWithoutCorruptingTheFirst() {
        mediator.requestChat("A", "BRANCH-2"); // A <-> B  (session 1)
        RecordingChatEndpoint requesterD = new RecordingChatEndpoint();
        mediator.register(employee("D", "BRANCH-1", Role.SELLER), requesterD);
        mediator.requestDirectChat("D", "C"); // D <-> C  (session 2 — C was still free)

        assertTrue(mediator.joinChat("M", "B"), "first join should succeed");
        assertFalse(mediator.joinChat("M", "C"),
                "a shift manager already busy in one session must not be able to join a second");

        // Ending session 1 should still correctly notify M — proving the rejected join attempt
        // didn't corrupt M's real mapping to session 1 (the bug this guards against: without the
        // isBusy check, the second joinChat call would silently repoint M's session mapping to
        // session 2 while session 1's participant list still listed M).
        mediator.endChat("A");
        assertEquals(MessageType.CHAT_END, shiftManager.lastType());
        assertFalse(mediator.isBusy("M"));

        // Session 2 is completely unaffected — M was never actually added to it.
        assertTrue(mediator.isBusy("D"));
        assertTrue(mediator.isBusy("C"));
    }

    @Test
    void messagesAreDeliveredToEveryOtherParticipantOnly() {
        mediator.requestChat("A", "BRANCH-2"); // A <-> B
        sellerA.payloads.clear();
        sellerA.types.clear();
        sellerB.payloads.clear();
        sellerB.types.clear();

        mediator.sendMessage("A", "hello from A");

        assertEquals(MessageType.CHAT_MESSAGE, sellerB.lastType());
        assertEquals("hello from A", sellerB.lastPayload(ChatMessageDto.class).getText());
        assertTrue(sellerA.types.isEmpty(), "sender should not receive its own message back");
    }

    @Test
    void requestingADirectChatWithABusyBranchlessEmployeeDoesNotCrash() {
        // A branchless employee (e.g. an ADMIN account, which has no branchId) who's already busy
        // used to make requestDirectChat's queueing path call pendingByBranch.computeIfAbsent(null,
        // ...) — a NullPointerException on the ConcurrentHashMap this used to be backed by.
        RecordingChatEndpoint adminEndpoint = new RecordingChatEndpoint();
        mediator.register(employee("ADMIN1", null, Role.ADMIN), adminEndpoint);
        mediator.requestDirectChat("A", "ADMIN1"); // A <-> ADMIN1, so ADMIN1 is now busy
        RecordingChatEndpoint requesterZ = new RecordingChatEndpoint();
        mediator.register(employee("Z", "BRANCH-1", Role.SELLER), requesterZ);

        mediator.requestDirectChat("Z", "ADMIN1"); // ADMIN1 is busy and branchless -> must not throw

        assertEquals(MessageType.CHAT_QUEUED, requesterZ.lastType());
    }

    @Test
    void endingChatFreesBothParticipants() {
        mediator.requestChat("A", "BRANCH-2");

        mediator.endChat("A");

        assertFalse(mediator.isBusy("A"));
        assertFalse(mediator.isBusy("B"));
    }
}
