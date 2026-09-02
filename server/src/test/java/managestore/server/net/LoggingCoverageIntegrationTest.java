package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.Branch;
import managestore.common.model.Employee;
import managestore.common.model.Product;
import managestore.common.model.Role;
import managestore.common.model.StoreChain;
import managestore.common.protocol.ChatEndNotice;
import managestore.common.protocol.ChatMessageDto;
import managestore.common.protocol.ChatRequestDto;
import managestore.common.protocol.ChatStartedNotice;
import managestore.common.protocol.CustomerAddRequest;
import managestore.common.protocol.LogEventDto;
import managestore.common.protocol.LogListRequest;
import managestore.common.protocol.LogListResponse;
import managestore.common.protocol.LoginRequest;
import managestore.common.protocol.LoginResponse;
import managestore.common.protocol.Message;
import managestore.common.protocol.MessageChannel;
import managestore.common.protocol.MessageType;
import managestore.common.protocol.PurchaseRequest;
import managestore.server.service.AuthService;
import managestore.server.service.InMemoryAccountRepository;
import managestore.server.service.InMemoryEmployeeRepository;
import managestore.server.service.LogManager;
import managestore.server.service.SessionManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The brief's system-log requirement (10.a-d) lists four action types the
 * log must cover. {@code EmployeeAndLogIntegrationTest} already proves
 * EMPLOYEE_REGISTERED (10.a) end-to-end and {@code RestockIntegrationTest}
 * proves the PURCHASE half of 10.c; this test closes the remaining gaps by
 * driving CUSTOMER_REGISTERED (10.b), the SALE half of 10.c, and CHAT (10.d)
 * — including that ending a chat saves the actual message text, not just
 * "a chat happened" — through the same real-socket path a live client uses,
 * not just LogManager in isolation (see LogManagerTest for that unit level).
 */
class LoggingCoverageIntegrationTest {

    private final Gson gson = new Gson();

    @AfterEach
    void tearDown() {
        SessionManager.getInstance().logout("logCoverageAdmin");
        SessionManager.getInstance().logout("logCoverageSeller");
        LogManager.getInstance().clear();
    }

    @Test
    void customerRegistrationSaleAndChatTranscriptAreAllLogged() throws Exception {
        LogManager.getInstance().clear();
        StoreChain storeChain = new StoreChain();
        Branch branch = new Branch("B1", "Downtown");
        Product shirt = new Product("SKU-1", "Shirt", "Tops", 100.0);
        branch.getInventory().addStock(shirt, 10);
        storeChain.addBranch(branch);
        storeChain.addProduct(shirt);

        InMemoryAccountRepository accountRepository = new InMemoryAccountRepository();
        InMemoryEmployeeRepository employeeRepository = new InMemoryEmployeeRepository();
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        authService.createAccount(
                new Employee("ADMIN1", "The Boss", "1", "050-1", "ACC-1", "B1", Role.ADMIN),
                "logCoverageAdmin", "secret123");
        authService.createAccount(
                new Employee("E2", "Seller B", "2", "050-2", "ACC-2", "B1", Role.SELLER),
                "logCoverageSeller", "secret123");

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, gson);
        ServerSocket serverSocket = ServerMain.bind(0);
        int port = serverSocket.getLocalPort();
        ExecutorService clientPool = Executors.newCachedThreadPool();
        Thread serverThread = new Thread(() -> ServerMain.acceptLoop(serverSocket, context, clientPool));
        serverThread.setDaemon(true);
        serverThread.start();

        try (MessageChannel adminChannel = loginAs(port, "logCoverageAdmin");
             MessageChannel sellerChannel = loginAs(port, "logCoverageSeller")) {

            // 10.b — registering a customer through the real CUSTOMER_ADD_REQUEST path.
            adminChannel.send(Message.of(gson, MessageType.CUSTOMER_ADD_REQUEST,
                    new CustomerAddRequest("456073923", "Noa Levi", "050-1234567", "VIP")));
            // CustomerDirectory is network-wide, so both connected clients get the live broadcast;
            // drain it from both before moving on so it doesn't race with what's read next. The
            // requester (admin) also gets its own direct CUSTOMER_ADD_RESPONSE, sent after that
            // broadcast — drain that too.
            adminChannel.receive(); // CUSTOMER_UPDATE_BROADCAST
            sellerChannel.receive();
            adminChannel.receive(); // CUSTOMER_ADD_RESPONSE

            // 10.c (sale half) — an actual purchase through PURCHASE_REQUEST. The seller is on the
            // same branch (B1), so it gets its own INVENTORY_UPDATE push too — drain it from both
            // sides or it desyncs the chat exchange read below.
            adminChannel.send(Message.of(gson, MessageType.PURCHASE_REQUEST, new PurchaseRequest("SKU-1", 2, "456073923")));
            adminChannel.receive(); // INVENTORY_UPDATE push, not the response we're checking here
            adminChannel.receive(); // PURCHASE_RESPONSE
            sellerChannel.receive(); // INVENTORY_UPDATE push on the other branch employee

            // 10.d — a real chat: request, exchange one message, then end it. The brief's "option to
            // save the chat content" means the saved log entry should contain the actual text sent.
            adminChannel.send(Message.of(gson, MessageType.CHAT_REQUEST, new ChatRequestDto("B1")));
            ChatStartedNotice startedOnAdmin = adminChannel.receive().readPayload(gson, ChatStartedNotice.class);
            sellerChannel.receive(); // ChatStartedNotice on the other side

            adminChannel.send(Message.of(gson, MessageType.CHAT_MESSAGE,
                    new ChatMessageDto(startedOnAdmin.getSessionId(), "ADMIN1", "let's restock jackets tomorrow")));
            sellerChannel.receive(); // the message arriving on the other side

            adminChannel.send(Message.of(gson, MessageType.CHAT_END, new ChatEndNotice(startedOnAdmin.getSessionId())));
            adminChannel.receive(); // CHAT_END notice on the sender's own side
            sellerChannel.receive(); // CHAT_END notice on the other participant

            adminChannel.send(Message.of(gson, MessageType.LOG_LIST_REQUEST, new LogListRequest(null)));
            LogListResponse logResponse = adminChannel.receive().readPayload(gson, LogListResponse.class);
            List<LogEventDto> events = logResponse.getEvents();

            assertTrue(events.stream().anyMatch(evt -> evt.getType().equals("CUSTOMER_REGISTERED")
                            && evt.getDetails().contains("456073923")),
                    "expected a CUSTOMER_REGISTERED entry for the customer just added");

            assertTrue(events.stream().anyMatch(evt -> evt.getType().equals("SALE") && evt.getDetails().contains("SKU-1")),
                    "expected a SALE entry for the purchase just made");

            LogEventDto chatEvent = events.stream().filter(evt -> evt.getType().equals("CHAT")).findFirst()
                    .orElseThrow(() -> new AssertionError("expected a CHAT entry for the session that just ended"));
            assertTrue(chatEvent.getDetails().contains("let's restock jackets tomorrow"),
                    "the CHAT log entry should save the actual transcript, not just note that a chat happened: " + chatEvent.getDetails());
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
