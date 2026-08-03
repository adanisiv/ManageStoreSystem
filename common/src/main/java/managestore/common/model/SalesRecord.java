package managestore.common.model;

import java.time.Instant;

/** One completed sale, kept for reporting and log history. */
public class SalesRecord {

    private final String branchId;
    private final Product product;
    private final Customer customer;
    private final int quantity;
    private final double amountCharged;
    private final Instant timestamp;

    public SalesRecord(String branchId, PurchaseResult purchaseResult) {
        this.branchId = branchId;
        this.product = purchaseResult.getProduct();
        this.customer = purchaseResult.getCustomer();
        this.quantity = purchaseResult.getQuantity();
        this.amountCharged = purchaseResult.getAmountCharged();
        this.timestamp = purchaseResult.getTimestamp();
    }

    public String getBranchId() {
        return branchId;
    }

    public Product getProduct() {
        return product;
    }

    public Customer getCustomer() {
        return customer;
    }

    public int getQuantity() {
        return quantity;
    }

    public double getAmountCharged() {
        return amountCharged;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
