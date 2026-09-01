package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.Branch;
import managestore.common.model.Employee;
import managestore.common.model.Product;
import managestore.common.model.Role;
import managestore.common.model.StoreChain;
import managestore.common.protocol.InventoryUpdateNotice;
import managestore.common.protocol.LogListRequest;
import managestore.common.protocol.LogListResponse;
import managestore.common.protocol.LoginRequest;
import managestore.common.protocol.LoginResponse;
import managestore.common.protocol.Message;
import managestore.common.protocol.MessageChannel;
import managestore.common.protocol.MessageType;
import managestore.common.protocol.RestockRequest;
import managestore.common.protocol.RestockResponse;
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
 * Proves the Inventory interface's "purchase" side (restocking, as opposed
 * to selling) end-to-end: adds stock, pushes a live INVENTORY_UPDATE to
 * another employee at the same branch (same Observer wiring as a sale), and
 * writes a PURCHASE-typed log entry — matching the brief's "will allow
 * performing purchase and sale of products" and its logging-by-action-type
 * requirement.
 */
class RestockIntegrationTest {

    private static final String BRANCH_ID = "B1";
    private final Gson gson = new Gson();

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().logout("restockAdmin");
        SessionManager.getInstance().logout("restockSeller");
        LogManager.getInstance().clear();
    }

    @Test
    void restockAddsStockPushesLiveUpdateAndLogsAsPurchase() throws Exception {
        LogManager.getInstance().clear();
        StoreChain storeChain = new StoreChain();
        Branch branch = new Branch(BRANCH_ID, "Downtown");
        Product shirt = new Product("SKU-1", "Shirt", "Tops", 100.0);
        branch.getInventory().addStock(shirt, 5);
        storeChain.addBranch(branch);
        storeChain.addProduct(shirt);

        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("ADMIN1", "The Boss", "1", "050-1", "ACC-1", BRANCH_ID, Role.ADMIN),
                "restockAdmin", "secret123");
        authService.createAccount(
                new Employee("E2", "Seller B", "2", "050-2", "ACC-2", BRANCH_ID, Role.SELLER),
                "restockSeller", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (MessageChannel adminChannel = loginAs(port, "restockAdmin");
             MessageChannel sellerChannel = loginAs(port, "restockSeller")) {

            adminChannel.send(Message.of(gson, MessageType.RESTOCK_REQUEST, new RestockRequest("SKU-1", 20)));

            // Same Observer wiring as a sale: the seller, who did nothing but sit on
            // its socket, gets an unsolicited push with the new stock level.
            InventoryUpdateNotice noticeOnSeller = sellerChannel.receive().readPayload(gson, InventoryUpdateNotice.class);
            assertEquals("SKU-1", noticeOnSeller.getEntry().getSku());
            assertEquals(25, noticeOnSeller.getEntry().getQuantity());

            InventoryUpdateNotice noticeOnAdmin = adminChannel.receive().readPayload(gson, InventoryUpdateNotice.class);
            assertEquals(25, noticeOnAdmin.getEntry().getQuantity());

            RestockResponse response = adminChannel.receive().readPayload(gson, RestockResponse.class);
            assertTrue(response.isSuccess(), "restock failed: " + response.getErrorMessage());
            assertEquals(25, response.getNewQuantity());

            adminChannel.send(Message.of(gson, MessageType.LOG_LIST_REQUEST, new LogListRequest("PURCHASE")));
            LogListResponse logResponse = adminChannel.receive().readPayload(gson, LogListResponse.class);
            assertEquals(1, logResponse.getEvents().size());
            assertTrue(logResponse.getEvents().get(0).getDetails().contains("SKU-1"));
        } finally {
            serverSocket.close();
            clientPool.shutdownNow();
        }
    }

    @Test
    void restockingAnUnknownSkuFails() throws Exception {
        StoreChain storeChain = new StoreChain();
        storeChain.addBranch(new Branch(BRANCH_ID, "Downtown"));

        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("ADMIN1", "The Boss", "1", "050-1", "ACC-1", BRANCH_ID, Role.ADMIN),
                "restockAdmin", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (MessageChannel adminChannel = loginAs(port, "restockAdmin")) {
            adminChannel.send(Message.of(gson, MessageType.RESTOCK_REQUEST, new RestockRequest("NO-SUCH-SKU", 20)));
            RestockResponse response = adminChannel.receive().readPayload(gson, RestockResponse.class);
            assertFalse(response.isSuccess());
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
