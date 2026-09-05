package managestore.common.exception;

/**
 * An update referred to a customer who is not in the directory — typically because
 * the record was removed between the moment a client's table was populated and the
 * moment it acted on a row.
 */
public class CustomerNotFoundException extends StoreStateException {

    private static final long serialVersionUID = 1L;

    private final String personalId;

    public CustomerNotFoundException(String personalId) {
        super("Customer not found: " + personalId);
        this.personalId = personalId;
    }

    public String getPersonalId() {
        return personalId;
    }
}
