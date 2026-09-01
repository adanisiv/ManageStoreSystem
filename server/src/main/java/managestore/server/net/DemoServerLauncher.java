package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.Branch;
import managestore.common.model.Employee;
import managestore.common.model.Product;
import managestore.common.model.Role;
import managestore.common.model.StoreChain;
import managestore.common.protocol.NetworkDefaults;
import managestore.server.repository.AccountRepository;
import managestore.server.repository.EmployeeRepository;
import managestore.server.repository.JsonFileAccountRepository;
import managestore.server.repository.JsonFileEmployeeRepository;
import managestore.server.service.AuthService;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Convenience entry point for trying the whole app out: unlike
 * {@link ServerMain} (which starts with a completely empty network — no
 * branches, no products, no accounts — by design, so the brief's "admin
 * screen defines employee accounts" flow is exercised for real), this seeds
 * two branches, a small product catalog with starting stock, and a handful
 * of demo accounts, then starts the exact same server loop. Nothing here is
 * used by the graded flow: {@link ServerMain} + {@link BootstrapAdmin} is
 * still the real entry point.
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

        ServerContext context = new ServerContext(storeChain, authService, employeeRepository, new Gson());
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
        for (Product product : new Product[]{tshirt, jeans, jacket}) {
            storeChain.addProduct(product);
        }

        downtown.getInventory().addStock(tshirt, 40);
        downtown.getInventory().addStock(jeans, 20);
        downtown.getInventory().addStock(jacket, 8);
        uptown.getInventory().addStock(tshirt, 25);
        uptown.getInventory().addStock(jeans, 15);

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
        if (employee.getBranchId() != null) {
            Branch branch = storeChain.getBranch(employee.getBranchId());
            if (branch != null) {
                branch.addEmployee(employee);
            }
        }
    }
}
