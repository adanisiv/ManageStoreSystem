package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.Employee;
import managestore.common.model.Role;
import managestore.common.model.StoreChain;
import managestore.common.protocol.CustomerAddRequest;
import managestore.common.protocol.EmployeeAddRequest;
import managestore.common.protocol.EmployeeAddResponse;
import managestore.common.protocol.ErrorMessage;
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
 * Before PersonalIdValidator/PhoneValidator existed, the Admin "add employee"
 * and "add customer" forms accepted anything at all — {@code Objects.requireNonNull}
 * rejects null but not "", "asdf", or a personal ID that's the wrong length or
 * fails the Israeli ID checksum. This proves the real request/response path
 * (not just the validator classes in isolation) now rejects garbage input
 * and still accepts a real one.
 */
class InputValidationIntegrationTest {

    private final Gson gson = new Gson();

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().logout("validationAdmin");
    }

    @Test
    void addEmployeeRejectsInvalidPersonalIdAndPhoneButAcceptsValidOnes() throws Exception {
        StoreChain storeChain = new StoreChain();
        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("ADMIN1", "The Boss", "1", "050-1", "ACC-1", null, Role.ADMIN), "validationAdmin", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (MessageChannel admin = loginAs(port, "validationAdmin")) {

            // Garbage personal ID (fails the Israeli ID checksum) is rejected.
            admin.send(Message.of(gson, MessageType.EMPLOYEE_ADD_REQUEST, new EmployeeAddRequest(
                    "E1", "Dana Cohen", "111111111", "050-1111111", "ACC-1", "B1", "SELLER", "dana1", "Secret12")));
            EmployeeAddResponse rejectedForBadId = admin.receive().readPayload(gson, EmployeeAddResponse.class);
            assertFalse(rejectedForBadId.isSuccess(), "a personal ID failing the checksum should be rejected");

            // Garbage phone number (not digits / wrong shape) is rejected, using a personal ID
            // that *does* pass the checksum this time, to isolate what's actually being tested.
            admin.send(Message.of(gson, MessageType.EMPLOYEE_ADD_REQUEST, new EmployeeAddRequest(
                    "E2", "Roi Levi", "123456782", "not-a-phone", "ACC-2", "B1", "SELLER", "roi1", "Secret12")));
            EmployeeAddResponse rejectedForBadPhone = admin.receive().readPayload(gson, EmployeeAddResponse.class);
            assertFalse(rejectedForBadPhone.isSuccess(), "a garbage phone number should be rejected");

            // A real, checksum-valid personal ID and plausible phone number succeed.
            admin.send(Message.of(gson, MessageType.EMPLOYEE_ADD_REQUEST, new EmployeeAddRequest(
                    "E3", "Maya Katz", "123456782", "050-1234567", "ACC-3", "B1", "SELLER", "maya1", "Secret12")));
            EmployeeAddResponse accepted = admin.receive().readPayload(gson, EmployeeAddResponse.class);
            assertTrue(accepted.isSuccess(), "a valid personal ID and phone should be accepted: " + accepted.getErrorMessage());
        } finally {
            serverSocket.close();
            clientPool.shutdownNow();
        }
    }

    @Test
    void addCustomerRejectsInvalidPersonalId() throws Exception {
        StoreChain storeChain = new StoreChain();
        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("ADMIN1", "The Boss", "1", "050-1", "ACC-1", null, Role.ADMIN), "validationAdmin", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (MessageChannel admin = loginAs(port, "validationAdmin")) {
            admin.send(Message.of(gson, MessageType.CUSTOMER_ADD_REQUEST,
                    new CustomerAddRequest("not-an-id", "Noa Levi", "050-1234567", "NEW")));
            Message response = admin.receive();
            assertTrue(response.getType() == MessageType.ERROR, "an invalid personal ID should come back as an ERROR");
            ErrorMessage error = response.readPayload(gson, ErrorMessage.class);
            assertTrue(error.getMessage().contains("Personal ID"), "error should explain what was wrong: " + error.getMessage());
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
