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
        // Default path: reuse the timestamp that was already recorded when the
        // purchase happened. We call the other constructor instead of copying
        // the same field assignments here.
        this(branchId, purchaseResult, purchaseResult.getTimestamp());
    }

    /**
     * Same as {@link #SalesRecord(String, PurchaseResult)}, but lets the caller
     * set the timestamp directly. Used for backdated or imported data, and in tests.
     */
    public SalesRecord(String branchId, PurchaseResult purchaseResult, Instant timestamp) {
        this.branchId = branchId;
        this.product = purchaseResult.getProduct();
        this.customer = purchaseResult.getCustomer();
        this.quantity = purchaseResult.getQuantity();
        this.amountCharged = purchaseResult.getAmountCharged();
        this.timestamp = timestamp;
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
