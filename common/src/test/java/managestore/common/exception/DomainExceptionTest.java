package managestore.common.exception;

import managestore.common.model.Customer;
import managestore.common.model.CustomerDirectory;
import managestore.common.model.CustomerFactory;
import managestore.common.model.CustomerType;
import managestore.common.model.Inventory;
import managestore.common.model.Product;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The domain exceptions carry the facts of each failure as fields, not only inside a
 * formatted message. These tests pin that down: a caller can read what went wrong
 * without parsing English, and — because each class still extends the standard
 * exception it replaced — every {@code catch} block written against
 * {@link IllegalArgumentException} / {@link IllegalStateException} still catches it.
 */
class DomainExceptionTest {

    private final Product shirt = new Product("SKU-1", "Shirt", "Tops", 100.0);

    @Test
    void insufficientStockCarriesRequestedAvailableAndShortfall() {
        Inventory inventory = new Inventory();
        inventory.addStock(shirt, 3);

        InsufficientStockException e = assertThrows(InsufficientStockException.class,
                () -> inventory.removeStock(shirt, 5));

        assertEquals("SKU-1", e.getSku());
        assertEquals(5, e.getRequested());
        assertEquals(3, e.getAvailable());
        assertEquals(2, e.getShortfall(), "the caller can say how many units short without re-parsing the message");
    }

    @Test
    void purchaseBeyondStockReportsTheSameTypeAsTheInventoryItself() {
        // Customer.purchase checks stock before delegating, so it has its own throw site.
        // Both must report the shortage as the same domain type, or a caller would have to
        // handle the identical situation twice under two different names.
        Inventory inventory = new Inventory();
        inventory.addStock(shirt, 1);
        Customer vip = CustomerFactory.create(CustomerType.VIP, "204812077", "Dana", "0501234567");

        InsufficientStockException e = assertThrows(InsufficientStockException.class,
                () -> vip.purchase(shirt, 4, inventory));

        assertEquals(1, e.getAvailable());
        assertEquals(1, inventory.getQuantity(shirt), "a refused purchase must not have moved stock");
    }

    @Test
    void invalidQuantityCarriesTheRejectedValue() {
        Inventory inventory = new Inventory();

        InvalidQuantityException e = assertThrows(InvalidQuantityException.class,
                () -> inventory.removeStock(shirt, -3));

        assertEquals(-3, e.getQuantity());
    }

    @Test
    void stockOverflowIsReportedAsItsOwnTypeNotAsAPlainBadArgument() {
        Inventory inventory = new Inventory();
        inventory.addStock(shirt, Integer.MAX_VALUE - 1);

        StockOverflowException e = assertThrows(StockOverflowException.class,
                () -> inventory.addStock(shirt, 10));

        assertEquals("SKU-1", e.getSku());
        assertEquals(10, e.getRequested());
        assertTrue(e.getCause() instanceof ArithmeticException,
                "the Math.addExact failure is kept as the cause rather than being swallowed");
        assertEquals(Integer.MAX_VALUE - 1, inventory.getQuantity(shirt), "the rejected call must not have changed the stock");
    }

    @Test
    void duplicateCustomerCarriesTheConflictingId() {
        CustomerDirectory directory = new CustomerDirectory();
        directory.add(CustomerFactory.create(CustomerType.NEW, "204812077", "Dana", "0501234567"));

        DuplicateCustomerException e = assertThrows(DuplicateCustomerException.class,
                () -> directory.add(CustomerFactory.create(CustomerType.VIP, "204812077", "Someone Else", "0507654321")));

        assertEquals("204812077", e.getPersonalId());
    }

    @Test
    void updatingAnAbsentCustomerIsItsOwnTypeSeparateFromADuplicate() {
        // Both used to be a bare IllegalStateException with different text, which meant a
        // caller could only tell "already exists" from "does not exist" by reading the message.
        CustomerDirectory directory = new CustomerDirectory();

        CustomerNotFoundException e = assertThrows(CustomerNotFoundException.class,
                () -> directory.update(CustomerFactory.create(CustomerType.NEW, "309825149", "Ghost", "0501111111")));

        assertEquals("309825149", e.getPersonalId());
    }

    @Test
    void everyDomainExceptionStillMatchesTheStandardTypeItReplaced() {
        // This is what makes the change safe: existing handlers catch IllegalArgumentException
        // and IllegalStateException, and they must keep catching these without modification.
        assertTrue(new InvalidQuantityException(0) instanceof IllegalArgumentException);
        assertTrue(new ValidationException("Phone", "bad") instanceof IllegalArgumentException);
        assertTrue(new DuplicateEmployeeException("E1") instanceof IllegalArgumentException);
        assertTrue(new DuplicateUsernameException("admin") instanceof IllegalArgumentException);
        assertTrue(new StockOverflowException("SKU-1", 1, new ArithmeticException()) instanceof IllegalArgumentException);

        assertTrue(new InsufficientStockException("SKU-1", 2, 1) instanceof IllegalStateException);
        assertTrue(new DuplicateCustomerException("1") instanceof IllegalStateException);
        assertTrue(new CustomerNotFoundException("1") instanceof IllegalStateException);
    }

    @Test
    void validationExceptionNamesTheFieldAtFault() {
        // The field name is what lets a form put the error next to the offending input
        // instead of showing a popup that leaves the user hunting for what was wrong.
        ValidationException e = new ValidationException("Personal ID", "Personal ID check digit is invalid");

        assertEquals("Personal ID", e.getFieldName());
        assertEquals("Personal ID check digit is invalid", e.getMessage());
    }
}
