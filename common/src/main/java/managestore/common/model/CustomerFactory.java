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
        switch (type) {
            case NEW:
                return new NewCustomer(personalId, fullName, phone);
            case RETURNING:
                return new ReturningCustomer(personalId, fullName, phone);
            case VIP:
                return new VIPCustomer(personalId, fullName, phone);
            default:
                throw new IllegalArgumentException("Unknown customer type: " + type);
        }
    }
}
