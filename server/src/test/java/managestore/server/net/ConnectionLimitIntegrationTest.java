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
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ServerMain.acceptLoop}'s connection limit, proven with a limit of 1 so it's easy to
 * trigger without opening dozens of real sockets. A second client connecting while the one
 * permitted slot is taken gets no response at all until the first client disconnects.
 */
class ConnectionLimitIntegrationTest {

    private final Gson gson = new Gson();

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().logout("limitedUser1");
        SessionManager.getInstance().logout("limitedUser2");
    }

    @Test
    void aSecondConnectionWaitsUntilTheFirstDisconnectsWhenTheLimitIsOne() throws Exception {
        StoreChain storeChain = new StoreChain();
        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("E1", "First", "204812077", "050-1", "ACC-1", null, Role.ADMIN), "limitedUser1", "secret123");
        authService.createAccount(
                new Employee("E2", "Second", "309825149", "050-2", "ACC-2", null, Role.ADMIN), "limitedUser2", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        // The limit of 1 is the whole point of this test -- with the real default of 50, we
        // would need 50 real open sockets before a 51st would ever notice anything.
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool, 1));
        serverThread.setDaemon(true);
        serverThread.start();

        Socket firstSocket = null;
        Socket secondSocket = null;
        try {
            // First connection takes the one permit and logs in normally.
            firstSocket = new Socket("localhost", port);
            MessageChannel first = new MessageChannel(firstSocket, gson);
            first.send(Message.of(gson, MessageType.LOGIN_REQUEST, new LoginRequest("limitedUser1", "secret123")));
            LoginResponse firstLogin = first.receive().readPayload(gson, LoginResponse.class);
            assertTrue(firstLogin.isSuccess(), "the first connection should be accepted normally");

            // Second connection: the TCP handshake itself succeeds (the operating system accepts
            // it into its own backlog independently of our code), but our accept loop is now
            // stuck waiting for the one permit the first connection is holding, so nothing on the
            // server side ever reads this socket or answers it -- not even to reject it.
            secondSocket = new Socket("localhost", port);
            secondSocket.setSoTimeout(300);
            MessageChannel second = new MessageChannel(secondSocket, gson);
            second.send(Message.of(gson, MessageType.LOGIN_REQUEST, new LoginRequest("limitedUser2", "secret123")));
            assertThrows(SocketTimeoutException.class, second::receive,
                    "with the limit already used up, the second connection must get no response at all, not even a rejection");

            // Freeing the first connection's permit should let the second one through.
            first.close();
            secondSocket.setSoTimeout(0);
            LoginResponse secondLogin = second.receive().readPayload(gson, LoginResponse.class);
            assertTrue(secondLogin.isSuccess(), "once a slot frees up, the connection that was waiting should be served normally");
        } finally {
            if (firstSocket != null) {
                firstSocket.close();
            }
            if (secondSocket != null) {
                secondSocket.close();
            }
            serverSocket.close();
            clientPool.shutdownNow();
        }
    }
}
