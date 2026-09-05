package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.Employee;
import managestore.common.model.Role;
import managestore.common.model.StoreChain;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test through real TCP sockets: starts an actual ServerMain
 * accept loop, connects two independent client sockets, and proves that the
 * second login attempt for the same username is rejected while the first
 * remains logged in — exercised exactly as it would happen in production.
 */
class ServerMainIntegrationTest {

    private static final String USERNAME = "dana-integration-test";
    private final Gson gson = new Gson();

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().logout(USERNAME);
    }

    @Test
    void secondSocketLoginForSameUserIsRejectedWhileFirstStaysConnected() throws Exception {
        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        Employee employee = new Employee("E1", "Dana Cohen", "123456789", "050-1111111", "ACC-1", "BRANCH-1", Role.CASHIER);
        authService.createAccount(employee, USERNAME, "secret123");

        ServerContext context = new ServerContext(new StoreChain(), authService, employeeRepository, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (Socket firstSocket = new Socket("localhost", port);
             MessageChannel firstChannel = new MessageChannel(firstSocket, gson)) {

            firstChannel.send(Message.of(gson, MessageType.LOGIN_REQUEST, new LoginRequest(USERNAME, "secret123")));
            LoginResponse firstResponse = firstChannel.receive().readPayload(gson, LoginResponse.class);
            assertTrue(firstResponse.isSuccess(), "first login should succeed");

            try (Socket secondSocket = new Socket("localhost", port);
                 MessageChannel secondChannel = new MessageChannel(secondSocket, gson)) {

                secondChannel.send(Message.of(gson, MessageType.LOGIN_REQUEST, new LoginRequest(USERNAME, "secret123")));
                LoginResponse secondResponse = secondChannel.receive().readPayload(gson, LoginResponse.class);

                assertFalse(secondResponse.isSuccess(), "second concurrent login for the same user should be rejected");
            }
        } finally {
            serverSocket.close();
            clientPool.shutdownNow();
        }
    }
}
