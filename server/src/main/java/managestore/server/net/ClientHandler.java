package managestore.server.net;

import managestore.common.exception.DuplicateProductException;
import managestore.common.exception.ValidationException;

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
import managestore.common.protocol.CustomerAddResponse;
import managestore.common.protocol.CustomerDto;
import managestore.common.protocol.CustomerListResponse;
import managestore.common.protocol.CustomerUpdateNotice;
import managestore.common.protocol.EmployeeAddRequest;
import managestore.common.protocol.EmployeeAddResponse;
import managestore.common.protocol.EmployeeDeleteRequest;
import managestore.common.protocol.EmployeeDeleteResponse;
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
import managestore.common.protocol.ProductAddRequest;
import managestore.common.protocol.ProductAddResponse;
import managestore.common.protocol.PurchaseRequest;
import managestore.common.protocol.PurchaseResponse;
import managestore.common.protocol.ReportRequest;
import managestore.common.protocol.ReportScope;
import managestore.common.protocol.ReportResponse;
import managestore.common.protocol.RestockRequest;
import managestore.common.protocol.RestockResponse;
import managestore.common.protocol.StockEntry;
import managestore.server.service.AccountNumberValidator;
import managestore.server.service.ChatEndpoint;
import managestore.server.service.EmployeeNumberValidator;
import managestore.server.service.FullNameValidator;
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
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One thread per connected client. This class reads {@link Message}s from
 * its socket in a loop and dispatches each one by its {@link MessageType}.
 *
 * <p>Once a client logs in successfully, its handler registers itself as an
 * {@link InventoryObserver} (for its own branch's inventory) and as a
 * {@link CustomerDirectoryObserver} (network-wide). This is the Observer
 * pattern in action: every connected client's handler registers the same
 * way, so when one client makes a change, every other client is pushed the
 * update automatically, the moment it happens, with no polling needed.
 */
public class ClientHandler implements Runnable, ChatEndpoint {

    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());

    private final Socket socket;
    private final ServerContext context;
    // This id is unique per connection, not per employee. It identifies this specific
    // socket/session to SessionManager, so the same employee logging in from a second
    // computer gets a different session id, not a reused one.
    private final String sessionId = UUID.randomUUID().toString();

    // These fields are volatile because getLoggedInEmployee() can be read from other threads
    // (for example, another ClientHandler pushing a chat message). Only this handler's own
    // thread ever writes to them.
    private volatile String loggedInUsername;
    private volatile Employee loggedInEmployee;
    private MessageChannel channel;

    // These three are only set once a login has subscribed this handler to live
    // inventory/customer updates. They are kept here so cleanupOnDisconnect() knows what,
    // if anything, needs to be unregistered.
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
        // The try-with-resources block closes the underlying socket and streams as soon as
        // this method returns, no matter how it returns: a normal disconnect or an exception.
        try (MessageChannel opened = new MessageChannel(socket, context.getGson())) {
            this.channel = opened;
            Message message;
            // receive() blocks here until the next message arrives. A null return means the
            // client closed the connection cleanly, which ends this loop.
            while ((message = opened.receive()) != null) {
                dispatchSafely(message);
            }
        } catch (IOException e) {
            // A broken or reset connection also lands here. This is logged at FINE, not a
            // warning, because it's routine — clients disconnect all the time, it's not a bug.
            LOG.log(Level.FINE, "Connection closed: " + e.getMessage());
        } finally {
            // Always run cleanup, whether the loop ended normally or through an exception. This
            // way a dropped connection still unregisters this handler from every observer and
            // chat session it had joined.
            cleanupOnDisconnect();
        }
    }

    /**
     * Runs {@link #dispatch}, but catches any unexpected {@link RuntimeException} first —
     * for example a malformed payload, an invalid enum value in a request, or a bug in a
     * handler. That way one bad message just reports an error back to this client, instead
     * of silently killing their whole session.
     *
     * <p>Every other connected client already has its own thread, so they were never at risk
     * either way. But a client still shouldn't lose their own session over one bad request.
     */
    private void dispatchSafely(Message message) {
        try {
            dispatch(message);
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Error handling " + message.getType() + " from " + socket.getRemoteSocketAddress(), e);
            sendError("Request failed: " + e.getMessage());
        }
    }

    // This is the central request router. Every message this client sends passes through here
    // exactly once. It is matched by its MessageType and handed off to the one handler method
    // that knows how to process that specific request. Adding a new request type means adding
    // both a case here and its handleXxx method below.
    private void dispatch(Message message) {
        switch (message.getType()) {
            case LOGIN_REQUEST:
                handleLogin(message);
                break;
            case LOGOUT:
                // No dedicated handler method: logging out just runs the same cleanup a dropped
                // connection would trigger (unregister observers/session), the client stays connected.
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
            case PRODUCT_ADD_REQUEST:
                handleProductAddRequest(message);
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
            case EMPLOYEE_DELETE_REQUEST:
                handleEmployeeDeleteRequest(message);
                break;
            case LOG_LIST_REQUEST:
                handleLogListRequest(message);
                break;
            default:
                // Reachable if a client sends a MessageType this server build doesn't recognize
                // (e.g. a protocol mismatch between client and server versions).
                sendError("Unhandled message type: " + message.getType());
        }
    }

    // ---- login / logout -------------------------------------------------

    // Handles LOGIN_REQUEST. It checks the username and password against AuthService. Only if
    // that succeeds does it also claim this username's single-session slot and wire this
    // handler up to receive live updates. Either way, a LOGIN_RESPONSE is sent back at the end.
    private void handleLogin(Message message) {
        LoginRequest request = message.readPayload(context.getGson(), LoginRequest.class);
        LoginResponse response = context.getAuthService().login(request.getUsername(), request.getPassword());

        if (response.isSuccess()) {
            // The credentials were valid, but a username may only be logged in from one place
            // at a time. tryLogin enforces that rule: it claims the session slot for this
            // connection's sessionId as one atomic step. If another connection already holds
            // that slot, this login is turned into a failure, even though the password was
            // actually correct.
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

    // Registers this handler as an observer of everything a logged-in client needs pushed to it
    // live: its own branch's inventory, the network-wide customer directory, and the chat mediator.
    private void subscribeToLiveUpdates() {
        String branchId = loggedInEmployee.getBranchId();
        // Not every employee is tied to a branch (e.g. an admin) — only subscribe to inventory
        // updates when there's an actual branch to subscribe to.
        if (branchId != null) {
            subscribedBranch = context.getStoreChain().getBranch(branchId);
            if (subscribedBranch != null) {
                // Lambda implementing InventoryObserver: whenever this branch's stock changes,
                // push the new quantity straight to this client.
                inventoryObserver = (product, newQuantity) -> pushInventoryUpdate(branchId, product, newQuantity);
                subscribedBranch.getInventory().addObserver(inventoryObserver);
            }
        }
        // The customer directory is shared across all branches, so every logged-in client
        // observes it regardless of which branch they're assigned to.
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
        // Lets other clients start/join a chat with this employee once they're online.
        context.getChatMediator().register(loggedInEmployee, this);
    }

    // Undoes everything subscribeToLiveUpdates set up, and also releases the session slot.
    // This runs both on an explicit LOGOUT message and from run()'s finally block on any
    // disconnect. That means it must be safe to call even for a client that never logged in
    // at all — hence all the null checks below.
    private void cleanupOnDisconnect() {
        if (loggedInEmployee != null) {
            context.getChatMediator().unregister(loggedInEmployee.getEmployeeNumber());
        }
        if (loggedInUsername != null) {
            // Frees the single-session slot so this username can log in again (from anywhere).
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

    // Handles INVENTORY_SNAPSHOT_REQUEST: sends back the full current stock list for this
    // employee's branch, one StockEntry per product, so the client can populate its inventory view.
    private void handleInventorySnapshotRequest() {
        if (!requireLoginAndBranch()) {
            return;
        }
        Map<Product, Integer> snapshot = subscribedBranch.getInventory().snapshot();
        List<StockEntry> items = new ArrayList<>();
        // Flatten the Product -> quantity map into the flat StockEntry DTOs the wire protocol uses.
        for (Map.Entry<Product, Integer> entry : snapshot.entrySet()) {
            Product p = entry.getKey();
            items.add(new StockEntry(p.getSku(), p.getName(), p.getCategory(), p.getPrice(), entry.getValue()));
        }
        channel.send(Message.of(context.getGson(), MessageType.INVENTORY_SNAPSHOT_RESPONSE,
                new InventorySnapshotResponse(subscribedBranch.getId(), items)));
    }

    // This is the callback registered as this handler's InventoryObserver (see
    // subscribeToLiveUpdates). It fires whenever the subscribed branch's stock changes, from
    // any client's action, and pushes the new quantity to this client. That's what keeps this
    // client's inventory view live, without it needing to poll.
    private void pushInventoryUpdate(String branchId, Product product, int newQuantity) {
        // The socket may already be gone (client disconnected) while this observer is still
        // registered momentarily — guard against sending on a closed/null channel.
        if (channel == null) {
            return;
        }
        StockEntry entry = new StockEntry(product.getSku(), product.getName(), product.getCategory(),
                product.getPrice(), newQuantity);
        channel.send(Message.of(context.getGson(), MessageType.INVENTORY_UPDATE,
                new InventoryUpdateNotice(branchId, entry)));
    }

    // ---- purchases ----------------------------------------------------------

    // Handles PURCHASE_REQUEST. It looks up the product and customer named in the request,
    // then asks PurchaseService to actually sell the product — applying any discount and
    // reducing stock. After that it records the sale, logs it, and reports back the amount
    // charged and the branch's new stock level.
    private void handlePurchaseRequest(Message message) {
        if (!requireLoginAndBranch()) {
            return;
        }
        PurchaseRequest request = message.readPayload(context.getGson(), PurchaseRequest.class);
        Product product = context.getStoreChain().getProduct(request.getSku());
        Customer customer = context.getStoreChain().getCustomerDirectory().get(request.getCustomerPersonalId());

        // Both lookups are checked before PurchaseService is touched at all. An unknown SKU or
        // personal ID is a normal, expected failure — a typo, or a stale client cache — not a
        // bug. So it is reported back as a PURCHASE_RESPONSE failure, not thrown as an
        // exception.
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
            // The actual sale: computes pricing/discount for this customer type and decrements
            // the branch's stock. May throw if e.g. requested quantity exceeds what's in stock.
            PurchaseResult result = context.getPurchaseService()
                    .purchase(subscribedBranch, product, request.getQuantity(), customer);
            // Persist the sale for reporting, and audit-log it against the employee who rang it up.
            context.getSalesRecordRepository().add(new SalesRecord(subscribedBranch.getId(), result));
            LogManager.getInstance().log(new LogEvent(LogType.SALE, loggedInEmployee.getEmployeeNumber(),
                    "Sold " + request.getQuantity() + "x " + product.getSku() + " to " + customer.getPersonalId()
                            + " for " + result.getAmountCharged()));
            int newQuantity = subscribedBranch.getInventory().getQuantity(product);
            channel.send(Message.of(context.getGson(), MessageType.PURCHASE_RESPONSE,
                    PurchaseResponse.success(result.getListTotal(), result.getAmountCharged(), newQuantity)));
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Business-rule rejection from PurchaseService (e.g. insufficient stock) — reported
            // back to this client the same way the lookup failures above are.
            channel.send(Message.of(context.getGson(), MessageType.PURCHASE_RESPONSE,
                    PurchaseResponse.failure(e.getMessage())));
        }
    }

    /**
     * Shift-manager-only: introduces a brand-new product into the chain-wide catalog and
     * gives it an opening quantity at this employee's own branch.
     *
     * <p>Restocking can only ever top up a SKU the catalog already knows, so without this
     * there was no way at all to sell something the server wasn't seeded with at startup.
     *
     * <p>Why SHIFT_MANAGER and not ADMIN, which is who adds employees: an ADMIN has no branch
     * (see {@code requireLoginAndBranch}), and the client hides the whole Inventory tab from
     * anyone without one — so an admin-gated version of this could never actually be reached.
     * A shift manager is the most senior role that does have a branch to stock.
     */
    private void handleProductAddRequest(Message message) {
        if (!requireLoginAndBranch()) {
            return;
        }
        if (!requireCurrentRole(Role.SHIFT_MANAGER).isPresent()) {
            channel.send(Message.of(context.getGson(), MessageType.PRODUCT_ADD_RESPONSE,
                    ProductAddResponse.failure("Only a shift manager can add products")));
            return;
        }
        ProductAddRequest request = message.readPayload(context.getGson(), ProductAddRequest.class);
        try {
            requireValid(request.getSku(), "SKU");
            requireValid(request.getName(), "Product name");
            requireValid(request.getCategory(), "Category");
            if (request.getPrice() <= 0) {
                throw new ValidationException("Price", "Price must be greater than 0");
            }
            String sku = request.getSku().trim();
            Product product = new Product(sku, request.getName().trim(), request.getCategory().trim(), request.getPrice());
            // addProductIfAbsent checks and writes as one atomic step (see its javadoc for why
            // a separate getProduct(sku)-then-addProduct check would race). The catalog is a
            // keyed map, so an unguarded add of an existing SKU would silently replace that
            // product's name and price, while every branch's Inventory -- keyed by the Product
            // object itself -- would still hold the old instance: one SKU reporting two
            // different products depending on which map you read.
            if (!context.getStoreChain().addProductIfAbsent(product)) {
                throw new DuplicateProductException(sku);
            }
            // Stocking it here is what makes it visible: Inventory only holds an entry for a
            // product once stock exists for it, and addStock's observer push is what puts the
            // new row on every other connected client at this branch, live. addStock also
            // rejects a non-positive quantity, so that validation isn't repeated here.
            subscribedBranch.getInventory().addStock(product, request.getInitialQuantity());
            LogManager.getInstance().log(new LogEvent(LogType.PRODUCT_ADDED, loggedInEmployee.getEmployeeNumber(),
                    "Added product " + product.getSku() + " (" + product.getName() + ") with opening stock "
                            + request.getInitialQuantity() + " at " + subscribedBranch.getId()));
            channel.send(Message.of(context.getGson(), MessageType.PRODUCT_ADD_RESPONSE, ProductAddResponse.success()));
        } catch (IllegalArgumentException e) {
            // Covers the field validation above, DuplicateProductException, and the
            // InvalidQuantityException addStock throws for a non-positive opening quantity.
            channel.send(Message.of(context.getGson(), MessageType.PRODUCT_ADD_RESPONSE,
                    ProductAddResponse.failure(e.getMessage())));
        }
    }

    /** Restocking: adds stock to a branch's inventory, as opposed to selling it. */
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
            // Adds the requested quantity to this branch's stock; the Inventory's own observer
            // mechanism is what pushes INVENTORY_UPDATE to every other subscribed client from here.
            int newQuantity = context.getPurchaseService().restock(subscribedBranch, product, request.getQuantity());
            LogManager.getInstance().log(new LogEvent(LogType.PURCHASE, loggedInEmployee.getEmployeeNumber(),
                    "Restocked " + request.getQuantity() + "x " + product.getSku() + " at " + subscribedBranch.getId()
                            + " (new quantity " + newQuantity + ")"));
            channel.send(Message.of(context.getGson(), MessageType.RESTOCK_RESPONSE, RestockResponse.success(newQuantity)));
        } catch (IllegalArgumentException e) {
            // e.g. a negative or zero quantity requested.
            channel.send(Message.of(context.getGson(), MessageType.RESTOCK_RESPONSE, RestockResponse.failure(e.getMessage())));
        }
    }

    // ---- customers ----------------------------------------------------------

    // Handles CUSTOMER_LIST_REQUEST: sends the whole customer directory (all branches share one)
    // back to the client as DTOs, for populating a customer picker/list.
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

    // Handles CUSTOMER_ADD_REQUEST. It validates every field, then builds the right Customer
    // subtype through the factory, based on the requested CustomerType. It then registers the
    // new customer, logs it, and reports success — or a specific failure reason — back to the
    // requesting client.
    private void handleCustomerAddRequest(Message message) {
        if (!requireLogin()) {
            return;
        }
        CustomerAddRequest request = message.readPayload(context.getGson(), CustomerAddRequest.class);
        try {
            // Each of these throws (a ValidationException, which is an IllegalArgumentException)
            // on the first invalid field, short-circuiting the rest of the chain.
            requireValidFullName(request.getFullName());
            requireValidPersonalId(request.getPersonalId());
            requireValidPhone(request.getPhone());
            CustomerType type = CustomerType.valueOf(request.getCustomerType());
            // Normalize to the full 9-digit, zero-padded form before storing it. "12345678" and
            // "012345678" both pass the checksum and refer to the same person, but without this
            // they would be stored under two different keys, and CustomerDirectory would never
            // notice the same person was registered twice.
            String normalizedPersonalId = PersonalIdValidator.normalize(request.getPersonalId());
            Customer customer = CustomerFactory.create(type, normalizedPersonalId, request.getFullName(), request.getPhone());
            context.getStoreChain().getCustomerDirectory().add(customer);
            LogManager.getInstance().log(new LogEvent(LogType.CUSTOMER_REGISTERED, loggedInEmployee.getEmployeeNumber(),
                    "Registered " + type + " customer " + customer.getPersonalId() + " (" + customer.getFullName() + ")"));
            channel.send(Message.of(context.getGson(), MessageType.CUSTOMER_ADD_RESPONSE, CustomerAddResponse.success()));
        } catch (IllegalArgumentException e) {
            // This used to be reported only through sendError, the generic ERROR channel.
            // That meant CustomersPanel had no direct signal that its own request had failed —
            // just an easy-to-miss popup, disconnected from the form that still had the
            // rejected input sitting in it. A dedicated response, the same idea as
            // EMPLOYEE_ADD_RESPONSE, lets the form show the failure right where the user is
            // already looking.
            channel.send(Message.of(context.getGson(), MessageType.CUSTOMER_ADD_RESPONSE,
                    CustomerAddResponse.failure(e.getMessage())));
        } catch (IllegalStateException e) {
            // CustomerDirectory.add throws this one for a duplicate personal ID. It's a
            // different exception type than the validators above use, but the same fix
            // applies: tell this specific form what went wrong.
            channel.send(Message.of(context.getGson(), MessageType.CUSTOMER_ADD_RESPONSE,
                    CustomerAddResponse.failure(e.getMessage())));
        }
    }

    // Callback registered as this handler's CustomerDirectoryObserver: fires whenever ANY client
    // adds or updates a customer, pushing the change to this client so its own customer list
    // stays in sync without polling.
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

    // Handles EMPLOYEE_LIST_REQUEST: any logged-in employee can see the full employee roster
    // (unlike logs, which are admin-only) — read straight from the repository, no filtering.
    private void handleEmployeeListRequest() {
        if (!requireLogin()) {
            return;
        }
        channel.send(Message.of(context.getGson(), MessageType.EMPLOYEE_LIST_RESPONSE,
                new EmployeeListResponse(context.getEmployeeRepository().findAll())));
    }

    /** Admin-only: provisions a new employee's login and profile. */
    private void handleEmployeeAddRequest(Message message) {
        if (!requireLogin()) {
            return;
        }
        // Role gate: only an ADMIN may create new employee accounts. Anyone else gets a
        // dedicated failure response rather than the request silently doing nothing.
        if (!requireCurrentRole(Role.ADMIN).isPresent()) {
            channel.send(Message.of(context.getGson(), MessageType.EMPLOYEE_ADD_RESPONSE,
                    EmployeeAddResponse.failure("Only an admin can add employees")));
            return;
        }
        EmployeeAddRequest request = message.readPayload(context.getGson(), EmployeeAddRequest.class);
        try {
            // Validate every required field up front; the first failure throws and skips the rest.
            requireValidEmployeeNumber(request.getEmployeeNumber());
            requireValidFullName(request.getFullName());
            requireValidPersonalId(request.getPersonalId());
            requireValidPhone(request.getPhone());
            requireValidAccountNumber(request.getAccountNumber());
            requireValid(request.getBranchId(), "Branch");
            requireValid(request.getUsername(), "Username");
            Role role = Role.valueOf(request.getRole());
            Employee employee = new Employee(request.getEmployeeNumber(), request.getFullName(), request.getPersonalId(),
                    request.getPhone(), request.getAccountNumber(), request.getBranchId(), role);
            // Creates the Employee record together with its login Account (username/password).
            // createAccount itself rejects both a taken employee number and a taken username,
            // each as one atomic check-and-save — see its javadoc for why that has to happen
            // there and not as a separate find-then-save check here first.
            context.getAuthService().createAccount(employee, request.getUsername(), request.getPassword());

            // Also attach the new employee to their branch's roster, if the branch id resolves.
            Branch branch = context.getStoreChain().getBranch(request.getBranchId());
            if (branch != null) {
                branch.addEmployee(employee);
            }
            LogManager.getInstance().log(new LogEvent(LogType.EMPLOYEE_REGISTERED, loggedInEmployee.getEmployeeNumber(),
                    "Registered employee " + employee.getEmployeeNumber() + " (" + employee.getFullName() + ", " + role + ")"));
            channel.send(Message.of(context.getGson(), MessageType.EMPLOYEE_ADD_RESPONSE, EmployeeAddResponse.success()));
        } catch (IllegalArgumentException e) {
            // Catches every validation failure above plus DuplicateEmployeeException (which
            // extends IllegalArgumentException) and an invalid Role.valueOf() argument.
            channel.send(Message.of(context.getGson(), MessageType.EMPLOYEE_ADD_RESPONSE,
                    EmployeeAddResponse.failure(e.getMessage())));
        }
    }

    /**
     * Admin-only, the same as adding an employee. This does not force-disconnect the deleted
     * employee if they are currently logged in — their live session just keeps working until
     * they log out or disconnect on their own.
     *
     * <p>But they can never log back in afterward. {@link
     * managestore.server.service.AuthService#login} already refuses any account whose employee
     * record is gone (see its "Account is not linked to an employee record" case). So removing
     * the Employee record, and its Account, here is enough on its own. The username is fully
     * freed, not just left orphaned, and there is no need to add a way to kill a live socket
     * from another thread.
     */
    private void handleEmployeeDeleteRequest(Message message) {
        if (!requireLogin()) {
            return;
        }
        // Role gate, same pattern as add: only an admin may delete an employee.
        if (!requireCurrentRole(Role.ADMIN).isPresent()) {
            channel.send(Message.of(context.getGson(), MessageType.EMPLOYEE_DELETE_RESPONSE,
                    EmployeeDeleteResponse.failure("Only an admin can delete employees")));
            return;
        }
        EmployeeDeleteRequest request = message.readPayload(context.getGson(), EmployeeDeleteRequest.class);
        String targetNumber = request.getEmployeeNumber();

        // This guards against an admin deleting the very account they're currently logged in
        // as. That would immediately orphan this session — see this method's javadoc above
        // about AuthService.login refusing accounts with no linked employee record.
        if (targetNumber != null && targetNumber.equals(loggedInEmployee.getEmployeeNumber())) {
            channel.send(Message.of(context.getGson(), MessageType.EMPLOYEE_DELETE_RESPONSE,
                    EmployeeDeleteResponse.failure("You can't delete your own account while logged in as it")));
            return;
        }

        // Confirm the target employee actually exists before doing anything destructive.
        Optional<Employee> target = context.getEmployeeRepository().findByEmployeeNumber(targetNumber);
        if (!target.isPresent()) {
            channel.send(Message.of(context.getGson(), MessageType.EMPLOYEE_DELETE_RESPONSE,
                    EmployeeDeleteResponse.failure("Unknown employee number: " + targetNumber)));
            return;
        }

        Employee employee = target.get();
        // A second, independent safety net: refuse to delete an admin if it would leave zero
        // admins in the system. Walking through the two guards above shows this can never
        // actually trigger today — you can't delete yourself, and requireCurrentRole above
        // guarantees the caller is genuinely an admin right now, so the caller always counts
        // as one admin distinct from the target, and at least one admin (the caller) is always
        // left after any single deletion. This check costs nothing to keep, and it keeps
        // protecting the same invariant even if a future change (a bulk-delete action, a
        // scripted cleanup job) reaches this code some other way that doesn't go through
        // those two guards first.
        if (employee.getRole() == Role.ADMIN) {
            long remainingAdmins = context.getEmployeeRepository().findAll().stream()
                    .filter(e -> e.getRole() == Role.ADMIN)
                    .count();
            if (remainingAdmins <= 1) {
                channel.send(Message.of(context.getGson(), MessageType.EMPLOYEE_DELETE_RESPONSE,
                        EmployeeDeleteResponse.failure("Can't delete the last remaining admin account")));
                return;
            }
        }
        // Removes both the Employee record and its Account, which frees the username entirely.
        // See this method's javadoc above for why a live session doesn't need to be killed here.
        context.getAuthService().deleteAccount(targetNumber);
        if (employee.getBranchId() != null) {
            Branch branch = context.getStoreChain().getBranch(employee.getBranchId());
            if (branch != null) {
                branch.removeEmployee(employee);
            }
        }
        LogManager.getInstance().log(new LogEvent(LogType.EMPLOYEE_REMOVED, loggedInEmployee.getEmployeeNumber(),
                "Removed employee " + employee.getEmployeeNumber() + " (" + employee.getFullName() + ")"));
        channel.send(Message.of(context.getGson(), MessageType.EMPLOYEE_DELETE_RESPONSE, EmployeeDeleteResponse.success()));
    }

    // ---- logs ----------------------------------------------------------

    /** Admin-only: logs can contain chat transcripts and other sensitive detail. */
    private void handleLogListRequest(Message message) {
        if (!requireLogin()) {
            return;
        }
        // This is a role gate. Unlike EMPLOYEE_LIST_REQUEST, it uses sendError, the generic
        // ERROR channel, instead of a dedicated *_RESPONSE failure. That's because
        // LogListResponse has no failure variant to fill in.
        if (!requireCurrentRole(Role.ADMIN).isPresent()) {
            sendError("Only an admin can view the system log");
            return;
        }
        LogListRequest request = message.readPayload(context.getGson(), LogListRequest.class);
        List<LogEvent> events;
        // No filter means "everything"; otherwise parse the requested LogType and filter to just
        // that type, reporting back an error if the client sent a type name that doesn't exist.
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

        // Convert the domain LogEvent objects into the wire-format DTOs (epoch millis instead of Instant).
        List<LogEventDto> dtos = new ArrayList<>();
        for (LogEvent event : events) {
            dtos.add(new LogEventDto(event.getType().name(), event.getActor(), event.getDetails(),
                    event.getTimestamp().toEpochMilli()));
        }
        channel.send(Message.of(context.getGson(), MessageType.LOG_LIST_RESPONSE, new LogListResponse(dtos)));
    }

    // ---- reports ----------------------------------------------------------

    // Handles REPORT_REQUEST: parses the optional day filter, then delegates the actual report
    // building (aggregating sales records by the requested scope/format) to ReportService.
    private void handleReportRequest(Message message) {
        if (!requireLogin()) {
            return;
        }
        ReportRequest request = message.readPayload(context.getGson(), ReportRequest.class);
        LocalDate day;
        try {
            // No day filter means "all time"; a present-but-unparseable day string is reported
            // back as an error rather than silently falling back to "all time".
            day = request.getDay() != null ? LocalDate.parse(request.getDay()) : null;
        } catch (DateTimeParseException e) {
            sendError("Invalid report date: " + request.getDay());
            return;
        }
        // A non-admin asking for a per-branch report can only see their own branch's numbers,
        // not every branch's. Without this, any seller or cashier could read another branch's
        // sales figures just by typing a different branch id into the filter field — reports
        // by product or category still show network-wide totals, since those don't reveal how
        // one specific branch is doing compared to another.
        String filterValue = request.getFilterValue();
        if (request.getScope() == ReportScope.BRANCH && loggedInEmployee.getRole() != Role.ADMIN) {
            filterValue = loggedInEmployee.getBranchId();
        }
        ReportResponse response = context.getReportService().generate(context.getSalesRecordRepository().all(),
                request.getScope(), filterValue, request.getFormat(), day);
        channel.send(Message.of(context.getGson(), MessageType.REPORT_RESPONSE, response));
    }

    // ---- chat ----------------------------------------------------------

    // Handles CHAT_REQUEST. It starts a chat either with one specific employee (a direct chat),
    // or with whichever employee at the target branch picks it up — depending on which field
    // the request filled in. All the actual pairing and notification logic lives in
    // ChatMediator. This handler just forwards the request, and turns a "no" into an ERROR
    // message for this client.
    private void handleChatRequest(Message message) {
        if (!requireLogin()) {
            return;
        }
        ChatRequestDto request = message.readPayload(context.getGson(), ChatRequestDto.class);
        String myNumber = loggedInEmployee.getEmployeeNumber();
        // A non-null target employee number means "chat with this specific person"; otherwise
        // it's a "chat with anyone at this branch" request.
        boolean accepted = request.getTargetEmployeeNumber() != null
                ? context.getChatMediator().requestDirectChat(myNumber, request.getTargetEmployeeNumber())
                : context.getChatMediator().requestChat(myNumber, request.getTargetBranchId());
        if (!accepted) {
            // requestChat only ever refuses for one reason (the requester is already busy), so
            // that's always the right message for it. requestDirectChat can refuse for other
            // reasons too — the target typed in doesn't exist, isn't connected, or has no branch
            // to queue under — where "you're already in an active chat" would be actively
            // wrong: the requester isn't busy at all, the target just isn't reachable.
            boolean requesterIsTheProblem = request.getTargetEmployeeNumber() == null
                    || context.getChatMediator().isBusy(myNumber);
            sendError(requesterIsTheProblem
                    ? "You're already in an active chat — end it before starting another."
                    : "That employee isn't available to chat right now — check the employee number and try again.");
        }
    }

    // Handles CHAT_MESSAGE: forwards the text to whoever this employee is currently chatting
    // with, via ChatMediator (which knows the current pairing, not this handler).
    private void handleChatMessage(Message message) {
        if (!requireLogin()) {
            return;
        }
        ChatMessageDto request = message.readPayload(context.getGson(), ChatMessageDto.class);
        context.getChatMediator().sendMessage(loggedInEmployee.getEmployeeNumber(), request.getText());
    }

    // Handles CHAT_END: ends whatever chat this employee is currently in, if any.
    private void handleChatEnd() {
        if (!requireLogin()) {
            return;
        }
        context.getChatMediator().endChat(loggedInEmployee.getEmployeeNumber());
    }

    // Handles CHAT_JOIN_REQUEST: lets a shift manager listen in on / take over an existing chat
    // between two other employees — restricted to that role since it's an oversight capability.
    private void handleChatJoinRequest(Message message) {
        if (!requireLogin()) {
            return;
        }
        if (!requireCurrentRole(Role.SHIFT_MANAGER).isPresent()) {
            sendError("Only a shift manager can join an existing chat");
            return;
        }
        ChatJoinRequest request = message.readPayload(context.getGson(), ChatJoinRequest.class);
        boolean joined = context.getChatMediator().joinChat(loggedInEmployee.getEmployeeNumber(), request.getTargetEmployeeNumber());
        if (!joined) {
            // Covers both failure reasons at once: the target isn't actually in a chat right now,
            // or this shift manager is already in a different chat of their own.
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

    // Guard used at the top of almost every handler above: bails out with an ERROR message and
    // returns false if this connection hasn't successfully logged in yet.
    private boolean requireLogin() {
        if (loggedInEmployee == null) {
            sendError("Not logged in");
            return false;
        }
        return true;
    }

    // Stronger guard for handlers that also need a branch to operate on (inventory, purchases,
    // restocking) — first requires login, then requires that login to have resolved a branch.
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

    /**
     * Checks this connection's role against the employee repository right now, instead of
     * trusting {@code loggedInEmployee.getRole()} as captured once at login time.
     *
     * <p>Without this, a role check that only reads the cached field is still true even after
     * another admin changes or deletes that employee's record. Two admins logged in at once
     * make this concrete: if admin A deletes admin B while B is still connected, B's own socket
     * still holds a cached ADMIN role and could otherwise go on adding and deleting employees —
     * including deleting A too, leaving zero admins anywhere with no way to create a new one.
     * Re-reading the role from the repository on every privileged action closes that window.
     *
     * @return the employee's current record if they still exist and their role right now is
     *     exactly {@code requiredRole}; empty otherwise, meaning the caller should refuse the request.
     */
    private Optional<Employee> requireCurrentRole(Role requiredRole) {
        if (loggedInEmployee == null) {
            return Optional.empty();
        }
        Optional<Employee> current = context.getEmployeeRepository().findByEmployeeNumber(loggedInEmployee.getEmployeeNumber());
        if (!current.isPresent() || current.get().getRole() != requiredRole) {
            return Optional.empty();
        }
        return current;
    }

    private void sendError(String message) {
        channel.send(Message.of(context.getGson(), MessageType.ERROR, new ErrorMessage(message)));
    }

    /**
     * Input validation shared by the Employee and Customer add-request handlers.
     * {@link ValidationException} is a kind of {@link IllegalArgumentException}, so it still
     * flows into the same catch block each handler already has for reporting a rejected
     * request. It also names which field was at fault, for callers that care which one.
     */
    private void requireValid(String value, String fieldName) {
        if (value == null || value.trim().isEmpty()) {
            throw new ValidationException(fieldName, fieldName + " is required");
        }
    }

    private void requireValidPersonalId(String personalId) {
        String reason = PersonalIdValidator.validate(personalId);
        if (reason != null) {
            throw new ValidationException("Personal ID", reason);
        }
    }

    private void requireValidPhone(String phone) {
        String reason = PhoneValidator.validate(phone);
        if (reason != null) {
            throw new ValidationException("Phone", reason);
        }
    }

    private void requireValidFullName(String fullName) {
        String reason = FullNameValidator.validate(fullName);
        if (reason != null) {
            throw new ValidationException("Full name", reason);
        }
    }

    private void requireValidAccountNumber(String accountNumber) {
        String reason = AccountNumberValidator.validate(accountNumber);
        if (reason != null) {
            throw new ValidationException("Account #", reason);
        }
    }

    private void requireValidEmployeeNumber(String employeeNumber) {
        String reason = EmployeeNumberValidator.validate(employeeNumber);
        if (reason != null) {
            throw new ValidationException("Employee #", reason);
        }
    }
}
