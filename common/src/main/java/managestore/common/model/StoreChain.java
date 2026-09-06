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
        // specific to a branch.
        productCatalog.put(product.getSku(), product);
    }

    public Product getProduct(String sku) {
        return productCatalog.get(sku);
    }

    public CustomerDirectory getCustomerDirectory() {
        return customerDirectory;
    }
}
