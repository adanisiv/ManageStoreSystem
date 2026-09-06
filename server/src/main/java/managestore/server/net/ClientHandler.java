package managestore.server.net;

import managestore.common.exception.DuplicateEmployeeException;
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
import managestore.common.protocol.PurchaseRequest;
import managestore.common.protocol.PurchaseResponse;
import managestore.common.protocol.ReportRequest;
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
    // Unique per connection (not per employee) — identifies this specific socket/session to
    // SessionManager, so the same employee logging in from a second computer doesn't reuse it.
    private final String sessionId = UUID.randomUUID().toString();

    // volatile because getLoggedInEmployee() may be read from other threads (e.g. another
    // ClientHandler pushing a chat message), while this field is only written from this handler's own thread.
    private volatile String loggedInUsername;
    private volatile Employee loggedInEmployee;
    private MessageChannel channel;

    // Only set once a login has subscribed this handler to live inventory/customer updates;
    // kept here so cleanupOnDisconnect() knows what (if anything) to unregister.
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
        // The try-with-resources closes the underlying socket/streams as soon as this method
        // returns, however that happens (normal disconnect or an exception).
        try (MessageChannel opened = new MessageChannel(socket, context.getGson())) {
            this.channel = opened;
            Message message;
            // Blocks on receive() waiting for the next message; a null return means the client
            // closed the connection cleanly, which ends the loop.
            while ((message = opened.receive()) != null) {
                dispatchSafely(message);
            }
        } catch (IOException e) {
            // A broken/reset connection also ends up here — logged at FINE since this is routine,
            // not a bug (clients disconnect all the time).
            LOG.log(Level.FINE, "Connection closed: " + e.getMessage());
        } finally {
            // Always run cleanup, whether the loop ended normally or via exception, so a dropped
            // connection still unregisters this handler from every observer/session it joined.
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

    // Central request router: every message this client sends comes through here exactly once,
    // matched by its MessageType and handed off to the one handler method that knows how to
    // process that specific request. Adding a new request type means adding both a case here
    // and its handleXxx method below.
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

    // Handles LOGIN_REQUEST: checks the username/password against AuthService, then — only on
    // success — claims this username's single-session slot and wires this handler up to receive
    // live updates, before sending the LOGIN_RESPONSE back either way.
    private void handleLogin(Message message) {
        LoginRequest request = message.readPayload(context.getGson(), LoginRequest.class);
        LoginResponse response = context.getAuthService().login(request.getUsername(), request.getPassword());

        if (response.isSuccess()) {
            // Credentials were valid, but a username may only be logged in from one place at a
            // time — tryLogin enforces that by atomically claiming the session slot for this
            // connection's sessionId. If another connection already holds it, this login is
            // downgraded to a failure even though the password was correct.
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

    // Undoes everything subscribeToLiveUpdates set up, plus releases the session slot. Called on
    // an explicit LOGOUT message and also from run()'s finally block on any disconnect, so this
    // must be safe to call even for a client that never logged in (all the null checks below).
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

    // Callback registered as this handler's InventoryObserver (see subscribeToLiveUpdates):
    // fires whenever the subscribed branch's stock changes, from ANY client's action, and pushes
    // the new quantity to this client so its inventory view stays live without polling.
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

    // Handles PURCHASE_REQUEST: looks up the product and customer named in the request, then asks
    // PurchaseService to actually sell the product (applying any discount, decrementing stock),
    // records the sale, logs it, and reports back the amounts charged and the branch's new stock level.
    private void handlePurchaseRequest(Message message) {
        if (!requireLoginAndBranch()) {
            return;
        }
        PurchaseRequest request = message.readPayload(context.getGson(), PurchaseRequest.class);
        Product product = context.getStoreChain().getProduct(request.getSku());
        Customer customer = context.getStoreChain().getCustomerDirectory().get(request.getCustomerPersonalId());

        // Validate both lookups before touching PurchaseService — an unknown SKU or personal ID
        // is a normal, expected failure (typo, stale client cache), not a bug, so it's reported
        // back as a PURCHASE_RESPONSE failure rather than thrown as an exception.
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

    // Handles CUSTOMER_ADD_REQUEST: validates every field, builds the right Customer subtype via
    // the factory (based on the requested CustomerType), registers it, logs it, and reports success
    // or a specific failure reason back to the requesting client.
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
            Customer customer = CustomerFactory.create(type, request.getPersonalId(), request.getFullName(), request.getPhone());
            context.getStoreChain().getCustomerDirectory().add(customer);
            LogManager.getInstance().log(new LogEvent(LogType.CUSTOMER_REGISTERED, loggedInEmployee.getEmployeeNumber(),
                    "Registered " + type + " customer " + customer.getPersonalId() + " (" + customer.getFullName() + ")"));
            channel.send(Message.of(context.getGson(), MessageType.CUSTOMER_ADD_RESPONSE, CustomerAddResponse.success()));
        } catch (IllegalArgumentException e) {
            // Previously only sendError (the global ERROR channel) reported this — CustomersPanel
            // itself had no direct signal that its own request specifically failed, only an
            // easy-to-miss generic popup with no connection back to the form still sitting there
            // with the rejected input in it. A dedicated response, same as EMPLOYEE_ADD_RESPONSE,
            // lets the form show the failure inline, right where the user is already looking.
            channel.send(Message.of(context.getGson(), MessageType.CUSTOMER_ADD_RESPONSE,
                    CustomerAddResponse.failure(e.getMessage())));
        } catch (IllegalStateException e) {
            // CustomerDirectory.add throws this for a duplicate personal ID — a different
            // exception type than the validators above use, but the same "tell this form
            // specifically" fix applies.
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
        if (loggedInEmployee.getRole() != Role.ADMIN) {
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
            // AuthService.createAccount already rejects a taken *username*, but nothing was
            // checking the employee number itself — re-adding an existing one would silently
            // overwrite that employee's profile (JsonFileEmployeeRepository.save is a keyed
            // upsert), while a second, unrelated account could still end up pointing at it.
            if (context.getEmployeeRepository().findByEmployeeNumber(request.getEmployeeNumber()).isPresent()) {
                throw new DuplicateEmployeeException(request.getEmployeeNumber());
            }
            Role role = Role.valueOf(request.getRole());
            Employee employee = new Employee(request.getEmployeeNumber(), request.getFullName(), request.getPersonalId(),
                    request.getPhone(), request.getAccountNumber(), request.getBranchId(), role);
            // Creates the Employee record together with its login Account (username/password).
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
     * Admin-only, same as add. Doesn't force-disconnect the deleted employee if they're currently
     * logged in — their live session just keeps working until they log out or disconnect — but
     * they can never log back in afterward: {@link managestore.server.service.AuthService#login}
     * already refuses any account whose employee record is gone (see its "Account is not linked
     * to an employee record" case), so removing the Employee (and its Account, so the username is
     * fully freed, not just orphaned) here is enough without adding a way to kill a live socket
     * from another thread.
     */
    private void handleEmployeeDeleteRequest(Message message) {
        if (!requireLogin()) {
            return;
        }
        // Role gate, same pattern as add: only an admin may delete an employee.
        if (loggedInEmployee.getRole() != Role.ADMIN) {
            channel.send(Message.of(context.getGson(), MessageType.EMPLOYEE_DELETE_RESPONSE,
                    EmployeeDeleteResponse.failure("Only an admin can delete employees")));
            return;
        }
        EmployeeDeleteRequest request = message.readPayload(context.getGson(), EmployeeDeleteRequest.class);
        String targetNumber = request.getEmployeeNumber();

        // Guard against an admin deleting the very account they're currently logged in as —
        // that would immediately orphan this session (see this method's javadoc above about
        // AuthService.login refusing accounts with no linked employee record).
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
        // Removes both the Employee record and its Account, freeing the username entirely
        // (see this method's javadoc above for why a live session doesn't need to be killed here).
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
        // Role gate: unlike EMPLOYEE_LIST_REQUEST, this one uses sendError (the generic ERROR
        // channel) rather than a dedicated *_RESPONSE failure, since there's no LogListResponse
        // failure variant to populate.
        if (loggedInEmployee.getRole() != Role.ADMIN) {
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
        ReportResponse response = context.getReportService().generate(context.getSalesRecordRepository().all(),
                request.getScope(), request.getFilterValue(), request.getFormat(), day);
        channel.send(Message.of(context.getGson(), MessageType.REPORT_RESPONSE, response));
    }

    // ---- chat ----------------------------------------------------------

    // Handles CHAT_REQUEST: starts a chat either with one specific employee (a direct chat) or
    // with whichever employee at the target branch picks it up, depending on which field the
    // request populated. All the actual pairing/notification logic lives in ChatMediator — this
    // handler just forwards the request and turns a "no" into an ERROR message for this client.
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
            sendError("You're already in an active chat — end it before starting another.");
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
        if (loggedInEmployee.getRole() != Role.SHIFT_MANAGER) {
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

    private void sendError(String message) {
        channel.send(Message.of(context.getGson(), MessageType.ERROR, new ErrorMessage(message)));
    }

    /** Input validation for the Employee/Customer add-request handlers. {@link ValidationException} is an
     *  {@link IllegalArgumentException}, so it still flows into the same catch block each handler already has
     *  for reporting a rejected request — while also naming which field was at fault, for callers that care. */
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
