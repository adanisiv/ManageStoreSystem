package managestore.server.repository;

import managestore.server.model.Account;

import java.util.Optional;

public interface AccountRepository {

    // Looks up login credentials by username. Returns empty if no account has that username.
    Optional<Account> findByUsername(String username);

    // Saves the account. If the username is new, this creates a new account.
    // If the username already exists, this overwrites that account.
    void save(Account account);

    /** Does nothing if no account is linked to that employee number. */
    void deleteByEmployeeNumber(String employeeNumber);
}
