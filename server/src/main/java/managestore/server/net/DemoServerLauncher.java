package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.Branch;
import managestore.common.model.Customer;
import managestore.common.model.Employee;
import managestore.common.model.LogEvent;
import managestore.common.model.LogType;
import managestore.common.model.NewCustomer;
import managestore.common.model.Product;
import managestore.common.model.PurchaseResult;
import managestore.common.model.ReturningCustomer;
import managestore.common.model.Role;
import managestore.common.model.SalesRecord;
import managestore.common.model.StoreChain;
import managestore.common.model.VIPCustomer;
import managestore.common.protocol.NetworkDefaults;
import managestore.server.repository.AccountRepository;
import managestore.server.repository.EmployeeRepository;
import managestore.server.repository.JsonFileAccountRepository;
import managestore.server.repository.JsonFileEmployeeRepository;
import managestore.server.service.AuthService;
import managestore.server.service.LogManager;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A convenience entry point for trying out the whole app quickly.
 *
 * <p>{@link ServerMain} always starts with a completely empty network: no
 * branches, no products, no accounts. That is by design, so the real
 * "admin creates every account" flow actually gets exercised. This class
 * skips that setup step instead: it seeds two branches, a product catalog
 * with starting stock, demo customers, a few days of sales history, and a
 * handful of demo accounts, then starts the exact same server loop.
 *
 * <p>{@link ServerMain} together with {@link BootstrapAdmin} is still the
 * real entry point to use for a clean deployment.
 */
public final class DemoServerLauncher {

    private DemoServerLauncher() {
    }

    public static void main(String[] args) throws IOException {
        // Same port/data-dir setup as ServerMain — the demo launcher reuses the real bootstrap
        // plumbing, it just seeds data into it before serving.
        int port = args.length > 0 ? Integer.parseInt(args[0]) : NetworkDefaults.DEFAULT_PORT;
        Path dataDir = Paths.get("data");

        EmployeeRepository employeeRepository = new JsonFileEmployeeRepository(dataDir.resolve("employees.json"));
        AccountRepository accountRepository = new JsonFileAccountRepository(dataDir.resolve("accounts.json"));
        AuthService authService = new AuthService(accountRepository, employeeRepository);

        // Build the branches/products/stock in memory, then create demo employee accounts and
        // customers against them — order matters here since accounts/customers reference branches.
        StoreChain storeChain = seedStoreChain();
        seedAccountsIfMissing(authService, accountRepository, storeChain);
        seedCustomersIfMissing(storeChain);

        // Sales history seeding needs a ServerContext (it reads/writes through the sales record
        // repository the context owns), so the context is built before that last seeding step.
        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, new Gson());
        seedSalesHistoryIfMissing(context, storeChain);

