package managestore.common.model;

/** A VIP customer: larger discount, and gets a flat additional perk credit deducted. */
public class VIPCustomer extends Customer {

    private static final double VIP_DISCOUNT_RATE = 0.15;
    private static final double PERK_CREDIT = 10.0;

    public VIPCustomer(String personalId, String fullName, String phone) {
        super(personalId, fullName, phone);
    }

    @Override
    public String getCustomerType() {
        return "VIP";
    }

    @Override
    public double applyDiscount(double amount) {
        // First take 15% off the list price. Then subtract a flat $10 perk
        // credit on top of that.
        double discounted = amount * (1 - VIP_DISCOUNT_RATE) - PERK_CREDIT;
        // For a small purchase, the perk credit could push the price below $0.
        // We floor it at 0 so a customer is never charged a negative amount.
        return Math.max(discounted, 0);
    }
}
