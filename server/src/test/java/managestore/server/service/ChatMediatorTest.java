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
    void requestChatRefusesARequesterAlreadyBusyInAnotherSession() {
        // The most ordinary way to hit this: click "Request Chat", then click it again before
        // ending the first one. Without the guard, the second call would silently repoint A's
        // sessionByEmployeeNumber entry to the new session while the first session's participant
        // list still lists A — corrupting both, exactly like the unguarded joinChat bug did.
        assertTrue(mediator.requestChat("A", "BRANCH-2")); // A <-> B

        assertFalse(mediator.requestChat("A", "BRANCH-2"), "A is already busy — a second request must be refused, not silently succeed");

        // A's real session (with B) must be completely unaffected by the refused second attempt.
        mediator.endChat("A");
        assertEquals(MessageType.CHAT_END, sellerB.lastType());
        assertFalse(mediator.isBusy("A"));
        assertFalse(mediator.isBusy("B"));
    }

    @Test
    void requestDirectChatToTheExactPersonAlreadyChattingWithIsAHarmlessNoOp() {
        // E.g. a stale "Call back" button clicked again after the callback already connected.
        assertTrue(mediator.requestDirectChat("A", "B")); // A <-> B

        assertTrue(mediator.requestDirectChat("A", "B"), "re-requesting the exact person already being chatted with should succeed as a no-op");
        assertTrue(mediator.isBusy("A"));
        assertTrue(mediator.isBusy("B"));
    }

    @Test
    void requestDirectChatToOneselfIsRefusedRatherThanCreatingADegenerateSession() {
        // Reachable from the client's "chat with a specific employee" field: typing your own
        // employee number. Without a guard, this would pass every other check (A is connected
        // and not busy) and call startSession("A", "A"), adding "A" to the participant list
        // twice and leaving a "session" with no one else actually in it.
        assertFalse(mediator.requestDirectChat("A", "A"), "a request targeting yourself must be refused");

        assertFalse(mediator.isBusy("A"));
        assertTrue(sellerA.types.isEmpty(), "a refused self-request must not start any session");
    }

    @Test
    void requestDirectChatRefusesARequesterBusyWithSomeoneElse() {
        assertTrue(mediator.requestChat("A", "BRANCH-2")); // A <-> B
        RecordingChatEndpoint requesterD = new RecordingChatEndpoint();
        mediator.register(employee("D", "BRANCH-1", Role.SELLER), requesterD);

        assertFalse(mediator.requestDirectChat("A", "D"), "A is already busy with B — calling a third party must be refused");
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
    void rejoiningTheSameSessionIsAHarmlessNoOp() {
        // A double-click on "Join" (or a client retry) targeting a session the shift manager is
        // already in must not be treated as "busy in a different one" — it's the same one.
        mediator.requestChat("A", "BRANCH-2"); // A <-> B

        assertTrue(mediator.joinChat("M", "B"));
        assertTrue(mediator.joinChat("M", "A"), "M is already in A's session too — re-joining via either participant should succeed");
        assertTrue(mediator.isBusy("M"));
    }

    @Test
    void disconnectingRemovesTheEmployeesOwnQueuedRequestSoItCantSurfaceAsAGhostNotification() {
        // Occupy every employee at BRANCH-2 (B, C, and M — the shift manager, who's also
        // registered there — must be busy too, or a request would connect to them instead of
        // queueing), then have Z queue a request for that branch, then disconnect Z.
        mediator.requestChat("A", "BRANCH-2"); // A <-> B
        RecordingChatEndpoint requesterD = new RecordingChatEndpoint();
        mediator.register(employee("D", "BRANCH-1", Role.SELLER), requesterD);
        mediator.requestDirectChat("D", "C"); // D <-> C
        mediator.joinChat("M", "B"); // M joins A/B's session, so now B, C, and M are all busy
        RecordingChatEndpoint requesterZ = new RecordingChatEndpoint();
        mediator.register(employee("Z", "BRANCH-1", Role.SELLER), requesterZ);
        mediator.requestChat("Z", "BRANCH-2"); // everyone at BRANCH-2 busy -> queues
        assertEquals(MessageType.CHAT_QUEUED, requesterZ.lastType());

        mediator.unregister("Z"); // Z closes the app / loses connection while still queued

        cashierC.types.clear();
        cashierC.payloads.clear();
        mediator.endChat("D"); // frees C — before the fix, C would additionally get a CHAT_FREE_NOTICE about Z

        // C does get its own CHAT_END here (D<->C's session ending) — that's expected and correct.
        // What must NOT happen is a CHAT_FREE_NOTICE about Z, whose queued request should have been
        // purged on disconnect.
        assertFalse(cashierC.types.contains(MessageType.CHAT_FREE_NOTICE),
                "a disconnected employee's stale queued request must not surface as a callback notification");
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

        // ADMIN1 is busy and has no branch. A queue is only ever drained by looking it up under a
        // freed employee's own branch id, so filing this under a null key would put Z in a line
        // that nothing can ever call — and Z's client would sit on "waiting in queue" forever.
        boolean accepted = mediator.requestDirectChat("Z", "ADMIN1");

        assertFalse(accepted, "a callback to a branchless employee cannot be queued, so it must be refused");
        assertTrue(requesterZ.types.isEmpty(), "Z must not be told it is queued when nothing can dequeue it");
    }

    @Test
    void aRequestQueuedBeforeAnyoneIsOnlineIsDeliveredWhenTheyLogIn() {
        // The queue is drained when an employee becomes free, and logging in is one of the two
        // ways that happens (ending a chat is the other). Without this, a request made against a
        // branch nobody had connected from yet would wait for an unrelated chat at that branch to
        // start and then end — which, on a freshly started server, never happens.
        ChatMediator empty = new ChatMediator();
        RecordingChatEndpoint requester = new RecordingChatEndpoint();
        empty.register(employee("A", "BRANCH-1", Role.SELLER), requester);

        empty.requestChat("A", "BRANCH-2"); // nobody from BRANCH-2 is connected yet
        assertEquals(MessageType.CHAT_QUEUED, requester.lastType());

        RecordingChatEndpoint latecomer = new RecordingChatEndpoint();
        empty.register(employee("B", "BRANCH-2", Role.SELLER), latecomer);

        assertEquals(MessageType.CHAT_FREE_NOTICE, latecomer.lastType(),
                "logging in should surface the request that was already waiting for this branch");
        assertEquals("A", latecomer.lastPayload(ChatFreeNotice.class).getFromEmployeeNumber());
    }

    @Test
    void askingAgainWhileQueuedReplacesTheRequestRatherThanDuplicatingIt() {
        // The client leaves "Request Chat" enabled while queued so the user can switch branches
        // instead of being stuck. Each new ask must therefore replace the previous one — two
        // queued entries for the same person would fire two separate callback notices later.
        ChatMediator empty = new ChatMediator();
        RecordingChatEndpoint requester = new RecordingChatEndpoint();
        empty.register(employee("A", "BRANCH-1", Role.SELLER), requester);

        empty.requestChat("A", "BRANCH-2");
        empty.requestChat("A", "BRANCH-2");
        empty.requestChat("A", "BRANCH-2");

        // Two people from BRANCH-2 come online. Each login drains one entry from that branch's
        // queue, so this distinguishes "one entry" from "three": with duplicates left in the
        // queue the second arrival would be told to call A back as well, for a request A only
        // ever made once. Asserting on the first arrival alone would pass either way, since a
        // single drain pops a single notice no matter how many duplicates are behind it.
        RecordingChatEndpoint first = new RecordingChatEndpoint();
        empty.register(employee("B", "BRANCH-2", Role.SELLER), first);
        RecordingChatEndpoint second = new RecordingChatEndpoint();
        empty.register(employee("C", "BRANCH-2", Role.CASHIER), second);

        assertEquals(MessageType.CHAT_FREE_NOTICE, first.lastType(), "the first to arrive takes the request");
        assertTrue(second.types.isEmpty(), "the queue must be empty by then — three asks left one entry, not three");
    }

    @Test
    void endingChatFreesBothParticipants() {
        mediator.requestChat("A", "BRANCH-2");

        mediator.endChat("A");

        assertFalse(mediator.isBusy("A"));
        assertFalse(mediator.isBusy("B"));
    }
}
