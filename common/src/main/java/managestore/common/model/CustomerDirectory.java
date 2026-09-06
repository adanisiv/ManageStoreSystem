package managestore.common.model;

import managestore.common.exception.CustomerNotFoundException;
import managestore.common.exception.DuplicateCustomerException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Network-wide customer list (shared by every branch, not per-branch like
 * {@link Inventory}). Subject side of the Observer pattern: registered
 * {@link CustomerDirectoryObserver}s are notified on add/update so every
 * connected employee sees the same customer data live.
 */
public class CustomerDirectory {

    private final Map<String, Customer> customersByPersonalId = new ConcurrentHashMap<>();
    private final List<CustomerDirectoryObserver> observers = new CopyOnWriteArrayList<>();

    public void addObserver(CustomerDirectoryObserver observer) {
        observers.add(observer);
    }

    public void removeObserver(CustomerDirectoryObserver observer) {
        observers.remove(observer);
    }

    public void add(Customer customer) {
        // putIfAbsent only inserts when the key is not already present. It returns the
        // existing value if the key was already there. So a non-null return here means
        // this personal ID was already registered, and the new customer was NOT added.
        if (customersByPersonalId.putIfAbsent(customer.getPersonalId(), customer) != null) {
            throw new DuplicateCustomerException(customer.getPersonalId());
        }
        // Push the newly added customer out to every registered observer (e.g. connected
        // clients) so their customer lists update live instead of needing a refresh/poll.
        for (CustomerDirectoryObserver observer : observers) {
            observer.onCustomerAdded(customer);
        }
    }

    public void update(Customer customer) {
        // Updating a customer that was never added would silently create one, so
        // require the personal ID to already exist first.
        if (!customersByPersonalId.containsKey(customer.getPersonalId())) {
            throw new CustomerNotFoundException(customer.getPersonalId());
        }
        // Overwrite the stored record with the new data for this personal ID.
        customersByPersonalId.put(customer.getPersonalId(), customer);
        // Notify every observer with the updated customer so all connected views stay in sync.
        for (CustomerDirectoryObserver observer : observers) {
            observer.onCustomerUpdated(customer);
        }
    }

    public Customer get(String personalId) {
        return customersByPersonalId.get(personalId);
    }

    public Collection<Customer> all() {
        return customersByPersonalId.values();
    }
}
