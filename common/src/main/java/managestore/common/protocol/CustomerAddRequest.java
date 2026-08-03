package managestore.common.protocol;

/** customerType must match a {@link managestore.common.model.CustomerType} enum name (e.g. "VIP"). */
public class CustomerAddRequest {

    private final String personalId;
    private final String fullName;
    private final String phone;
    private final String customerType;

    public CustomerAddRequest(String personalId, String fullName, String phone, String customerType) {
        this.personalId = personalId;
        this.fullName = fullName;
        this.phone = phone;
        this.customerType = customerType;
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
