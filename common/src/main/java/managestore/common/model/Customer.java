package managestore.common.model;

import managestore.common.exception.InsufficientStockException;
import managestore.common.exception.InvalidQuantityException;

import java.util.Objects;

/**
 * Base type for every customer in the network. Each concrete customer type
 * (see {@link NewCustomer}, {@link ReturningCustomer}, {@link VIPCustomer})
 * is its own class and overrides {@link #applyDiscount(double)} with its own
 * pricing rule — polymorphism over an if/else chain on a "type" field, so a
 * new customer tier is a new class rather than a new branch scattered across
 * the purchase logic.
 *
 * <p>{@link #purchase} is a Template Method: the steps of a purchase (check
 * stock, compute total, apply the subclass-specific discount, decrement
 * inventory) are fixed here and identical for every customer type; only the
 * discount step varies per subclass.
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
     * Executes a purchase against the given branch inventory: validates
     * stock, computes the discounted price via this customer's own
     * {@link #applyDiscount}, and decrements stock. Identical logic for
     * every customer type — only applyDiscount differs per subclass.
     */
    public final PurchaseResult purchase(Product product, int quantity, Inventory inventory) {
        if (quantity <= 0) {
            throw new InvalidQuantityException(quantity);
        }
        int available = inventory.getQuantity(product);
        if (available < quantity) {
            throw new InsufficientStockException(product.getSku(), quantity, available);
        }
        double listTotal = product.getPrice() * quantity;
        double charged = applyDiscount(listTotal);
        inventory.removeStock(product, quantity);
        return new PurchaseResult(this, product, quantity, listTotal, charged);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Customer)) return false;
        Customer customer = (Customer) o;
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
