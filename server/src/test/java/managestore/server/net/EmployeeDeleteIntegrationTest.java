package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.Employee;
import managestore.common.model.Role;
import managestore.common.model.StoreChain;
import managestore.common.protocol.EmployeeDeleteRequest;
import managestore.common.protocol.EmployeeDeleteResponse;
import managestore.common.protocol.EmployeeListResponse;
import managestore.common.protocol.LogEventDto;
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delete-employee feature end-to-end: admin-only, refuses self-deletion, refuses an unknown
 * employee number, actually removes the employee from the roster and their login, and logs it.
 */
class EmployeeDeleteIntegrationTest {

    private final Gson gson = new Gson();

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().logout("deleteAdmin");
        SessionManager.getInstance().logout("deleteSeller");
        SessionManager.getInstance().logout("victim");
        LogManager.getInstance().clear();
    }

    @Test
    void adminCanDeleteAnEmployeeWhoCanNoLongerLogInAfterward() throws Exception {
        LogManager.getInstance().clear();
        StoreChain storeChain = new StoreChain();
        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("ADMIN1", "The Boss", "1", "050-1", "ACC-1", null, Role.ADMIN), "deleteAdmin", "secret123");
        authService.createAccount(
                new Employee("VICTIM1", "Soon Gone", "123456782", "050-1234567", "ACC-2", "BRANCH-1", Role.SELLER),
                "victim", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (MessageChannel admin = loginAs(port, "deleteAdmin")) {
            admin.send(Message.of(gson, MessageType.EMPLOYEE_DELETE_REQUEST, new EmployeeDeleteRequest("VICTIM1")));
            EmployeeDeleteResponse response = admin.receive().readPayload(gson, EmployeeDeleteResponse.class);
            assertTrue(response.isSuccess(), "admin should be able to delete an employee: " + response.getErrorMessage());

            admin.send(Message.of(gson, MessageType.EMPLOYEE_LIST_REQUEST, new Object()));
            EmployeeListResponse list = admin.receive().readPayload(gson, EmployeeListResponse.class);
            assertFalse(list.getEmployees().stream().anyMatch(emp -> emp.getEmployeeNumber().equals("VICTIM1")),
                    "the deleted employee should no longer appear in the roster");

            admin.send(Message.of(gson, MessageType.LOG_LIST_REQUEST, new LogListRequest("EMPLOYEE_REMOVED")));
            LogListResponse logResponse = admin.receive().readPayload(gson, LogListResponse.class);
            assertTrue(logResponse.getEvents().stream().anyMatch(evt -> evt.getDetails().contains("VICTIM1")),
                    "expected an EMPLOYEE_REMOVED log entry for the deletion");
        } finally {
            serverSocket.close();
            clientPool.shutdownNow();
        }

        // Separately (after the server's stopped) prove the deleted account can't authenticate at all.
        LoginResponse loginAttempt = authService.login("victim", "secret123");
        assertFalse(loginAttempt.isSuccess(), "a deleted employee's account should no longer be able to log in");
    }

    @Test
    void nonAdminCannotDeleteAnEmployee() throws Exception {
        StoreChain storeChain = new StoreChain();
        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("SELLER1", "Regular Seller", "204812077", "050-1234567", "ACC-1", "BRANCH-1", Role.SELLER),
                "deleteSeller", "secret123");
        authService.createAccount(
                new Employee("VICTIM1", "Should Survive", "309825149", "050-7654321", "ACC-2", "BRANCH-1", Role.CASHIER),
                "victim", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (MessageChannel seller = loginAs(port, "deleteSeller")) {
            seller.send(Message.of(gson, MessageType.EMPLOYEE_DELETE_REQUEST, new EmployeeDeleteRequest("VICTIM1")));
            EmployeeDeleteResponse response = seller.receive().readPayload(gson, EmployeeDeleteResponse.class);
            assertFalse(response.isSuccess(), "a non-admin must not be able to delete an employee");
        } finally {
            serverSocket.close();
            clientPool.shutdownNow();
        }

        assertTrue(authService.login("victim", "secret123").isSuccess(), "the target employee must be untouched");
    }

    @Test
    void adminCannotDeleteTheirOwnAccountWhileLoggedInAsIt() throws Exception {
        StoreChain storeChain = new StoreChain();
        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("ADMIN1", "The Boss", "1", "050-1", "ACC-1", null, Role.ADMIN), "deleteAdmin", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (MessageChannel admin = loginAs(port, "deleteAdmin")) {
            admin.send(Message.of(gson, MessageType.EMPLOYEE_DELETE_REQUEST, new EmployeeDeleteRequest("ADMIN1")));
            EmployeeDeleteResponse response = admin.receive().readPayload(gson, EmployeeDeleteResponse.class);
            assertFalse(response.isSuccess(), "an admin must not be able to delete their own currently-logged-in account");
        } finally {
            serverSocket.close();
            clientPool.shutdownNow();
        }

        assertTrue(authService.login("deleteAdmin", "secret123").isSuccess(), "the admin's own account must be untouched");
    }

    @Test
    void deletingAnUnknownEmployeeNumberFailsCleanly() throws Exception {
        StoreChain storeChain = new StoreChain();
        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("ADMIN1", "The Boss", "1", "050-1", "ACC-1", null, Role.ADMIN), "deleteAdmin", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (MessageChannel admin = loginAs(port, "deleteAdmin")) {
            admin.send(Message.of(gson, MessageType.EMPLOYEE_DELETE_REQUEST, new EmployeeDeleteRequest("NO-SUCH-EMPLOYEE")));
            EmployeeDeleteResponse response = admin.receive().readPayload(gson, EmployeeDeleteResponse.class);
            assertFalse(response.isSuccess(), "deleting a nonexistent employee number should fail with a clear message, not silently succeed");
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
