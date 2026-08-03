package managestore.server.service;

import managestore.common.model.Employee;
import managestore.common.protocol.LoginResponse;
import managestore.server.model.Account;
import managestore.server.repository.AccountRepository;
import managestore.server.repository.EmployeeRepository;

import java.util.Optional;

/**
 * Validates credentials and creates new accounts under the
 * {@link PasswordPolicy}. Deliberately does NOT touch {@link SessionManager}
 * — a successful password check and "is this username allowed to log in
 * right now" (duplicate-login check) are two separate concerns, kept in two
 * separate classes so each is easy to explain/test on its own.
 */
public class AuthService {

    private final AccountRepository accountRepository;
    private final EmployeeRepository employeeRepository;
    private final PasswordPolicy passwordPolicy;

    public AuthService(AccountRepository accountRepository, EmployeeRepository employeeRepository) {
        this(accountRepository, employeeRepository, PasswordPolicy.standard());
    }

    public AuthService(AccountRepository accountRepository, EmployeeRepository employeeRepository,
                        PasswordPolicy passwordPolicy) {
        this.accountRepository = accountRepository;
        this.employeeRepository = employeeRepository;
        this.passwordPolicy = passwordPolicy;
    }

    public LoginResponse login(String username, String password) {
        Optional<Account> account = accountRepository.findByUsername(username);
        if (!account.isPresent()
                || !PasswordHasher.matches(password, account.get().getPasswordSalt(), account.get().getPasswordHash())) {
            // Same generic message for "no such user" and "wrong password" so a caller can't
            // use the response to enumerate valid usernames.
            return LoginResponse.failure("Invalid username or password");
        }

        Optional<Employee> employee = employeeRepository.findByEmployeeNumber(account.get().getEmployeeNumber());
        if (!employee.isPresent()) {
            return LoginResponse.failure("Account is not linked to an employee record");
        }
        return LoginResponse.success(employee.get());
    }

    /** Used by the Admin screen to provision a new employee's login. */
    public void createAccount(Employee employee, String username, String rawPassword) {
        String policyViolation = passwordPolicy.validate(rawPassword);
        if (policyViolation != null) {
            throw new IllegalArgumentException(policyViolation);
        }
        if (accountRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("Username already taken: " + username);
        }
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(rawPassword, salt);
        employeeRepository.save(employee);
        accountRepository.save(new Account(employee.getEmployeeNumber(), username, hash, salt));
    }
}
