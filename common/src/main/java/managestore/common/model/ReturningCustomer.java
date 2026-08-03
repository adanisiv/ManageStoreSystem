package managestore.common.model;

/** A repeat customer: gets a flat loyalty discount on every purchase. */
public class ReturningCustomer extends Customer {

    private static final double LOYALTY_DISCOUNT_RATE = 0.05;

    public ReturningCustomer(String personalId, String fullName, String phone) {
        super(personalId, fullName, phone);
    }

    @Override
    public String getCustomerType() {
        return "Returning";
    }

    @Override
    public double applyDiscount(double amount) {
        return amount * (1 - LOYALTY_DISCOUNT_RATE);
    }
}
