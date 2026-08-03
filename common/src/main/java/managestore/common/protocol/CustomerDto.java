package managestore.common.protocol;

import managestore.common.model.Customer;

/**
 * Flat, non-polymorphic wire representation of a {@link Customer}. The
 * domain model uses a class hierarchy (NewCustomer/ReturningCustomer/
 * VIPCustomer) so purchase logic can be polymorphic server-side, but Gson
 * can't reliably deserialize back into "the right subclass" from JSON alone
 * — and the client never needs to: it only displays customer data and
 * refers back to customers by personalId when requesting a purchase, so a
 * flat DTO with a type label is simpler and avoids that whole problem.
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
}
