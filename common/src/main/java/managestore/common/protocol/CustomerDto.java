package managestore.common.protocol;

import managestore.common.model.Customer;

/**
 * Flat wire representation of a {@link Customer} — no class hierarchy,
 * just plain fields.
 *
 * <p>The domain model uses a class hierarchy (NewCustomer, ReturningCustomer,
 * VIPCustomer) so the purchase logic can be polymorphic on the server. But
 * Gson cannot reliably tell, from JSON alone, which subclass to rebuild.
 * The client does not need that anyway: it only displays customer data,
 * and refers back to a customer by personalId when it requests a purchase.
 * So a flat DTO with a type label is simpler, and sidesteps the problem
 * entirely.
 */
public class CustomerDto {

    private final String personalId;
    private final String fullName;
    private final String phone;
    private final String customerType;

    public CustomerDto(String personalId, String fullName, String phone, String customerType) {
        this.personalId = personalId;
        this.fullName = fullName;
        this.phone = phone;
        this.customerType = customerType;
    }

    public static CustomerDto from(Customer customer) {
        return new CustomerDto(customer.getPersonalId(), customer.getFullName(),
                customer.getPhone(), customer.getCustomerType());
    }

    public String getPersonalId() {
        return personalId;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPhone() {
        return phone;
    }

    public String getCustomerType() {
        return customerType;
    }

    /** Drives how this DTO displays itself in a JavaFX ChoiceBox (customer picker in InventoryPanel). */
    @Override
    public String toString() {
        return fullName + " (" + customerType + ", " + personalId + ")";
    }
}
