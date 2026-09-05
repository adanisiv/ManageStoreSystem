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
 * Convenience entry point for trying the whole app out: unlike
 * {@link ServerMain} (which starts with a completely empty network — no
 * branches, no products, no accounts — by design, so the "admin creates
 * every account" flow is exercised for real), this seeds two branches, a
 * product catalog with starting stock, demo customers, a few days of sales
 * history, and a handful of demo accounts, then starts the exact same
 * server loop. {@link ServerMain} + {@link BootstrapAdmin} is still the
 * real entry point for a clean deployment.
 */
public final class DemoServerLauncher {

    private DemoServerLauncher() {
    }

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : NetworkDefaults.DEFAULT_PORT;
        Path dataDir = Paths.get("data");

        EmployeeRepository employeeRepository = new JsonFileEmployeeRepository(dataDir.resolve("employees.json"));
        AccountRepository accountRepository = new JsonFileAccountRepository(dataDir.resolve("accounts.json"));
        AuthService authService = new AuthService(accountRepository, employeeRepository);

        StoreChain storeChain = seedStoreChain();
        seedAccountsIfMissing(authService, accountRepository, storeChain);
        seedCustomersIfMissing(storeChain);

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, new Gson());
        seedSalesHistoryIfMissing(context, storeChain);

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

        Branch downtown = new Branch("B1", "Downtown Branch");
        Branch uptown = new Branch("B2", "Uptown Branch");
        storeChain.addBranch(downtown);
        storeChain.addBranch(uptown);

        Product tshirt = new Product("SKU-TSHIRT", "Basic T-Shirt", "Tops", 49.90);
        Product jeans = new Product("SKU-JEANS", "Slim Jeans", "Bottoms", 149.90);
        Product jacket = new Product("SKU-JACKET", "Winter Jacket", "Outerwear", 349.90);
        Product sneakers = new Product("SKU-SNEAKERS", "Canvas Sneakers", "Footwear", 199.90);
        Product hat = new Product("SKU-HAT", "Wool Beanie", "Accessories", 39.90);
        Product socks = new Product("SKU-SOCKS", "Sock 3-Pack", "Accessories", 29.90);
        for (Product product : new Product[]{tshirt, jeans, jacket, sneakers, hat, socks}) {
            storeChain.addProduct(product);
        }

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
        if (accountRepository.findByUsername(username).isPresent()) {
            return;
        }
        authService.createAccount(employee, username, password);
        LogManager.getInstance().log(new LogEvent(LogType.EMPLOYEE_REGISTERED, "system-seed",
                "Registered employee " + employee.getEmployeeNumber() + " (" + employee.getFullName() + ", " + employee.getRole() + ")"));
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
        if (storeChain.getCustomerDirectory().get(customer.getPersonalId()) != null) {
            return;
        }
        storeChain.getCustomerDirectory().add(customer);
        LogManager.getInstance().log(new LogEvent(LogType.CUSTOMER_REGISTERED, "system-seed",
                "Registered " + customer.getCustomerType() + " customer " + customer.getPersonalId()
                        + " (" + customer.getFullName() + ")"));
    }

    /**
     * Backdated sales across the last few days so Reports has real numbers to
     * show immediately (including something to demonstrate the daily-report
     * filter with) instead of an empty table until someone manually sells
     * something first. Guarded by whether the repository is already
     * non-empty so re-running this against a live server mid-demo doesn't
     * double the numbers.
     */
    private static void seedSalesHistoryIfMissing(ServerContext context, StoreChain storeChain) {
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
        double amount = product.getPrice() * quantity;
        PurchaseResult result = new PurchaseResult(customer, product, quantity, amount, amount);
        Instant timestamp = day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(12 * 3600);
        context.getSalesRecordRepository().add(new SalesRecord(branchId, result, timestamp));
        context.getStoreChain().getBranch(branchId).getInventory().removeStock(product, quantity);
        LogManager.getInstance().log(new LogEvent(LogType.SALE, "system-seed",
                "Sold " + quantity + "x " + product.getSku() + " to " + customer.getPersonalId() + " for " + amount));
    }
}
