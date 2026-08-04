package managestore.server.net;

import managestore.common.model.Employee;
import managestore.common.model.Role;
import managestore.common.protocol.NetworkDefaults;
import managestore.server.repository.AccountRepository;
import managestore.server.repository.EmployeeRepository;
import managestore.server.repository.JsonFileAccountRepository;
import managestore.server.repository.JsonFileEmployeeRepository;
import managestore.server.service.AuthService;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * One-off utility: creates the very first ADMIN account directly against
 * the same {@code data/} JSON files {@link ServerMain} reads from. Needed
 * because, by design, only an existing admin can create new employee
 * accounts (see {@code EMPLOYEE_ADD_REQUEST} in {@link ClientHandler}) — so
 * the first one has to be seeded outside the normal client/server flow.
 * Run this once before starting the server for the first time.
 */
public final class BootstrapAdmin {

    private BootstrapAdmin() {
    }

    public static void main(String[] args) {
        String username = args.length > 0 ? args[0] : "admin";
        String password = args.length > 1 ? args[1] : "admin123";
        String fullName = args.length > 2 ? args[2] : "System Administrator";

        Path dataDir = Paths.get("data");
        EmployeeRepository employeeRepository = new JsonFileEmployeeRepository(dataDir.resolve("employees.json"));
        AccountRepository accountRepository = new JsonFileAccountRepository(dataDir.resolve("accounts.json"));
        AuthService authService = new AuthService(accountRepository, employeeRepository);

        Employee admin = new Employee("ADMIN-1", fullName, "000000000", "", "", null, Role.ADMIN);
        authService.createAccount(admin, username, password);

        System.out.println("Created admin account. Username: " + username + "  Password: " + password);
        System.out.println("Start the server with ServerMain (default port " + NetworkDefaults.DEFAULT_PORT
                + ") and log in with these credentials from the client.");
    }
}
