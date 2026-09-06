package managestore.common.model;

import managestore.common.exception.InsufficientStockException;
import managestore.common.exception.InvalidQuantityException;

import java.util.Objects;

/**
 * Base type for every customer in the network.
 *
 * <p>Each customer tier is its own class: {@link NewCustomer}, {@link ReturningCustomer},
 * and {@link VIPCustomer}. Each one overrides {@link #applyDiscount(double)} with its own
 * pricing rule. This uses polymorphism instead of an if/else chain on a "type" field. So
 * adding a new customer tier means adding a new class, not adding another branch to the
 * purchase logic.
 *
 * <p>{@link #purchase} is a Template Method. The steps of a purchase are always the
 * same: check stock, compute the total, apply the subclass's discount, then decrement
 * inventory. Only the discount step changes between customer types.
 */
public abstract class Customer {

    private final String personalId;
    private String fullName;
    private String phone;

    protected Customer(String personalId, String fullName, String phone) {
        this.personalId = Objects.requireNonNull(personalId);
        this.fullName = Objects.requireNonNull(fullName);
        this.phone = phone;
    }

    public String getPersonalId() {
        return personalId;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    /** Short label used in UI/reports, e.g. "VIP". */
    public abstract String getCustomerType();

    /**
     * Applies this customer type's discount rule to a pre-discount total.
     * Must return a value in [0, amount].
     */
    public abstract double applyDiscount(double amount);

    /**
     * Runs a purchase against the given branch inventory. It checks stock, computes
     * the discounted price using this customer's own {@link #applyDiscount}, and then
     * decrements stock. This logic is the same for every customer type — only
     * applyDiscount differs per subclass.
     */
    public final PurchaseResult purchase(Product product, int quantity, Inventory inventory) {
        // Step 1: reject a nonsensical purchase before touching any state.
        if (quantity <= 0) {
            throw new InvalidQuantityException(quantity);
        }
        // Step 2: read how much of this product the branch currently has on hand.
        int available = inventory.getQuantity(product);
        // Step 3: make sure there is enough stock to cover the requested quantity.
        if (available < quantity) {
            throw new InsufficientStockException(product.getSku(), quantity, available);
        }
        // Step 4: compute the full price before any discount (unit price * quantity).
        double listTotal = product.getPrice() * quantity;
        // Step 5: hand the pre-discount total to the subclass-specific hook. Each
        // concrete customer type (New/Returning/VIP) decides how much is actually charged.
        double charged = applyDiscount(listTotal);
        // Step 6: only after the price is settled, decrement the branch's stock.
        inventory.removeStock(product, quantity);
        // Step 7: package everything about this sale (list price vs. charged price) for the caller.
        return new PurchaseResult(this, product, quantity, listTotal, charged);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Customer)) return false;
        Customer customer = (Customer) o;
        // Two customers are considered the same person if their personal IDs match,
        // regardless of subclass (New/Returning/VIP) or any other field.
        return personalId.equals(customer.personalId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(personalId);
    }

    @Override
    public String toString() {
        return getCustomerType() + "Customer{" + personalId + ", " + fullName + "}";
    }
}
