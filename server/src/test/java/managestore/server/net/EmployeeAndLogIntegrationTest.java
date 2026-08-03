package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.Employee;
import managestore.common.model.Role;
import managestore.common.model.StoreChain;
import managestore.common.protocol.EmployeeAddRequest;
import managestore.common.protocol.EmployeeAddResponse;
import managestore.common.protocol.EmployeeListResponse;
import managestore.common.protocol.LogListRequest;
import managestore.common.protocol.LogListResponse;
import managestore.common.protocol.LoginRequest;
import managestore.common.protocol.LoginResponse;
import managestore.common.protocol.Message;
import managestore.common.protocol.MessageChannel;
import managestore.common.protocol.MessageType;
import managestore.server.service.AuthService;
import managestore.server.service.InMemoryAccountRepository;
import managestore.server.service.InMemoryEmployeeRepository;
import managestore.server.service.LogManager;
import managestore.server.service.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the Admin-only employee registration flow and the resulting
 * EMPLOYEE_REGISTERED log entry, and that a non-admin is refused both
 * actions — over real sockets, exactly as the Admin/Employees GUI screens
 * would exercise it.
 */
class EmployeeAndLogIntegrationTest {

    private final Gson gson = new Gson();

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().logout("boss");
        SessionManager.getInstance().logout("regularSeller");
        LogManager.getInstance().clear();
    }

    @Test
    void adminCanAddEmployeeAndSeeItLogged_nonAdminIsRefused() throws Exception {
        LogManager.getInstance().clear();
        StoreChain storeChain = new StoreChain();
        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("ADMIN1", "The Boss", "1", "050-1", "ACC-1", null, Role.ADMIN), "boss", "secret123");
        authService.createAccount(
                new Employee("SELLER1", "Regular Seller", "2", "050-2", "ACC-2", "BRANCH-1", Role.SELLER),
                "regularSeller", "secret123");
        storeChain.addBranch(new managestore.common.model.Branch("BRANCH-1", "Downtown"));

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (MessageChannel adminChannel = loginAs(port, "boss");
             MessageChannel sellerChannel = loginAs(port, "regularSeller")) {

            adminChannel.send(Message.of(gson, MessageType.EMPLOYEE_ADD_REQUEST,
                    new EmployeeAddRequest("NEW1", "New Hire", "3", "050-3", "ACC-3", "BRANCH-1",
                            "CASHIER", "newhire", "secret123")));
            EmployeeAddResponse addResponse = adminChannel.receive().readPayload(gson, EmployeeAddResponse.class);
            assertTrue(addResponse.isSuccess(), "admin should be able to add an employee");

            adminChannel.send(Message.of(gson, MessageType.EMPLOYEE_LIST_REQUEST, new Object()));
            EmployeeListResponse listResponse = adminChannel.receive().readPayload(gson, EmployeeListResponse.class);
            assertTrue(listResponse.getEmployees().stream().anyMatch(e -> e.getEmployeeNumber().equals("NEW1")));

            adminChannel.send(Message.of(gson, MessageType.LOG_LIST_REQUEST, new LogListRequest(null)));
            LogListResponse logResponse = adminChannel.receive().readPayload(gson, LogListResponse.class);
            assertTrue(logResponse.getEvents().stream()
                    .anyMatch(e -> e.getType().equals("EMPLOYEE_REGISTERED") && e.getDetails().contains("NEW1")));

            sellerChannel.send(Message.of(gson, MessageType.EMPLOYEE_ADD_REQUEST,
                    new EmployeeAddRequest("NEW2", "Another Hire", "4", "050-4", "ACC-4", "BRANCH-1",
                            "CASHIER", "another", "secret123")));
            EmployeeAddResponse refusedAdd = sellerChannel.receive().readPayload(gson, EmployeeAddResponse.class);
            assertFalse(refusedAdd.isSuccess(), "non-admin must not be able to add an employee");

            sellerChannel.send(Message.of(gson, MessageType.LOG_LIST_REQUEST, new LogListRequest(null)));
            Message refusedLogMsg = sellerChannel.receive();
            assertEquals(MessageType.ERROR, refusedLogMsg.getType(), "non-admin must not be able to view the log");
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
