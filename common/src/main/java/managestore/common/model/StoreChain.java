package managestore.common.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The whole network. It holds every {@link Branch}, the shared
 * {@link CustomerDirectory}, and the shared product catalog.
 *
 * <p>A product's price, name, and category are the same at every branch.
 * Only the on-hand quantity differs per branch. Each {@link Branch} tracks
 * its own quantity in its own {@link Inventory}.
 */
public class StoreChain {

    private final Map<String, Branch> branches = new ConcurrentHashMap<>();
    private final Map<String, Product> productCatalog = new ConcurrentHashMap<>();
    private final CustomerDirectory customerDirectory = new CustomerDirectory();

    public void addBranch(Branch branch) {
        // Keyed by branch ID, so we can look up one branch directly
        // instead of scanning through a list.
        branches.put(branch.getId(), branch);
    }

    public Branch getBranch(String branchId) {
        return branches.get(branchId);
    }

    public List<Branch> allBranches() {
        // Copy the map's values into a new list, then wrap it as unmodifiable.
        // This gives callers a safe snapshot, not a live view into our internal map.
        return Collections.unmodifiableList(new ArrayList<>(branches.values()));
    }

    public void addProduct(Product product) {
        // Catalog entries are keyed by SKU. The same product (price, name,
        // category) is shared by every branch. Only the stock quantity is
        // specific to a branch. Used only by trusted, sequential startup seeding
        // (DemoServerLauncher) -- addProductIfAbsent below is what a live request must use.
        productCatalog.put(product.getSku(), product);
    }

    /**
     * Adds the product only if no product with this SKU is already in the catalog — the check
     * and the write happen as one atomic map operation, not two separate calls a concurrent
     * PRODUCT_ADD_REQUEST could interleave with. Without that, two requests for the same new
     * SKU could each see "not present" before either writes, and the second add would silently
     * overwrite the first — exactly the corruption the SKU-uniqueness check exists to prevent.
     *
     * @return true if added; false if a product with that SKU already existed (nothing changed).
     */
    public boolean addProductIfAbsent(Product product) {
        return productCatalog.putIfAbsent(product.getSku(), product) == null;
    }

    public Product getProduct(String sku) {
        return productCatalog.get(sku);
    }

    public CustomerDirectory getCustomerDirectory() {
        return customerDirectory;
    }
}
