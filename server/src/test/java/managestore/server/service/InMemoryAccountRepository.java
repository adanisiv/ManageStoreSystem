package managestore.server.service;

import managestore.server.model.Account;
import managestore.server.repository.AccountRepository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Trivial in-memory stand-in so service tests don't need real files or a database. Backed by
 * a ConcurrentHashMap (not a plain HashMap) so this double is also safe to exercise from
 * concurrency tests, the same way the real {@link JsonFileAccountRepository} is.
 */
public class InMemoryAccountRepository implements AccountRepository {

    private final Map<String, Account> byUsername = new ConcurrentHashMap<>();

    @Override
    public Optional<Account> findByUsername(String username) {
        return Optional.ofNullable(byUsername.get(username));
    }

    @Override
    public void save(Account account) {
        byUsername.put(account.getUsername(), account);
    }

    @Override
    public boolean saveIfUsernameAbsent(Account account) {
        return byUsername.putIfAbsent(account.getUsername(), account) == null;
    }

    @Override
    public void deleteByEmployeeNumber(String employeeNumber) {
        byUsername.values().removeIf(account -> account.getEmployeeNumber().equals(employeeNumber));
    }
}
