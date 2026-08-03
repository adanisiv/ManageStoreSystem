package managestore.server.net;

import managestore.common.model.Branch;
import managestore.common.model.Customer;
import managestore.common.model.CustomerDirectoryObserver;
import managestore.common.model.CustomerFactory;
import managestore.common.model.CustomerType;
import managestore.common.model.Employee;
import managestore.common.model.InventoryObserver;
import managestore.common.model.Product;
import managestore.common.model.PurchaseResult;
import managestore.common.model.Role;
import managestore.common.protocol.ChatJoinRequest;
import managestore.common.protocol.ChatMessageDto;
import managestore.common.protocol.ChatRequestDto;
import managestore.common.protocol.CustomerAddRequest;
import managestore.common.protocol.CustomerDto;
import managestore.common.protocol.CustomerListResponse;
import managestore.common.protocol.CustomerUpdateNotice;
import managestore.common.protocol.ErrorMessage;
import managestore.common.protocol.InventorySnapshotResponse;
import managestore.common.protocol.InventoryUpdateNotice;
import managestore.common.protocol.LoginRequest;
import managestore.common.protocol.LoginResponse;
import managestore.common.protocol.Message;
import managestore.common.protocol.MessageChannel;
import managestore.common.protocol.MessageType;
import managestore.common.protocol.PurchaseRequest;
import managestore.common.protocol.PurchaseResponse;
import managestore.common.protocol.StockEntry;
import managestore.server.service.ChatEndpoint;
import managestore.server.service.SessionManager;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One thread per connected client. Reads {@link Message}s from its socket in
 * a loop and dispatches by {@link MessageType}.
 *
 * <p>On successful login this handler registers itself as an
 * {@link InventoryObserver} (on its own branch's inventory) and a
 * {@link CustomerDirectoryObserver} (network-wide) — the Observer pattern's
 * concrete wiring: every other connected client's ClientHandler is also
 * registered the same way, so one client's change is pushed to all of them
 * automatically the moment it happens, with no polling.
 */
public class ClientHandler implements Runnable, ChatEndpoint {

    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());

    private final Socket socket;
    private final ServerContext context;
    private final String sessionId = UUID.randomUUID().toString();

    private volatile String loggedInUsername;
    private volatile Employee loggedInEmployee;
    private MessageChannel channel;

    private InventoryObserver inventoryObserver;
    private Branch subscribedBranch;
    private CustomerDirectoryObserver customerDirectoryObserver;

    public ClientHandler(Socket socket, ServerContext context) {
        this.socket = socket;
        this.context = context;
    }

    public Employee getLoggedInEmployee() {
        return loggedInEmployee;
    }

    @Override
    public void run() {
        try (MessageChannel opened = new MessageChannel(socket, context.getGson())) {
            this.channel = opened;
            Message message;
            while ((message = opened.receive()) != null) {
                dispatch(message);
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Connection closed: " + e.getMessage());
        } finally {
            cleanupOnDisconnect();
        }
    }

    private void dispatch(Message message) {
        switch (message.getType()) {
            case LOGIN_REQUEST:
                handleLogin(message);
                break;
            case LOGOUT:
                cleanupOnDisconnect();
                break;
            case INVENTORY_SNAPSHOT_REQUEST:
                handleInventorySnapshotRequest();
                break;
            case PURCHASE_REQUEST:
                handlePurchaseRequest(message);
                break;
            case CUSTOMER_LIST_REQUEST:
                handleCustomerListRequest();
                break;
            case CUSTOMER_ADD_REQUEST:
                handleCustomerAddRequest(message);
                break;
            case CHAT_REQUEST:
                handleChatRequest(message);
                break;
            case CHAT_MESSAGE:
                handleChatMessage(message);
                break;
            case CHAT_END:
                handleChatEnd();
                break;
            case CHAT_JOIN_REQUEST:
                handleChatJoinRequest(message);
                break;
            default:
                sendError("Unhandled message type: " + message.getType());
        }
    }

    // ---- login / logout -------------------------------------------------

    private void handleLogin(Message message) {
        LoginRequest request = message.readPayload(context.getGson(), LoginRequest.class);
        LoginResponse response = context.getAuthService().login(request.getUsername(), request.getPassword());

        if (response.isSuccess()) {
            boolean sessionAcquired = SessionManager.getInstance().tryLogin(request.getUsername(), sessionId);
            if (!sessionAcquired) {
                response = LoginResponse.failure("This user is already logged in on another computer");
            } else {
                loggedInUsername = request.getUsername();
                loggedInEmployee = response.getEmployee();
                subscribeToLiveUpdates();
            }
        }

        channel.send(Message.of(context.getGson(), MessageType.LOGIN_RESPONSE, response));
    }

    private void subscribeToLiveUpdates() {
        String branchId = loggedInEmployee.getBranchId();
        if (branchId != null) {
            subscribedBranch = context.getStoreChain().getBranch(branchId);
            if (subscribedBranch != null) {
                inventoryObserver = (product, newQuantity) -> pushInventoryUpdate(branchId, product, newQuantity);
                subscribedBranch.getInventory().addObserver(inventoryObserver);
            }
        }
        customerDirectoryObserver = new CustomerDirectoryObserver() {
            @Override
            public void onCustomerAdded(Customer customer) {
                pushCustomerUpdate(customer, true);
            }

            @Override
            public void onCustomerUpdated(Customer customer) {
                pushCustomerUpdate(customer, false);
            }
        };
        context.getStoreChain().getCustomerDirectory().addObserver(customerDirectoryObserver);
        context.getChatMediator().register(loggedInEmployee, this);
    }

    private void cleanupOnDisconnect() {
        if (loggedInEmployee != null) {
            context.getChatMediator().unregister(loggedInEmployee.getEmployeeNumber());
        }
        if (loggedInUsername != null) {
            SessionManager.getInstance().logout(loggedInUsername);
            loggedInUsername = null;
            loggedInEmployee = null;
        }
        if (subscribedBranch != null && inventoryObserver != null) {
            subscribedBranch.getInventory().removeObserver(inventoryObserver);
            subscribedBranch = null;
            inventoryObserver = null;
        }
        if (customerDirectoryObserver != null) {
            context.getStoreChain().getCustomerDirectory().removeObserver(customerDirectoryObserver);
            customerDirectoryObserver = null;
        }
    }

    // ---- inventory --------------------------------------------------------

    private void handleInventorySnapshotRequest() {
        if (!requireLoginAndBranch()) {
            return;
        }
        Map<Product, Integer> snapshot = subscribedBranch.getInventory().snapshot();
        List<StockEntry> items = new ArrayList<>();
        for (Map.Entry<Product, Integer> entry : snapshot.entrySet()) {
            Product p = entry.getKey();
            items.add(new StockEntry(p.getSku(), p.getName(), p.getCategory(), p.getPrice(), entry.getValue()));
        }
        channel.send(Message.of(context.getGson(), MessageType.INVENTORY_SNAPSHOT_RESPONSE,
                new InventorySnapshotResponse(subscribedBranch.getId(), items)));
    }

    private void pushInventoryUpdate(String branchId, Product product, int newQuantity) {
        if (channel == null) {
            return;
        }
        StockEntry entry = new StockEntry(product.getSku(), product.getName(), product.getCategory(),
                product.getPrice(), newQuantity);
        channel.send(Message.of(context.getGson(), MessageType.INVENTORY_UPDATE,
                new InventoryUpdateNotice(branchId, entry)));
    }

    // ---- purchases ----------------------------------------------------------

    private void handlePurchaseRequest(Message message) {
        if (!requireLoginAndBranch()) {
            return;
        }
        PurchaseRequest request = message.readPayload(context.getGson(), PurchaseRequest.class);
        Product product = context.getStoreChain().getProduct(request.getSku());
        Customer customer = context.getStoreChain().getCustomerDirectory().get(request.getCustomerPersonalId());

        if (product == null) {
            channel.send(Message.of(context.getGson(), MessageType.PURCHASE_RESPONSE,
                    PurchaseResponse.failure("Unknown product: " + request.getSku())));
            return;
        }
        if (customer == null) {
            channel.send(Message.of(context.getGson(), MessageType.PURCHASE_RESPONSE,
                    PurchaseResponse.failure("Unknown customer: " + request.getCustomerPersonalId())));
            return;
        }

        try {
            PurchaseResult result = context.getPurchaseService()
                    .purchase(subscribedBranch, product, request.getQuantity(), customer);
            int newQuantity = subscribedBranch.getInventory().getQuantity(product);
            channel.send(Message.of(context.getGson(), MessageType.PURCHASE_RESPONSE,
                    PurchaseResponse.success(result.getListTotal(), result.getAmountCharged(), newQuantity)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            channel.send(Message.of(context.getGson(), MessageType.PURCHASE_RESPONSE,
                    PurchaseResponse.failure(e.getMessage())));
        }
    }

    // ---- customers ----------------------------------------------------------

    private void handleCustomerListRequest() {
        if (!requireLogin()) {
            return;
        }
        List<CustomerDto> dtos = new ArrayList<>();
        for (Customer customer : context.getStoreChain().getCustomerDirectory().all()) {
            dtos.add(CustomerDto.from(customer));
        }
        channel.send(Message.of(context.getGson(), MessageType.CUSTOMER_LIST_RESPONSE, new CustomerListResponse(dtos)));
    }

    private void handleCustomerAddRequest(Message message) {
        if (!requireLogin()) {
            return;
        }
        CustomerAddRequest request = message.readPayload(context.getGson(), CustomerAddRequest.class);
        try {
            CustomerType type = CustomerType.valueOf(request.getCustomerType());
            Customer customer = CustomerFactory.create(type, request.getPersonalId(), request.getFullName(), request.getPhone());
            context.getStoreChain().getCustomerDirectory().add(customer);
        } catch (IllegalArgumentException e) {
            sendError("Could not add customer: " + e.getMessage());
        }
    }

    private void pushCustomerUpdate(Customer customer, boolean newlyAdded) {
        if (channel == null) {
            return;
        }
        channel.send(Message.of(context.getGson(), MessageType.CUSTOMER_UPDATE_BROADCAST,
                new CustomerUpdateNotice(CustomerDto.from(customer), newlyAdded)));
    }

    // ---- chat ----------------------------------------------------------

    private void handleChatRequest(Message message) {
        if (!requireLogin()) {
            return;
        }
        ChatRequestDto request = message.readPayload(context.getGson(), ChatRequestDto.class);
        String myNumber = loggedInEmployee.getEmployeeNumber();
        if (request.getTargetEmployeeNumber() != null) {
            context.getChatMediator().requestDirectChat(myNumber, request.getTargetEmployeeNumber());
        } else {
            context.getChatMediator().requestChat(myNumber, request.getTargetBranchId());
        }
    }

    private void handleChatMessage(Message message) {
        if (!requireLogin()) {
            return;
        }
        ChatMessageDto request = message.readPayload(context.getGson(), ChatMessageDto.class);
        context.getChatMediator().sendMessage(loggedInEmployee.getEmployeeNumber(), request.getText());
    }

    private void handleChatEnd() {
        if (!requireLogin()) {
            return;
        }
        context.getChatMediator().endChat(loggedInEmployee.getEmployeeNumber());
    }

    private void handleChatJoinRequest(Message message) {
        if (!requireLogin()) {
            return;
        }
        if (loggedInEmployee.getRole() != Role.SHIFT_MANAGER) {
            sendError("Only a shift manager can join an existing chat");
            return;
        }
        ChatJoinRequest request = message.readPayload(context.getGson(), ChatJoinRequest.class);
        context.getChatMediator().joinChat(loggedInEmployee.getEmployeeNumber(), request.getTargetEmployeeNumber());
    }

    /** {@link ChatEndpoint} implementation: how ChatMediator pushes chat messages to this specific client. */
    @Override
    public void send(MessageType type, Object payload) {
        if (channel != null) {
            channel.send(Message.of(context.getGson(), type, payload));
        }
    }

    // ---- helpers ----------------------------------------------------------

    private boolean requireLogin() {
        if (loggedInEmployee == null) {
            sendError("Not logged in");
            return false;
        }
        return true;
    }

    private boolean requireLoginAndBranch() {
        if (!requireLogin()) {
            return false;
        }
        if (subscribedBranch == null) {
            sendError("Employee is not assigned to a branch");
            return false;
        }
        return true;
    }

    private void sendError(String message) {
        channel.send(Message.of(context.getGson(), MessageType.ERROR, new ErrorMessage(message)));
    }
}
