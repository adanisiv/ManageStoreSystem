package managestore.common.protocol;

/** Pushed to every connected client whenever a customer is added or updated (Observer pattern). */
public class CustomerUpdateNotice {

    private final CustomerDto customer;
    private final boolean newlyAdded;

    public CustomerUpdateNotice(CustomerDto customer, boolean newlyAdded) {
        this.customer = customer;
        this.newlyAdded = newlyAdded;
    }

    public CustomerDto getCustomer() {
        return customer;
    }

    public boolean isNewlyAdded() {
        return newlyAdded;
    }
}
