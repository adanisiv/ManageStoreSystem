package managestore.common.model;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CustomerTest {

    private Inventory inventory;
    private Product shirt;

    @BeforeEach
    void setUp() {
        inventory = new Inventory();
        shirt = new Product("SKU-1", "Shirt", "Tops", 100.0);
        inventory.addStock(shirt, 10);
    }

    @Test
    void newCustomerPaysFullPrice() {
        Customer customer = new NewCustomer("111", "Dana", "050-0000000");

        PurchaseResult result = customer.purchase(shirt, 2, inventory);

        assertEquals(200.0, result.getListTotal());
        assertEquals(200.0, result.getAmountCharged());
        assertEquals(8, inventory.getQuantity(shirt));
    }

    @Test
    void returningCustomerGetsFivePercentOff() {
        Customer customer = new ReturningCustomer("222", "Yossi", "050-0000001");

        PurchaseResult result = customer.purchase(shirt, 1, inventory);

        assertEquals(100.0, result.getListTotal());
        assertEquals(95.0, result.getAmountCharged());
    }

    @Test
    void vipCustomerGetsDiscountPlusPerkCredit() {
        Customer customer = new VIPCustomer("333", "Noa", "050-0000002");

        // list total 300 -> 15% off = 255 -> minus 10 perk credit = 245
        PurchaseResult result = customer.purchase(shirt, 3, inventory);

        assertEquals(300.0, result.getListTotal());
        assertEquals(245.0, result.getAmountCharged(), 0.0001);
    }

    @Test
    void vipDiscountNeverGoesNegativeOnACheapEnoughItem() {
        // VIPCustomer's flat $10 perk credit is subtracted *after* the 15% discount, so an item
        // cheap enough (list price under ~$11.76) would otherwise produce a *negative* charge —
        // the store paying the customer to take it. This is the boundary that actually exercises
        // the Math.max(..., 0) floor in VIPCustomer.applyDiscount; nothing else in this file does.
        Customer customer = new VIPCustomer("555", "Tamar", "050-0000004");
        Product cheapHat = new Product("SKU-HAT", "Beanie", "Accessories", 5.0);
        inventory.addStock(cheapHat, 5);

        PurchaseResult result = customer.purchase(cheapHat, 1, inventory);

        assertEquals(5.0, result.getListTotal());
        assertEquals(0.0, result.getAmountCharged(), "a discount can floor at free, but must never charge negative");
    }

    @Test
    void purchaseRejectsInsufficientStock() {
        Customer customer = new NewCustomer("444", "Amit", "050-0000003");

        assertThrows(IllegalStateException.class, () -> customer.purchase(shirt, 999, inventory));
    }

    @Test
    void factoryBuildsCorrectSubclassPerType() {
        assertEquals(NewCustomer.class, CustomerFactory.create(CustomerType.NEW, "1", "a", "p").getClass());
        assertEquals(ReturningCustomer.class, CustomerFactory.create(CustomerType.RETURNING, "2", "b", "p").getClass());
        assertEquals(VIPCustomer.class, CustomerFactory.create(CustomerType.VIP, "3", "c", "p").getClass());
    }
}
