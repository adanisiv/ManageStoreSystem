package managestore.server.repository;

import managestore.server.model.Account;

import java.util.Optional;

public interface AccountRepository {

    // Looks up login credentials by username; empty if no account has that username.
    Optional<Account> findByUsername(String username);

    // Upsert: creates the account if its username is new, otherwise overwrites the existing one.
    void save(Account account);

    /** No-op if no account is linked to that employee number. */
    void deleteByEmployeeNumber(String employeeNumber);
}