        // From here on this is identical to ServerMain: bind the socket, accept connections on
        // a pooled thread per client, until the socket is closed.
        try (ServerSocket serverSocket = ServerMain.bind(port)) {
            ExecutorService clientPool = Executors.newCachedThreadPool();
            try {
                ServerMain.acceptLoop(serverSocket, context, clientPool);
            } finally {
                clientPool.shutdownNow();
            }
        }
    }

    private static StoreChain seedStoreChain() {
        StoreChain storeChain = new StoreChain();

        // Two branches to demonstrate that inventory/stock is tracked per branch, not globally.
        Branch downtown = new Branch("B1", "Downtown Branch");
        Branch uptown = new Branch("B2", "Uptown Branch");
        storeChain.addBranch(downtown);
        storeChain.addBranch(uptown);

        // The product catalog itself is chain-wide (not per branch) — only the stock quantities below are per branch.
        Product tshirt = new Product("SKU-TSHIRT", "Basic T-Shirt", "Tops", 49.90);
        Product jeans = new Product("SKU-JEANS", "Slim Jeans", "Bottoms", 149.90);
        Product jacket = new Product("SKU-JACKET", "Winter Jacket", "Outerwear", 349.90);
        Product sneakers = new Product("SKU-SNEAKERS", "Canvas Sneakers", "Footwear", 199.90);
        Product hat = new Product("SKU-HAT", "Wool Beanie", "Accessories", 39.90);
        Product socks = new Product("SKU-SOCKS", "Sock 3-Pack", "Accessories", 29.90);
        for (Product product : new Product[]{tshirt, jeans, jacket, sneakers, hat, socks}) {
            storeChain.addProduct(product);
        }

        // Give each branch its own starting stock levels; uptown deliberately doesn't carry
        // every product (no jacket or hat there) to show inventory can differ between branches.
        downtown.getInventory().addStock(tshirt, 40);
        downtown.getInventory().addStock(jeans, 20);
        downtown.getInventory().addStock(jacket, 8);
        downtown.getInventory().addStock(sneakers, 15);
        downtown.getInventory().addStock(hat, 30);
        downtown.getInventory().addStock(socks, 50);
        uptown.getInventory().addStock(tshirt, 25);
        uptown.getInventory().addStock(jeans, 15);
        uptown.getInventory().addStock(sneakers, 10);
        uptown.getInventory().addStock(socks, 20);

        return storeChain;
    }

    /** Only seeds accounts that don't already exist, so re-running this against real data/*.json is harmless. */
    private static void seedAccountsIfMissing(AuthService authService, AccountRepository accountRepository, StoreChain storeChain) {
        createIfMissing(authService, accountRepository, storeChain, "admin", "Admin1234",
                new Employee("ADMIN-1", "System Administrator", "000000000", "", "", null, Role.ADMIN));
        createIfMissing(authService, accountRepository, storeChain, "seller1", "Seller123",
                new Employee("E1", "Dana Cohen", "111111111", "050-1111111", "ACC-1", "B1", Role.SELLER));
        createIfMissing(authService, accountRepository, storeChain, "mgr1", "Manager123",
                new Employee("E2", "Roi Levi", "222222222", "050-2222222", "ACC-2", "B1", Role.SHIFT_MANAGER));
        createIfMissing(authService, accountRepository, storeChain, "seller2", "Seller123",
                new Employee("E3", "Maya Katz", "333333333", "050-3333333", "ACC-3", "B2", Role.CASHIER));

        System.out.println("Demo accounts ready:");
        System.out.println("  admin   / Admin1234  (ADMIN, no branch)");
        System.out.println("  seller1 / Seller123  (SELLER, Downtown Branch / B1)");
        System.out.println("  mgr1    / Manager123 (SHIFT_MANAGER, Downtown Branch / B1)");
        System.out.println("  seller2 / Seller123  (CASHIER, Uptown Branch / B2)");
    }

    private static void createIfMissing(AuthService authService, AccountRepository accountRepository, StoreChain storeChain,
                                         String username, String password, Employee employee) {
        // Skip account creation entirely if this username already exists — makes re-running the
        // demo launcher against existing data/*.json files a no-op instead of an error or duplicate.
        if (accountRepository.findByUsername(username).isPresent()) {
            return;
        }
        // Creates both the Employee record and its login Account together.
        authService.createAccount(employee, username, password);
        LogManager.getInstance().log(new LogEvent(LogType.EMPLOYEE_REGISTERED, "system-seed",
                "Registered employee " + employee.getEmployeeNumber() + " (" + employee.getFullName() + ", " + employee.getRole() + ")"));
        // Also attach the employee to their branch's roster, if they have one (admins don't).
        if (employee.getBranchId() != null) {
            Branch branch = storeChain.getBranch(employee.getBranchId());
            if (branch != null) {
                branch.addEmployee(employee);
            }
        }
    }

    /** Idempotent the same way {@link #createIfMissing} is: skips a customer id that's already in the directory. */
    private static void seedCustomersIfMissing(StoreChain storeChain) {
        addCustomerIfMissing(storeChain, new NewCustomer("501234561", "Noa Ben-David", "050-5551111"));
        addCustomerIfMissing(storeChain, new ReturningCustomer("501234562", "Yossi Peretz", "050-5552222"));
        addCustomerIfMissing(storeChain, new VIPCustomer("501234563", "Tamar Shalev", "050-5553333"));
        addCustomerIfMissing(storeChain, new NewCustomer("501234564", "Eitan Mizrahi", "050-5554444"));
    }

    private static void addCustomerIfMissing(StoreChain storeChain, Customer customer) {
        // Personal ID is the directory's key, so a non-null lookup means this customer was
        // already seeded (or added independently) on a previous run.
        if (storeChain.getCustomerDirectory().get(customer.getPersonalId()) != null) {
            return;
        }
        storeChain.getCustomerDirectory().add(customer);
        LogManager.getInstance().log(new LogEvent(LogType.CUSTOMER_REGISTERED, "system-seed",
                "Registered " + customer.getCustomerType() + " customer " + customer.getPersonalId()
                        + " (" + customer.getFullName() + ")"));
    }

    /**
     * Creates backdated sales across the last few days, so the Reports screen has real numbers
     * to show right away, instead of an empty table until someone manually makes a sale. Having
     * sales on more than one day also gives us something to demonstrate the daily-report filter
     * with.
     *
     * <p>This only runs if the sales repository is still empty, so re-running it against a live
     * server mid-demo won't double the numbers.
     */
    private static void seedSalesHistoryIfMissing(ServerContext context, StoreChain storeChain) {
        // Only seed once: if the sales repository already has records (a previous demo run,
        // or real sales made since), skip re-seeding so numbers don't get doubled up.
        if (!context.getSalesRecordRepository().all().isEmpty()) {
            return;
        }
        Customer noa = storeChain.getCustomerDirectory().get("501234561");
        Customer yossi = storeChain.getCustomerDirectory().get("501234562");
        Customer tamar = storeChain.getCustomerDirectory().get("501234563");
        Product tshirt = storeChain.getProduct("SKU-TSHIRT");
        Product jeans = storeChain.getProduct("SKU-JEANS");
        Product jacket = storeChain.getProduct("SKU-JACKET");
        Product sneakers = storeChain.getProduct("SKU-SNEAKERS");

        // Spread the demo sales across today and the two previous days so the Reports screen's
        // daily filter has more than one day of data to demonstrate.
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        sell(context, "B1", tshirt, 3, noa, today);
        sell(context, "B1", jeans, 1, tamar, today);
        sell(context, "B2", sneakers, 2, yossi, today);
        sell(context, "B1", tshirt, 2, yossi, today.minusDays(1));
        sell(context, "B1", jacket, 1, tamar, today.minusDays(1));
        sell(context, "B2", tshirt, 4, noa, today.minusDays(1));
        sell(context, "B1", jeans, 2, noa, today.minusDays(2));
        sell(context, "B2", jeans, 1, tamar, today.minusDays(2));
    }

    /** Records the sale AND decrements stock, so the Inventory tab's current numbers stay consistent with sales history. */
    private static void sell(ServerContext context, String branchId, Product product, int quantity, Customer customer, LocalDate day) {
        // No discount logic here (unlike the live PurchaseService) — demo sales are recorded at full list price.
        double amount = product.getPrice() * quantity;
        // Backdate the timestamp to midday on the given day, rather than "now", so each seeded sale
        // lands on the intended day for the daily report filter.
        PurchaseResult result = new PurchaseResult(customer, product, quantity, amount, amount);
        Instant timestamp = day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(12 * 3600);
        context.getSalesRecordRepository().add(new SalesRecord(branchId, result, timestamp));
        // Keep inventory consistent with the sale: whatever was "sold" here is also removed from stock.
        context.getStoreChain().getBranch(branchId).getInventory().removeStock(product, quantity);
        LogManager.getInstance().log(new LogEvent(LogType.SALE, "system-seed",
                "Sold " + quantity + "x " + product.getSku() + " to " + customer.getPersonalId() + " for " + amount));
    }
}
