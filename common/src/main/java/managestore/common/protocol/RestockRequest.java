package managestore.common.protocol;

/** Adds stock of an existing product to the requesting employee's own branch — the brief's "purchase". */
public class RestockRequest {

    private final String sku;
    private final int quantity;

    public RestockRequest(String sku, int quantity) {
        this.sku = sku;
        this.quantity = quantity;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }
}
