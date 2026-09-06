package managestore.common.exception;

/**
 * A customer with this personal ID is already registered in the chain.
 *
 * <p>This is a state conflict, not bad input — the ID itself is perfectly valid, it
 * is just already taken. {@code CustomerDirectory} detects this with an atomic
 * {@code putIfAbsent} call. That guarantees that if two employees try to register
 * the same person at the same moment, only one of them can succeed. The other one
 * gets this exception.
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
