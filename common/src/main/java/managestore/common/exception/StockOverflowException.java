package managestore.common.exception;

/**
 * A restock would have pushed a product's on-hand count past {@link Integer#MAX_VALUE}.
 *
 * <p>Not reachable from the client UI, whose Restock spinner caps well below this — but
 * the wire protocol places no upper bound on what a client can send, and plain {@code int}
 * addition would wrap silently into a <em>negative</em> stock level rather than failing.
 * {@code Math.addExact} turns that into a real error, and this class gives it a name
 * saying what it actually was, instead of an "invalid argument" that reads like the
 * caller simply typed something wrong.
 */
public class StockOverflowException extends InvalidRequestException {

    private static final long serialVersionUID = 1L;

    private final String sku;
    private final int requested;

    public StockOverflowException(String sku, int requested, Throwable cause) {
        super("Restocking " + requested + " of " + sku + " would overflow past Integer.MAX_VALUE", cause);
        this.sku = sku;
        this.requested = requested;
    }

    public String getSku() {
        return sku;
    }

    public int getRequested() {
        return requested;
    }
}
