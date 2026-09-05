package managestore.common.exception;

/**
 * A sale or stock removal asked for more units than the branch actually holds.
 *
 * <p>The three numbers that describe the failure — what was asked for, what was
 * there, and which product — are kept as fields rather than only being formatted
 * into the message. A caller that wants to say "only 3 left, order more?" can read
 * {@link #getAvailable()} instead of parsing English out of a string, and a test can
 * assert on the shortfall without depending on the exact wording.
 */
public class InsufficientStockException extends StoreStateException {

    private static final long serialVersionUID = 1L;

    private final String sku;
    private final int requested;
    private final int available;

    public InsufficientStockException(String sku, int requested, int available) {
        super("Insufficient stock for " + sku + ": requested " + requested + ", available " + available);
        this.sku = sku;
        this.requested = requested;
        this.available = available;
    }

    public String getSku() {
        return sku;
    }

    public int getRequested() {
        return requested;
    }

    public int getAvailable() {
        return available;
    }

    /** How many units short the request was — always at least 1. */
    public int getShortfall() {
        return requested - available;
    }
}
