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
        if (customersByPersonalId.putIfAbsent(customer.getPersonalId(), customer) != null) {
            throw new DuplicateCustomerException(customer.getPersonalId());
        }
        for (CustomerDirectoryObserver observer : observers) {
            observer.onCustomerAdded(customer);
        }
    }

    public void update(Customer customer) {
        if (!customersByPersonalId.containsKey(customer.getPersonalId())) {
            throw new CustomerNotFoundException(customer.getPersonalId());
        }
        customersByPersonalId.put(customer.getPersonalId(), customer);
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
