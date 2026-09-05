package managestore.common.model;

/**
 * Observer role for {@link CustomerDirectory}: notified whenever a customer
 * is added or updated, so every branch's employee list of customers stays in
 * sync network-wide.
 */
public interface CustomerDirectoryObserver {

    void onCustomerAdded(Customer customer);

    void onCustomerUpdated(Customer customer);
}
