package managestore.common.protocol;

public class PurchaseRequest {

    private final String sku;
    private final int quantity;
    private final String customerPersonalId;

    public PurchaseRequest(String sku, int quantity, String customerPersonalId) {
        this.sku = sku;
        this.quantity = quantity;
        this.customerPersonalId = customerPersonalId;
    }

    public String getSku() {
        return sku;
    }

    public int getQuantity() {
        return quantity;
    }

    public String getCustomerPersonalId() {
        return customerPersonalId;
    }
}
