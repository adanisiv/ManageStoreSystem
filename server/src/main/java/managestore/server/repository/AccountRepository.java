package managestore.server.repository;

import managestore.server.model.Account;

import java.util.Optional;

public interface AccountRepository {

    // Looks up login credentials by username. Returns empty if no account has that username.
    Optional<Account> findByUsername(String username);

    // Saves the account. If the username is new, this creates a new account.
    // If the username already exists, this overwrites that account.
    void save(Account account);

    /**
     * Saves the account only if no account with its username already exists — the check and
     * the save happen as one atomic step, not two separate calls a concurrent request could
     * interleave with.
     *
     * @return true if saved; false if that username was already taken (nothing changed).
     */
    boolean saveIfUsernameAbsent(Account account);

    /** Does nothing if no account is linked to that employee number. */
    void deleteByEmployeeNumber(String employeeNumber);
}
