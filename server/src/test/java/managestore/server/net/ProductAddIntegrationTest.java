package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.Branch;
import managestore.common.model.Employee;
import managestore.common.model.Product;
import managestore.common.model.Role;
import managestore.common.model.StoreChain;
import managestore.common.protocol.InventoryUpdateNotice;
import managestore.common.protocol.LoginRequest;
import managestore.common.protocol.LoginResponse;
import managestore.common.protocol.Message;
import managestore.common.protocol.MessageChannel;
import managestore.common.protocol.MessageType;
import managestore.common.protocol.ProductAddRequest;
import managestore.common.protocol.ProductAddResponse;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Adding a product is the only way a new SKU enters the catalog while the server is running:
 * restocking can only top up a SKU the catalog already has, and before this the catalog was
 * fixed at whatever the server was seeded with at startup.
 *
 * <p>These cover the three things that decide whether a SKU is safe to accept — the role
 * allowed to add one, a SKU already in use, and an opening quantity that isn't a real amount.
 */
class ProductAddIntegrationTest {

    private static final String BRANCH_ID = "B1";
    private final Gson gson = new Gson();

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().logout("productManager");
        SessionManager.getInstance().logout("productSeller");
        LogManager.getInstance().clear();
    }

    @Test
    void shiftManagerAddsANewProductWhichAppearsLiveForEveryoneAtTheBranch() throws Exception {
        LogManager.getInstance().clear();
        Fixture fixture = new Fixture();
        try (MessageChannel managerChannel = fixture.loginAs("productManager");
             MessageChannel sellerChannel = fixture.loginAs("productSeller")) {

            managerChannel.send(Message.of(gson, MessageType.PRODUCT_ADD_REQUEST,
                    new ProductAddRequest("SKU-NEW", "Rain Jacket", "Outerwear", 249.90, 7)));

            // The new row reaches other employees through the same Observer push a restock uses,
            // so the seller sees it without asking for anything.
            InventoryUpdateNotice noticeOnSeller = sellerChannel.receive().readPayload(gson, InventoryUpdateNotice.class);
            assertEquals("SKU-NEW", noticeOnSeller.getEntry().getSku());
            assertEquals(7, noticeOnSeller.getEntry().getQuantity());
            assertEquals("Rain Jacket", noticeOnSeller.getEntry().getName());

            // The manager gets that same push (they observe the branch too), then the response.
            managerChannel.receive();
            ProductAddResponse response = managerChannel.receive().readPayload(gson, ProductAddResponse.class);
            assertTrue(response.isSuccess(), "add failed: " + response.getErrorMessage());

            // The catalog is chain-wide, so the product is now sellable at any branch, not just this one.
            assertNotNull(fixture.storeChain.getProduct("SKU-NEW"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void aSkuAlreadyInTheCatalogIsRefusedInsteadOfOverwritingIt() throws Exception {
        Fixture fixture = new Fixture();
        try (MessageChannel managerChannel = fixture.loginAs("productManager")) {
            // SKU-1 was seeded as "Shirt" at 100.0 with 5 in stock.
            managerChannel.send(Message.of(gson, MessageType.PRODUCT_ADD_REQUEST,
                    new ProductAddRequest("SKU-1", "Something Else", "Tops", 5.0, 3)));

            ProductAddResponse response = managerChannel.receive().readPayload(gson, ProductAddResponse.class);
            assertFalse(response.isSuccess(), "a duplicate SKU must be refused");

            // The original catalog entry must be untouched: the catalog is keyed by SKU, and
            // every branch's Inventory keys its stock by the Product object itself, so a silent
            // overwrite would leave one SKU reporting two different products depending on which
            // map you read.
            Product existing = fixture.storeChain.getProduct("SKU-1");
            assertEquals("Shirt", existing.getName());
            assertEquals(100.0, existing.getPrice());
        } finally {
            fixture.close();
        }
    }

    @Test
    void anEmployeeWhoIsNotAShiftManagerCannotAddAProduct() throws Exception {
        Fixture fixture = new Fixture();
        try (MessageChannel sellerChannel = fixture.loginAs("productSeller")) {
            sellerChannel.send(Message.of(gson, MessageType.PRODUCT_ADD_REQUEST,
                    new ProductAddRequest("SKU-SELLER", "Sneaky Product", "Tops", 10.0, 1)));

            ProductAddResponse response = sellerChannel.receive().readPayload(gson, ProductAddResponse.class);
            assertFalse(response.isSuccess(), "a SELLER must not be able to add a product");
            // Refused server-side, not just hidden in the UI — nothing entered the catalog.
            assertNull(fixture.storeChain.getProduct("SKU-SELLER"));
        } finally {
            fixture.close();
        }
    }

    @Test
    void anOpeningQuantityOfZeroIsRefusedSoNoProductIsStrandedOutOfEveryBranchList() throws Exception {
        // A product with no stock anywhere appears in no branch's inventory at all (Inventory
        // only holds an entry once stock exists for it), and Restock only offers products
        // already in the branch's list — so a zero-quantity add would strand the SKU where
        // nothing in the UI could ever reach it again.
        Fixture fixture = new Fixture();
        try (MessageChannel managerChannel = fixture.loginAs("productManager")) {
            managerChannel.send(Message.of(gson, MessageType.PRODUCT_ADD_REQUEST,
                    new ProductAddRequest("SKU-ZERO", "Ghost Product", "Tops", 10.0, 0)));

            ProductAddResponse response = managerChannel.receive().readPayload(gson, ProductAddResponse.class);
            assertFalse(response.isSuccess(), "an opening quantity of 0 must be refused");
        } finally {
            fixture.close();
        }
    }

    /** The server, store chain and accounts every test here needs, started and torn down together. */
    private final class Fixture implements AutoCloseable {

        private final StoreChain storeChain = new StoreChain();
        private final ServerSocket serverSocket;
        private final ExecutorService clientPool = Executors.newCachedThreadPool();
        private final int port;

        Fixture() throws Exception {
            Branch branch = new Branch(BRANCH_ID, "Downtown");
            Product shirt = new Product("SKU-1", "Shirt", "Tops", 100.0);
            branch.getInventory().addStock(shirt, 5);
            storeChain.addBranch(branch);
            storeChain.addProduct(shirt);

            InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
            InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
            AuthService authService = new AuthService(accountRepository, employeeRepository);
            authService.createAccount(
                    new Employee("M1", "The Manager", "1", "050-1", "ACC-1", BRANCH_ID, Role.SHIFT_MANAGER),
                    "productManager", "secret123");
            authService.createAccount(
                    new Employee("S1", "The Seller", "2", "050-2", "ACC-2", BRANCH_ID, Role.SELLER),
                    "productSeller", "secret123");

            ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
            serverSocket = ServerMain.bind(0);
            port = serverSocket.getLocalPort();
            Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
            serverThread.setDaemon(true);
            serverThread.start();
        }

        MessageChannel loginAs(String username) throws Exception {
            Socket socket = new Socket("localhost", port);
            MessageChannel channel = new MessageChannel(socket, gson);
            channel.send(Message.of(gson, MessageType.LOGIN_REQUEST, new LoginRequest(username, "secret123")));
            LoginResponse response = channel.receive().readPayload(gson, LoginResponse.class);
            assertTrue(response.isSuccess(), "login should succeed for " + username);
            return channel;
        }

        @Override
        public void close() throws Exception {
            serverSocket.close();
            clientPool.shutdownNow();
        }
    }
}
