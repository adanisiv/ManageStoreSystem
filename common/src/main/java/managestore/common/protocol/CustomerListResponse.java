package managestore.common.protocol;

import java.util.List;

public class CustomerListResponse {

    private final List<CustomerDto> customers;

    public CustomerListResponse(List<CustomerDto> customers) {
        this.customers = customers;
    }

    public List<CustomerDto> getCustomers() {
        return customers;
    }
}
