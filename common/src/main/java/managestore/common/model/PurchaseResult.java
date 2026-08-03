package managestore.common.model;

import java.time.Instant;

/**
 * Outcome of a single {@link Customer#purchase} call: how much the customer
 * was charged after their type-specific discount was applied.
 */
public class PurchaseResult {

    private final Customer customer;
    private final Product product;
    private final int quantity;
    private final double listTotal;
    private final double amountCharged;
    private final Instant timestamp;

    public PurchaseResult(Customer customer, Product product, int quantity,
                           double listTotal, double amountCharged) {
        this.customer = customer;
        this.product = product;
        this.quantity = quantity;
        this.listTotal = listTotal;
        this.amountCharged = amountCharged;
        this.timestamp = Instant.now();
    }

    public Customer getCustomer() {
        return customer;
    }

    public Product getProduct() {
        return product;
    }

    public int getQuantity() {
        return quantity;
    }

    public double getListTotal() {
        return listTotal;
    }

    public double getAmountCharged() {
        return amountCharged;
    }

    public double getDiscountAmount() {
        return listTotal - amountCharged;
    }

    public Instant getTimestamp() {
        return timestamp;
    }
}
