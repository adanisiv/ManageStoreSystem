package managestore.server.service;

import managestore.common.exception.DuplicateUsernameException;
import managestore.common.exception.ValidationException;

import managestore.common.model.Employee;
import managestore.common.protocol.LoginResponse;
import managestore.server.model.Account;
import managestore.server.repository.AccountRepository;
import managestore.server.repository.EmployeeRepository;

import java.util.Optional;

/**
 * Validates credentials and creates new accounts under the
 * {@link PasswordPolicy}. This class deliberately does NOT touch
 * {@link SessionManager}. Checking a password is one concern. Checking
 * whether that username is already logged in somewhere else is a separate
 * concern. Keeping them in two separate classes makes each one easier to
 * explain and test on its own.
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
        // Look up the account by username first; it may not exist at all.
        Optional<Account> account = accountRepository.findByUsername(username);
        if (!account.isPresent()
                || !PasswordHasher.matches(password, account.get().getPasswordSalt(), account.get().getPasswordHash())) {
            // Same generic message for "no such user" and "wrong password" so a caller can't
            // use the response to enumerate valid usernames.
            return LoginResponse.failure("Invalid username or password");
        }

        // Credentials check out; now resolve the employee profile the account is linked to.
        Optional<Employee> employee = employeeRepository.findByEmployeeNumber(account.get().getEmployeeNumber());
        if (!employee.isPresent()) {
            // The account exists and the password matched, but its employee record is gone
            // (e.g. deleted out from under it) — treat as a login failure, not a crash.
            return LoginResponse.failure("Account is not linked to an employee record");
        }
        return LoginResponse.success(employee.get());
    }

    /** Used by the Admin screen to provision a new employee's login. */
    public void createAccount(Employee employee, String username, String rawPassword) {
        // Reject weak passwords before touching either repository.
        String policyViolation = passwordPolicy.validate(rawPassword);
        if (policyViolation != null) {
            throw new ValidationException("Password", policyViolation);
        }
        // Usernames must be unique across all accounts.
        if (accountRepository.findByUsername(username).isPresent()) {
            throw new DuplicateUsernameException(username);
        }
        // Generate a fresh random salt and hash the raw password with it — the plaintext
        // password itself is never persisted anywhere.
        String salt = PasswordHasher.newSalt();
        String hash = PasswordHasher.hash(rawPassword, salt);
        // Persist the employee profile first, then the account that links a username/password
        // to that employee's number.
        employeeRepository.save(employee);
        accountRepository.save(new Account(employee.getEmployeeNumber(), username, hash, salt));
    }

    /**
     * The counterpart to {@link #createAccount}: it undoes both halves that method set up.
     * It removes the employee profile and its login credentials together. This fully frees
     * up the username, instead of just leaving it orphaned with no employee behind it.
     * Afterward there is nothing left for {@link #login} to find — it already refuses any
     * account whose employee record is missing. Does nothing if the employee number doesn't
     * exist.
     */
    public void deleteAccount(String employeeNumber) {
        // Remove both halves together so no orphaned employee-without-account or
        // account-without-employee record is left behind.
        employeeRepository.delete(employeeNumber);
        accountRepository.deleteByEmployeeNumber(employeeNumber);
    }
}
