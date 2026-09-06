package managestore.common.model;

/**
 * Factory pattern: the one place that maps a {@link CustomerType} to the
 * concrete {@link Customer} subclass to instantiate, so callers (e.g. the
 * "add customer" screen) never need an if/else on type themselves.
 */
public final class CustomerFactory {

    private CustomerFactory() {
    }

    public static Customer create(CustomerType type, String personalId, String fullName, String phone) {
        // Branch on the requested tag and construct the matching concrete subclass.
        switch (type) {
            case NEW:
                return new NewCustomer(personalId, fullName, phone);
            case RETURNING:
                return new ReturningCustomer(personalId, fullName, phone);
            case VIP:
                return new VIPCustomer(personalId, fullName, phone);
            default:
                // Defensive fallback: only reachable if CustomerType gains a new
                // constant that this switch hasn't been updated to handle.
                throw new IllegalArgumentException("Unknown customer type: " + type);
        }
    }
}
