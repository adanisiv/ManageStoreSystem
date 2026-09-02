package managestore.server.net;

import managestore.common.model.Branch;
import managestore.common.model.Customer;
import managestore.common.model.CustomerDirectoryObserver;
import managestore.common.model.CustomerFactory;
import managestore.common.model.CustomerType;
import managestore.common.model.Employee;
import managestore.common.model.InventoryObserver;
import managestore.common.model.LogEvent;
import managestore.common.model.LogType;
import managestore.common.model.Product;
import managestore.common.model.PurchaseResult;
import managestore.common.model.Role;
import managestore.common.model.SalesRecord;
import managestore.common.protocol.BranchDto;
import managestore.common.protocol.BranchListResponse;
import managestore.common.protocol.ChatJoinRequest;
import managestore.common.protocol.ChatMessageDto;
import managestore.common.protocol.ChatRequestDto;
import managestore.common.protocol.CustomerAddRequest;
import managestore.common.protocol.CustomerDto;
import managestore.common.protocol.CustomerListResponse;
import managestore.common.protocol.CustomerUpdateNotice;
import managestore.common.protocol.EmployeeAddRequest;
import managestore.common.protocol.EmployeeAddResponse;
import managestore.common.protocol.EmployeeListResponse;
import managestore.common.protocol.ErrorMessage;
import managestore.common.protocol.InventorySnapshotResponse;
import managestore.common.protocol.InventoryUpdateNotice;
import managestore.common.protocol.LogEventDto;
import managestore.common.protocol.LogListRequest;
import managestore.common.protocol.LogListResponse;
import managestore.common.protocol.LoginRequest;
import managestore.common.protocol.LoginResponse;
import managestore.common.protocol.Message;
import managestore.common.protocol.MessageChannel;
import managestore.common.protocol.MessageType;
import managestore.common.protocol.PurchaseRequest;
import managestore.common.protocol.PurchaseResponse;
import managestore.common.protocol.ReportRequest;
import managestore.common.protocol.ReportResponse;
import managestore.common.protocol.RestockRequest;
import managestore.common.protocol.RestockResponse;
import managestore.common.protocol.StockEntry;
import managestore.server.service.ChatEndpoint;
import managestore.server.service.LogManager;
import managestore.server.service.PersonalIdValidator;
import managestore.server.service.PhoneValidator;
import managestore.server.service.SessionManager;

