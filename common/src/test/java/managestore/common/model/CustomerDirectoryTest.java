package managestore.common.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomerDirectoryTest {

    @Test
    void addingSameCustomerTwiceThrows() {
        CustomerDirectory directory = new CustomerDirectory();
        Customer customer = new NewCustomer("1", "Dana", "050-0000000");

        directory.add(customer);

        assertThrows(IllegalStateException.class, () -> directory.add(customer));
    }

    @Test
    void observersNotifiedOnAddAndUpdate() {
        CustomerDirectory directory = new CustomerDirectory();
        List<String> events = new ArrayList<>();
        directory.addObserver(new CustomerDirectoryObserver() {
            @Override
            public void onCustomerAdded(Customer customer) {
                events.add("added:" + customer.getPersonalId());
            }

            @Override
            public void onCustomerUpdated(Customer customer) {
                events.add("updated:" + customer.getPersonalId());
            }
        });

        Customer customer = new NewCustomer("1", "Dana", "050-0000000");
        directory.add(customer);
        customer.setPhone("050-1111111");
        directory.update(customer);

        assertEquals(2, events.size());
        assertEquals("added:1", events.get(0));
        assertEquals("updated:1", events.get(1));
    }
}
