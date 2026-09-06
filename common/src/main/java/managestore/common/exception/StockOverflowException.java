package managestore.common.exception;

/**
 * A restock would have pushed a product's on-hand count past {@link Integer#MAX_VALUE}.
 *
 * <p>You cannot trigger this from the client UI, since its Restock spinner caps well
 * below this limit. But the wire protocol itself has no upper bound on what a client
 * can send. Plain {@code int} addition would silently wrap around into a
 * <em>negative</em> stock level instead of failing. {@code Math.addExact} turns that
 * into a real, catchable error. This class gives that error a name that says what
 * actually happened, instead of a generic "invalid argument" that sounds like the
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
