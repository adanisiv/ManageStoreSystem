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
        // First take 15% off the list price, then subtract a flat $10 perk credit
        // on top of that percentage discount.
        double discounted = amount * (1 - VIP_DISCOUNT_RATE) - PERK_CREDIT;
        // The perk credit could push a small purchase below $0, so floor the
        // final charge at 0 — a customer is never charged a negative amount.
        return Math.max(discounted, 0);
    }
}
