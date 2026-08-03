package managestore.server.service;

import managestore.server.model.Account;
import managestore.server.repository.AccountRepository;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Trivial in-memory stand-in so service tests don't need real files or a database. */
public class InMemoryAccountRepository implements AccountRepository {

    private final Map<String, Account> byUsername = new HashMap<>();

    @Override
    public Optional<Account> findByUsername(String username) {
        return Optional.ofNullable(byUsername.get(username));
    }

    @Override
    public void save(Account account) {
        byUsername.put(account.getUsername(), account);
    }
}