import java.io.IOException;
import java.net.Socket;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
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
                dispatchSafely(message);
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Connection closed: " + e.getMessage());
        } finally {
            cleanupOnDisconnect();
        }
    }

    /**
     * Runs {@link #dispatch} guarded against any unexpected {@link RuntimeException}
     * (a malformed payload, an invalid enum value in a request, a bug in a handler)
     * so that one bad message reports an error back to this client instead of
     * silently killing their whole session — every other connected client is
     * completely unaffected either way, since each has its own thread, but a
     * client shouldn't lose their session over one bad request.
     */
    private void dispatchSafely(Message message) {
        try {
            dispatch(message);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error handling " + message.getType() + " from " + socket.getRemoteSocketAddress(), e);
            sendError("Request failed: " + e.getMessage());
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
            case RESTOCK_REQUEST:
                handleRestockRequest(message);
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
            case REPORT_REQUEST:
                handleReportRequest(message);
                break;
            case EMPLOYEE_LIST_REQUEST:
                handleEmployeeListRequest();
                break;
            case BRANCH_LIST_REQUEST:
                handleBranchListRequest();
                break;
            case EMPLOYEE_ADD_REQUEST:
                handleEmployeeAddRequest(message);
                break;
            case LOG_LIST_REQUEST:
                handleLogListRequest(message);
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
            context.getSalesRecordRepository().add(new SalesRecord(subscribedBranch.getId(), result));
            LogManager.getInstance().log(new LogEvent(LogType.SALE, loggedInEmployee.getEmployeeNumber(),
                    "Sold " + request.getQuantity() + "x " + product.getSku() + " to " + customer.getPersonalId()
                            + " for " + result.getAmountCharged()));
            int newQuantity = subscribedBranch.getInventory().getQuantity(product);
            channel.send(Message.of(context.getGson(), MessageType.PURCHASE_RESPONSE,
                    PurchaseResponse.success(result.getListTotal(), result.getAmountCharged(), newQuantity)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            channel.send(Message.of(context.getGson(), MessageType.PURCHASE_RESPONSE,
                    PurchaseResponse.failure(e.getMessage())));
        }
    }

    /** The brief's "purchase" side of the Inventory interface: adds stock, as opposed to selling it. */
    private void handleRestockRequest(Message message) {
        if (!requireLoginAndBranch()) {
            return;
        }
        RestockRequest request = message.readPayload(context.getGson(), RestockRequest.class);
        Product product = context.getStoreChain().getProduct(request.getSku());
        if (product == null) {
            channel.send(Message.of(context.getGson(), MessageType.RESTOCK_RESPONSE,
                    RestockResponse.failure("Unknown product: " + request.getSku())));
            return;
        }
        try {
            int newQuantity = context.getPurchaseService().restock(subscribedBranch, product, request.getQuantity());
            LogManager.getInstance().log(new LogEvent(LogType.PURCHASE, loggedInEmployee.getEmployeeNumber(),
                    "Restocked " + request.getQuantity() + "x " + product.getSku() + " at " + subscribedBranch.getId()
                            + " (new quantity " + newQuantity + ")"));
            channel.send(Message.of(context.getGson(), MessageType.RESTOCK_RESPONSE, RestockResponse.success(newQuantity)));
        } catch (IllegalArgumentException e) {
            channel.send(Message.of(context.getGson(), MessageType.RESTOCK_RESPONSE, RestockResponse.failure(e.getMessage())));
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
            requireValid(request.getFullName(), "Full name");
            requireValidPersonalId(request.getPersonalId());
            requireValidPhone(request.getPhone());
            CustomerType type = CustomerType.valueOf(request.getCustomerType());
            Customer customer = CustomerFactory.create(type, request.getPersonalId(), request.getFullName(), request.getPhone());
            context.getStoreChain().getCustomerDirectory().add(customer);
            LogManager.getInstance().log(new LogEvent(LogType.CUSTOMER_REGISTERED, loggedInEmployee.getEmployeeNumber(),
                    "Registered " + type + " customer " + customer.getPersonalId() + " (" + customer.getFullName() + ")"));
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

    /** Lets a client offer a branch picker instead of requiring the user to know/type an exact branch id. */
    private void handleBranchListRequest() {
        if (!requireLogin()) {
            return;
        }
        List<BranchDto> dtos = new ArrayList<>();
        for (Branch branch : context.getStoreChain().allBranches()) {
            dtos.add(BranchDto.from(branch));
        }
        channel.send(Message.of(context.getGson(), MessageType.BRANCH_LIST_RESPONSE, new BranchListResponse(dtos)));
    }

    // ---- employees ----------------------------------------------------------

    private void handleEmployeeListRequest() {
        if (!requireLogin()) {
            return;
        }
        channel.send(Message.of(context.getGson(), MessageType.EMPLOYEE_LIST_RESPONSE,
                new EmployeeListResponse(context.getEmployeeRepository().findAll())));
    }

    /** Admin-only: matches the brief's "Admin screen defines employee accounts". */
    private void handleEmployeeAddRequest(Message message) {
        if (!requireLogin()) {
            return;
        }
        if (loggedInEmployee.getRole() != Role.ADMIN) {
            channel.send(Message.of(context.getGson(), MessageType.EMPLOYEE_ADD_RESPONSE,
                    EmployeeAddResponse.failure("Only an admin can add employees")));
            return;
        }
        EmployeeAddRequest request = message.readPayload(context.getGson(), EmployeeAddRequest.class);
        try {
            requireValid(request.getEmployeeNumber(), "Employee #");
            requireValid(request.getFullName(), "Full name");
            requireValidPersonalId(request.getPersonalId());
            requireValidPhone(request.getPhone());
            requireValid(request.getAccountNumber(), "Account #");
            requireValid(request.getBranchId(), "Branch");
            requireValid(request.getUsername(), "Username");
            // AuthService.createAccount already rejects a taken *username*, but nothing was
            // checking the employee number itself — re-adding an existing one would silently
            // overwrite that employee's profile (JsonFileEmployeeRepository.save is a keyed
            // upsert), while a second, unrelated account could still end up pointing at it.
            if (context.getEmployeeRepository().findByEmployeeNumber(request.getEmployeeNumber()).isPresent()) {
                throw new IllegalArgumentException("Employee number already exists: " + request.getEmployeeNumber());
            }
            Role role = Role.valueOf(request.getRole());
            Employee employee = new Employee(request.getEmployeeNumber(), request.getFullName(), request.getPersonalId(),
                    request.getPhone(), request.getAccountNumber(), request.getBranchId(), role);
            context.getAuthService().createAccount(employee, request.getUsername(), request.getPassword());

            Branch branch = context.getStoreChain().getBranch(request.getBranchId());
            if (branch != null) {
                branch.addEmployee(employee);
            }
            LogManager.getInstance().log(new LogEvent(LogType.EMPLOYEE_REGISTERED, loggedInEmployee.getEmployeeNumber(),
                    "Registered employee " + employee.getEmployeeNumber() + " (" + employee.getFullName() + ", " + role + ")"));
            channel.send(Message.of(context.getGson(), MessageType.EMPLOYEE_ADD_RESPONSE, EmployeeAddResponse.success()));
        } catch (IllegalArgumentException e) {
            channel.send(Message.of(context.getGson(), MessageType.EMPLOYEE_ADD_RESPONSE,
                    EmployeeAddResponse.failure(e.getMessage())));
        }
    }

    // ---- logs ----------------------------------------------------------

    /** Admin-only: logs can contain chat transcripts and other sensitive detail. */
    private void handleLogListRequest(Message message) {
        if (!requireLogin()) {
            return;
        }
        if (loggedInEmployee.getRole() != Role.ADMIN) {
            sendError("Only an admin can view the system log");
            return;
        }
        LogListRequest request = message.readPayload(context.getGson(), LogListRequest.class);
        List<LogEvent> events;
        if (request.getTypeFilter() == null) {
            events = LogManager.getInstance().all();
        } else {
            try {
                events = LogManager.getInstance().byType(LogType.valueOf(request.getTypeFilter()));
            } catch (IllegalArgumentException e) {
                sendError("Unknown log type: " + request.getTypeFilter());
                return;
            }
        }

        List<LogEventDto> dtos = new ArrayList<>();
        for (LogEvent event : events) {
            dtos.add(new LogEventDto(event.getType().name(), event.getActor(), event.getDetails(),
                    event.getTimestamp().toEpochMilli()));
        }
        channel.send(Message.of(context.getGson(), MessageType.LOG_LIST_RESPONSE, new LogListResponse(dtos)));
    }

    // ---- reports ----------------------------------------------------------

    private void handleReportRequest(Message message) {
        if (!requireLogin()) {
            return;
        }
        ReportRequest request = message.readPayload(context.getGson(), ReportRequest.class);
        LocalDate day;
        try {
            day = request.getDay() != null ? LocalDate.parse(request.getDay()) : null;
        } catch (DateTimeParseException e) {
            sendError("Invalid report date: " + request.getDay());
            return;
        }
        ReportResponse response = context.getReportService().generate(context.getSalesRecordRepository().all(),
                request.getScope(), request.getFilterValue(), request.getFormat(), day);
        channel.send(Message.of(context.getGson(), MessageType.REPORT_RESPONSE, response));
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
        boolean joined = context.getChatMediator().joinChat(loggedInEmployee.getEmployeeNumber(), request.getTargetEmployeeNumber());
        if (!joined) {
            sendError("Could not join: that employee isn't in an active chat right now, "
                    + "or you're already in a different one — end it first.");
        }
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

    /** Input validation for the Employee/Customer add-request handlers, thrown as {@link IllegalArgumentException}
     *  so it flows into the same catch block each handler already has for reporting a rejected request. */
    private void requireValid(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private void requireValidPersonalId(String personalId) {
        String reason = PersonalIdValidator.validate(personalId);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
    }

    private void requireValidPhone(String phone) {
        String reason = PhoneValidator.validate(phone);
        if (reason != null) {
            throw new IllegalArgumentException(reason);
        }
    }
}
