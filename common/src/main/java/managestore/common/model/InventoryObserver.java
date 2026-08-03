package managestore.common.model;

/**
 * Observer role in the Observer pattern applied to {@link Inventory}. The
 * server registers one implementation per connected client belonging to a
 * branch, so every stock change is pushed live to every employee working
 * that branch instead of being polled.
 */
public interface InventoryObserver {

    void onStockChanged(Product product, int newQuantity);
}
