package managestore.common.model;

/** A first-time customer: no discount yet — their purchase track is the plain list price. */
public class NewCustomer extends Customer {

    public NewCustomer(String personalId, String fullName, String phone) {
        super(personalId, fullName, phone);
    }

    @Override
    public String getCustomerType() {
        return "New";
    }

    @Override
    public double applyDiscount(double amount) {
        // No discount rule for a first-time customer: the amount charged equals the list price.
        return amount;
    }
}
