package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.Branch;
import managestore.common.model.Employee;
import managestore.common.model.NewCustomer;
import managestore.common.model.Product;
import managestore.common.model.PurchaseResult;
import managestore.common.model.Role;
import managestore.common.model.SalesRecord;
import managestore.common.model.StoreChain;
import managestore.common.protocol.LoginRequest;
import managestore.common.protocol.LoginResponse;
import managestore.common.protocol.Message;
import managestore.common.protocol.MessageChannel;
import managestore.common.protocol.MessageType;
import managestore.common.protocol.ReportFormat;
import managestore.common.protocol.ReportLineDto;
import managestore.common.protocol.ReportRequest;
import managestore.common.protocol.ReportResponse;
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
 * A non-admin who asks for a branch-scoped sales report can only see their own branch's
 * numbers, even if they ask for a different one by name. An admin has no such restriction.
 */
class ReportPrivacyIntegrationTest {

    private final Gson gson = new Gson();

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().logout("branchSeller");
        SessionManager.getInstance().logout("reportAdmin");
    }

    @Test
    void nonAdminAskingForAnotherBranchsReportGetsTheirOwnBranchInstead() throws Exception {
        StoreChain storeChain = new StoreChain();
        Branch home = new Branch("B1", "Home Branch");
        Branch other = new Branch("B2", "Other Branch");
        storeChain.addBranch(home);
        storeChain.addBranch(other);
        Product shirt = new Product("SKU-1", "Shirt", "Tops", 100.0);

        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("SELLER1", "Home Seller", "204812077", "050-1", "ACC-1", "B1", Role.SELLER),
                "branchSeller", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        // One sale at each branch, so a leak would be obvious: if B1's seller could see B2's
        // report, they would see this $500 sale that has nothing to do with their own branch.
        context.getSalesRecordRepository().add(new SalesRecord("B1",
                new PurchaseResult(new NewCustomer("111111111", "Home Customer", "050-1"), shirt, 1, 100.0, 100.0)));
        context.getSalesRecordRepository().add(new SalesRecord("B2",
                new PurchaseResult(new NewCustomer("222222222", "Other Customer", "050-2"), shirt, 5, 500.0, 500.0)));

        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (MessageChannel seller = loginAs(port, "branchSeller")) {
            // Explicitly asks for B2 -- the branch they do NOT belong to.
            seller.send(Message.of(gson, MessageType.REPORT_REQUEST,
                    new ReportRequest(ReportScope.BRANCH, "B2", ReportFormat.JSON)));
            ReportResponse response = seller.receive().readPayload(gson, ReportResponse.class);

            assertEquals(1, response.getLines().size(), "should get exactly one branch's line, not B2's");
            ReportLineDto line = response.getLines().get(0);
            // The report line is labeled with the branch's display name, not its id.
            assertEquals("Home Branch", line.getLabel(), "a non-admin's branch report must show their own branch, not the one they asked for");
            assertEquals(100.0, line.getRevenue(), 0.01, "must show B1's revenue, not B2's $500 sale");
        } finally {
            serverSocket.close();
            clientPool.shutdownNow();
        }
    }

    @Test
    void adminCanStillSeeAnyBranchsReport() throws Exception {
        StoreChain storeChain = new StoreChain();
        Branch home = new Branch("B1", "Home Branch");
        Branch other = new Branch("B2", "Other Branch");
        storeChain.addBranch(home);
        storeChain.addBranch(other);
        Product shirt = new Product("SKU-1", "Shirt", "Tops", 100.0);

        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("ADMIN1", "The Boss", "1", "050-1", "ACC-1", null, Role.ADMIN), "reportAdmin", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        context.getSalesRecordRepository().add(new SalesRecord("B2",
                new PurchaseResult(new NewCustomer("222222222", "Other Customer", "050-2"), shirt, 5, 500.0, 500.0)));

        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (MessageChannel admin = loginAs(port, "reportAdmin")) {
            admin.send(Message.of(gson, MessageType.REPORT_REQUEST,
                    new ReportRequest(ReportScope.BRANCH, "B2", ReportFormat.JSON)));
            ReportResponse response = admin.receive().readPayload(gson, ReportResponse.class);

            assertEquals(1, response.getLines().size());
            assertEquals("Other Branch", response.getLines().get(0).getLabel(), "an admin's own filter choice must not be overridden");
            assertEquals(500.0, response.getLines().get(0).getRevenue(), 0.01);
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
