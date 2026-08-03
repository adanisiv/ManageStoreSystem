package managestore.server.repository;

import managestore.server.model.Account;

import java.util.Optional;

public interface AccountRepository {

    Optional<Account> findByUsername(String username);

    void save(Account account);
}
