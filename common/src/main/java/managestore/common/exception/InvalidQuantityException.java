package managestore.common.exception;

/**
 * A quantity that has to be a positive count of items was zero or negative.
 *
 * <p>Both {@code Inventory} and {@code Customer.purchase} throw this. That is the
 * whole reason it has its own name: the same rule is enforced in two places, so
 * naming it once keeps both places from wording the failure differently.
 */
public class InvalidQuantityException extends InvalidRequestException {

    private static final long serialVersionUID = 1L;

    private final int quantity;

    public InvalidQuantityException(int quantity) {
        super("Quantity must be positive: " + quantity);
        this.quantity = quantity;
    }

    /** The rejected value, kept so a caller can report it without re-parsing the message. */
    public int getQuantity() {
        return quantity;
    }
}
