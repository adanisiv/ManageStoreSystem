package managestore.common.exception;

/**
 * A quantity that has to be a positive count of items was zero or negative.
 *
 * <p>Thrown by both {@code Inventory} and {@code Customer.purchase}, which is the
 * point of giving it a name: the same domain rule is enforced in two places, and
 * naming it once means neither copy can drift into wording the failure differently.
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
