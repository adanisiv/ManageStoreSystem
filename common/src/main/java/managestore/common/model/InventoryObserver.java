package managestore.common.model;

/**
 * Observer role in the Observer pattern, applied to {@link Inventory}.
 * The server registers one of these per connected client at a branch.
 * That way, every stock change is pushed live to all employees at that
 * branch. No one needs to poll for updates.
 */
public interface InventoryObserver {

    void onStockChanged(Product product, int newQuantity);
}
