package managestore.common.model;

import managestore.common.exception.InvalidQuantityException;
import managestore.common.exception.InsufficientStockException;
import managestore.common.exception.StockOverflowException;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Stock of {@link Product}s for a single branch. This is the Subject side of
 * the Observer pattern: every {@link #addStock}/{@link #removeStock} call
 * notifies all registered {@link InventoryObserver}s with the product's new
 * quantity, which is how a sale or restock by one employee reaches every
 * other employee at the same branch live.
 *
 * <p>Backed by a {@link ConcurrentHashMap} and a {@link CopyOnWriteArrayList}
 * of observers because, once the server is running, multiple client threads
 * can read/write the same branch's inventory concurrently.
 */
public class Inventory {

    private final Map<Product, Integer> stock = new ConcurrentHashMap<>();
    private final List<InventoryObserver> observers = new CopyOnWriteArrayList<>();

    public void addObserver(InventoryObserver observer) {
        observers.add(observer);
    }

    public void removeObserver(InventoryObserver observer) {
        observers.remove(observer);
    }

    public int getQuantity(Product product) {
        return stock.getOrDefault(product, 0);
    }

    public synchronized void addStock(Product product, int quantity) {
        requirePositive(quantity);
        int updated;
        try {
            updated = Math.addExact(getQuantity(product), quantity);
        } catch (ArithmeticException e) {
            // Plain int addition would silently wrap around to a negative "quantity" instead of
            // failing — Math.addExact turns that into a real, catchable error. Not reachable
            // through the client UI today (the Restock spinner caps at 1000), but the protocol
            // itself places no upper bound on what a client sends, and this server should not
            // trust that blindly.
            throw new StockOverflowException(product.getSku(), quantity, e);
        }
        stock.put(product, updated);
        notifyObservers(product, updated);
    }

    public synchronized void removeStock(Product product, int quantity) {
        requirePositive(quantity);
        int current = getQuantity(product);
        if (current < quantity) {
            throw new InsufficientStockException(product.getSku(), quantity, current);
        }
        int updated = current - quantity;
        stock.put(product, updated);
        notifyObservers(product, updated);
    }

    /** Read-only snapshot of the current stock, in insertion order. */
    public Map<Product, Integer> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(stock));
    }

    private void notifyObservers(Product product, int newQuantity) {
        for (InventoryObserver observer : observers) {
            observer.onStockChanged(product, newQuantity);
        }
    }

    private static void requirePositive(int quantity) {
        if (quantity <= 0) {
            throw new InvalidQuantityException(quantity);
        }
    }
}
