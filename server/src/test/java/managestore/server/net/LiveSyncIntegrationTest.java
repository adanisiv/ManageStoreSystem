package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.Branch;
import managestore.common.model.Employee;
import managestore.common.model.Product;
import managestore.common.model.Role;
import managestore.common.model.StoreChain;
import managestore.common.protocol.CustomerAddRequest;
import managestore.common.protocol.CustomerUpdateNotice;
import managestore.common.protocol.InventoryUpdateNotice;
import managestore.common.protocol.LoginRequest;
import managestore.common.protocol.LoginResponse;
import managestore.common.protocol.Message;
import managestore.common.protocol.MessageChannel;
import managestore.common.protocol.MessageType;
import managestore.common.protocol.PurchaseRequest;
import managestore.common.protocol.PurchaseResponse;
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
 * Proves the Observer pattern actually works end-to-end over real sockets:
 * two independently connected clients (Cashier A, Seller B) are both logged
 * into the same branch. A performs a purchase; B — who did nothing but sit
 * on its socket — receives an unsolicited INVENTORY_UPDATE push with the new
 * stock level. Same idea proven for a customer being added, pushed to a
 * third bystander client that only logged in and listened.
 */
class LiveSyncIntegrationTest {

    private static final String BRANCH_ID = "B1";
    private final Gson gson = new Gson();

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().logout("cashierA");
        SessionManager.getInstance().logout("sellerB");
        SessionManager.getInstance().logout("bystanderC");
    }

    @Test
    void purchaseByOneClientPushesInventoryUpdateToAnotherClientOnSameBranch() throws Exception {
        StoreChain storeChain = new StoreChain();
        Branch branch = new Branch(BRANCH_ID, "Downtown");
        Product shirt = new Product("SKU-1", "Shirt", "Tops", 100.0);
        branch.getInventory().addStock(shirt, 10);
        storeChain.addBranch(branch);
        storeChain.addProduct(shirt);

        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("E1", "Cashier A", "111", "050-1", "ACC-1", BRANCH_ID, Role.CASHIER),
                "cashierA", "secret123");
        authService.createAccount(
                new Employee("E2", "Seller B", "222", "050-2", "ACC-2", BRANCH_ID, Role.SELLER),
                "sellerB", "secret123");
        authService.createAccount(
                new Employee("E3", "Newbie Customer", "333333333", "050-3", "N/A", "N/A", Role.ADMIN),
                "bystanderC", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (MessageChannel cashierChannel = loginAs(port, "cashierA");
             MessageChannel sellerChannel = loginAs(port, "sellerB");
             MessageChannel bystanderChannel = loginAs(port, "bystanderC")) {

            // Cashier A purchases 2 shirts for a not-yet-existing customer; add the customer first
            // via the bystander client, and prove Seller B (who never asked) sees the new customer.
            // CustomerDirectory is network-wide, so every logged-in client (including cashierChannel
            // itself) gets this broadcast too — drain it from both before moving on, so it doesn't
            // race with the branch-scoped inventory push read further down.
            bystanderChannel.send(Message.of(gson, MessageType.CUSTOMER_ADD_REQUEST,
                    new CustomerAddRequest("999", "Noa Levi", "050-9", "VIP")));
            CustomerUpdateNotice customerNoticeOnSeller =
                    sellerChannel.receive().readPayload(gson, CustomerUpdateNotice.class);
            CustomerUpdateNotice customerNoticeOnCashier =
                    cashierChannel.receive().readPayload(gson, CustomerUpdateNotice.class);
            assertEquals("Noa Levi", customerNoticeOnSeller.getCustomer().getFullName());
            assertTrue(customerNoticeOnSeller.isNewlyAdded());
            assertEquals("Noa Levi", customerNoticeOnCashier.getCustomer().getFullName());

            // Inventory, unlike the customer directory, is per-branch: only cashierChannel (the
            // actor) and sellerChannel (same branch) are subscribed to Branch B1's Inventory, so
            // only they receive this push — bystanderChannel has no branch and never subscribed.
            cashierChannel.send(Message.of(gson, MessageType.PURCHASE_REQUEST,
                    new PurchaseRequest("SKU-1", 2, "999")));

            // Stock is decremented (and observers notified) INSIDE the purchase call, before the
            // explicit PURCHASE_RESPONSE is sent — so even the actor (cashier) sees the new stock
            // level arrive first via the same live-push channel as every other branch employee,
            // not as a side effect baked into the response. That uniformity is the point of using
            // Observer here rather than special-casing "also tell the sender".
            InventoryUpdateNotice inventoryNoticeOnCashier =
                    cashierChannel.receive().readPayload(gson, InventoryUpdateNotice.class);
            assertEquals(8, inventoryNoticeOnCashier.getEntry().getQuantity());

            PurchaseResponse purchaseResponse = cashierChannel.receive().readPayload(gson, PurchaseResponse.class);
            assertTrue(purchaseResponse.isSuccess(), "purchase failed: " + purchaseResponse.getErrorMessage());
            assertEquals(8, purchaseResponse.getNewQuantity());

            InventoryUpdateNotice inventoryNoticeOnSeller =
                    sellerChannel.receive().readPayload(gson, InventoryUpdateNotice.class);
            assertEquals(BRANCH_ID, inventoryNoticeOnSeller.getBranchId());
            assertEquals("SKU-1", inventoryNoticeOnSeller.getEntry().getSku());
            assertEquals(8, inventoryNoticeOnSeller.getEntry().getQuantity());
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
