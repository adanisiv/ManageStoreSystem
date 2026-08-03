package managestore.common.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InventoryTest {

    private final Product shirt = new Product("SKU-1", "Shirt", "Tops", 100.0);

    @Test
    void addAndRemoveStockUpdatesQuantity() {
        Inventory inventory = new Inventory();

        inventory.addStock(shirt, 5);
        assertEquals(5, inventory.getQuantity(shirt));

        inventory.removeStock(shirt, 2);
        assertEquals(3, inventory.getQuantity(shirt));
    }

    @Test
    void removingMoreThanAvailableThrows() {
        Inventory inventory = new Inventory();
        inventory.addStock(shirt, 1);

        assertThrows(IllegalStateException.class, () -> inventory.removeStock(shirt, 2));
    }

    @Test
    void observersAreNotifiedOnEveryChange() {
        Inventory inventory = new Inventory();
        List<Integer> notifiedQuantities = new ArrayList<>();
        inventory.addObserver((product, newQuantity) -> notifiedQuantities.add(newQuantity));

        inventory.addStock(shirt, 5);
        inventory.removeStock(shirt, 2);

        assertEquals(Arrays.asList(5, 3), notifiedQuantities);
    }

    @Test
    void removedObserverStopsReceivingUpdates() {
        Inventory inventory = new Inventory();
        List<Integer> notifiedQuantities = new ArrayList<>();
        InventoryObserver observer = (product, newQuantity) -> notifiedQuantities.add(newQuantity);

        inventory.addObserver(observer);
        inventory.addStock(shirt, 1);
        inventory.removeObserver(observer);
        inventory.addStock(shirt, 1);

        assertEquals(Arrays.asList(1), notifiedQuantities);
    }
}
