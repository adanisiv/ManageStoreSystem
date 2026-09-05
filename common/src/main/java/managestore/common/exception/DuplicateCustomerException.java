package managestore.common.exception;

/**
 * A customer with this personal ID is already registered in the chain.
 *
 * <p>A state conflict rather than bad input: the ID is perfectly valid, it is simply
 * taken. {@code CustomerDirectory} detects it through an atomic {@code putIfAbsent},
 * so two employees registering the same person at the same moment can never both
 * succeed — exactly one gets this exception.
 */
public class DuplicateCustomerException extends StoreStateException {

    private static final long serialVersionUID = 1L;

    private final String personalId;

    public DuplicateCustomerException(String personalId) {
        super("Customer already exists: " + personalId);
        this.personalId = personalId;
    }

    public String getPersonalId() {
        return personalId;
    }
}
