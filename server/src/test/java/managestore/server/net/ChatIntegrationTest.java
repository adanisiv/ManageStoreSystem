package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.Employee;
import managestore.common.model.Role;
import managestore.common.model.StoreChain;
import managestore.common.protocol.ChatMessageDto;
import managestore.common.protocol.ChatRequestDto;
import managestore.common.protocol.ChatStartedNotice;
import managestore.common.protocol.LoginRequest;
import managestore.common.protocol.LoginResponse;
import managestore.common.protocol.Message;
import managestore.common.protocol.MessageChannel;
import managestore.common.protocol.MessageType;
import managestore.server.service.AuthService;
import managestore.server.service.InMemoryAccountRepository;
import managestore.server.service.InMemoryEmployeeRepository;
import managestore.server.service.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the chat Mediator is correctly reachable over real sockets: an
 * employee at one branch requests a chat with any free employee at another
 * branch, gets matched, and a message sent by one side arrives at the other
 * — all through actual ClientHandler/ChatMediator wiring, not the unit-level
 * fakes used in ChatMediatorTest.
 */
class ChatIntegrationTest {

    private final Gson gson = new Gson();

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().logout("chatEmpA");
        SessionManager.getInstance().logout("chatEmpB");
    }

    @Test
    void chatRequestMatchesFreeEmployeeAndMessagesFlowBothWays() throws Exception {
        StoreChain storeChain = new StoreChain();
        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("CA", "Chat Employee A", "1", "050-1", "ACC-1", "BRANCH-A", Role.SELLER),
                "chatEmpA", "secret123");
        authService.createAccount(
                new Employee("CB", "Chat Employee B", "2", "050-2", "ACC-2", "BRANCH-B", Role.SELLER),
                "chatEmpB", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (MessageChannel channelA = loginAs(port, "chatEmpA");
             MessageChannel channelB = loginAs(port, "chatEmpB")) {

            channelA.send(Message.of(gson, MessageType.CHAT_REQUEST, new ChatRequestDto("BRANCH-B")));

            ChatStartedNotice noticeOnA = channelA.receive().readPayload(gson, ChatStartedNotice.class);
            ChatStartedNotice noticeOnB = channelB.receive().readPayload(gson, ChatStartedNotice.class);
            assertEquals(noticeOnA.getSessionId(), noticeOnB.getSessionId());
            assertTrue(noticeOnA.getParticipantEmployeeNumbers().containsAll(java.util.Arrays.asList("CA", "CB")));

            channelA.send(Message.of(gson, MessageType.CHAT_MESSAGE,
                    new ChatMessageDto(noticeOnA.getSessionId(), "CA", "hi from A")));
            ChatMessageDto messageOnB = channelB.receive().readPayload(gson, ChatMessageDto.class);
            assertEquals("hi from A", messageOnB.getText());

            channelB.send(Message.of(gson, MessageType.CHAT_MESSAGE,
                    new ChatMessageDto(noticeOnA.getSessionId(), "CB", "hi back from B")));
            ChatMessageDto messageOnA = channelA.receive().readPayload(gson, ChatMessageDto.class);
            assertEquals("hi back from B", messageOnA.getText());
        } finally {
            serverSocket.close();
            clientPool.shutdownNow();
        }
    }

    private MessageChannel loginAs(int port, String username) throws Exception {
        Socket socket = new Socket("localhost", port);
        MessageChannel channel = new MessageChannel(socket, gson);
        channel.send(Message.of(gson, MessageType.LOGIN_REQUEST, new LoginRequest(username, "secret123")));
        LoginResponse response = channel.receive().readPayload(gson, LoginResponse.class);
        assertTrue(response.isSuccess(), "login should succeed for " + username);
        return channel;
    }
}
