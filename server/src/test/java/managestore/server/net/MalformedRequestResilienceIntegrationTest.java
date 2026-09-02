package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.Employee;
import managestore.common.model.Role;
import managestore.common.model.StoreChain;
import managestore.common.protocol.EmployeeListResponse;
import managestore.common.protocol.ErrorMessage;
import managestore.common.protocol.LogListRequest;
import managestore.common.protocol.LoginRequest;
import managestore.common.protocol.LoginResponse;
import managestore.common.protocol.Message;
import managestore.common.protocol.MessageChannel;
import managestore.common.protocol.MessageType;
import managestore.common.protocol.ReportFormat;
import managestore.common.protocol.ReportRequest;
import managestore.common.protocol.ReportScope;
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
 * Proves that a single malformed request (here, a LOG_LIST_REQUEST whose
 * type filter isn't a real {@link managestore.common.model.LogType} constant)
 * reports an error back to that client instead of silently killing their
 * whole socket connection — a client sending one bad request should not
 * have to reconnect and log back in to keep using the app.
 */
class MalformedRequestResilienceIntegrationTest {

    private final Gson gson = new Gson();

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().logout("resilience-admin");
    }

    @Test
    void malformedRequestReturnsErrorInsteadOfDroppingTheConnection() throws Exception {
        StoreChain storeChain = new StoreChain();
        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("ADMIN1", "The Boss", "1", "050-1", "ACC-1", null, Role.ADMIN),
                "resilience-admin", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (Socket socket = new Socket("localhost", port);
             MessageChannel channel = new MessageChannel(socket, gson)) {

            channel.send(Message.of(gson, MessageType.LOGIN_REQUEST, new LoginRequest("resilience-admin", "secret123")));
            LoginResponse loginResponse = channel.receive().readPayload(gson, LoginResponse.class);
            assertTrue(loginResponse.isSuccess());

            // "NOT_A_REAL_LOG_TYPE" isn't a LogType enum constant, so the server-side
            // LogType.valueOf(...) call throws — this must not tear down the connection.
            channel.send(Message.of(gson, MessageType.LOG_LIST_REQUEST, new LogListRequest("NOT_A_REAL_LOG_TYPE")));
            Message errorMessage = channel.receive();
            assertEquals(MessageType.ERROR, errorMessage.getType(),
                    "a malformed request should come back as an ERROR, not silently drop the connection");
            assertTrue(errorMessage.readPayload(gson, ErrorMessage.class).getMessage().contains("NOT_A_REAL_LOG_TYPE"),
                    "should report the specific, readable reason (an unknown log type), not a generic dispatch failure message");

            // The connection must still be alive and usable for a normal follow-up request.
            channel.send(Message.of(gson, MessageType.EMPLOYEE_LIST_REQUEST, new Object()));
            Message followUp = channel.receive();
            assertEquals(MessageType.EMPLOYEE_LIST_RESPONSE, followUp.getType(),
                    "the same connection should still work normally after the malformed request");
            followUp.readPayload(gson, EmployeeListResponse.class);
        } finally {
            serverSocket.close();
            clientPool.shutdownNow();
        }
    }

    @Test
    void malformedReportDateReturnsAClearErrorInsteadOfDroppingTheConnection() throws Exception {
        StoreChain storeChain = new StoreChain();
        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("ADMIN1", "The Boss", "1", "050-1", "ACC-1", null, Role.ADMIN),
                "resilience-admin", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (Socket socket = new Socket("localhost", port);
             MessageChannel channel = new MessageChannel(socket, gson)) {

            channel.send(Message.of(gson, MessageType.LOGIN_REQUEST, new LoginRequest("resilience-admin", "secret123")));
            assertTrue(channel.receive().readPayload(gson, LoginResponse.class).isSuccess());

            // "not-a-date" isn't ISO-8601, so LocalDate.parse throws — this is only reachable at
            // all through a raw client, since the real UI's DatePicker always sends LocalDate's
            // own toString() (always ISO-8601, regardless of locale) — but the server should never
            // trust that a request actually came from that UI.
            channel.send(Message.of(gson, MessageType.REPORT_REQUEST,
                    new ReportRequest(ReportScope.ALL, null, ReportFormat.JSON, "not-a-date")));
            Message errorMessage = channel.receive();
            assertEquals(MessageType.ERROR, errorMessage.getType(),
                    "an unparseable report date should come back as a clear ERROR, not silently drop the connection");

            // The connection must still be alive and usable for a normal follow-up request.
            channel.send(Message.of(gson, MessageType.REPORT_REQUEST, new ReportRequest(ReportScope.ALL, null, ReportFormat.JSON)));
            Message followUp = channel.receive();
            assertEquals(MessageType.REPORT_RESPONSE, followUp.getType(),
                    "the same connection should still work normally after the malformed request");
        } finally {
            serverSocket.close();
            clientPool.shutdownNow();
        }
    }
}
