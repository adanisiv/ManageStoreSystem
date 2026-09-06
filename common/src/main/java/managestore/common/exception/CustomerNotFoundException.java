package managestore.common.exception;

/**
 * An update referred to a customer who is not in the directory.
 *
 * <p>This usually happens because the record was removed. For example, a client's
 * table was loaded with a row, and by the time the client acted on that row, someone
 * else had already deleted the customer.
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
