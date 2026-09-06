package managestore.common.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The whole network: every {@link Branch}, the shared {@link CustomerDirectory},
 * and the shared product catalog (a product's price/name/category is the same
 * everywhere; only the on-hand quantity is per-branch, tracked by each
 * {@link Branch}'s own {@link Inventory}).
 */
public class StoreChain {

    private final Map<String, Branch> branches = new ConcurrentHashMap<>();
    private final Map<String, Product> productCatalog = new ConcurrentHashMap<>();
    private final CustomerDirectory customerDirectory = new CustomerDirectory();

    public void addBranch(Branch branch) {
        // Indexed by branch ID so a specific branch can be looked up directly
        // instead of scanning a list.
        branches.put(branch.getId(), branch);
    }

    public Branch getBranch(String branchId) {
        return branches.get(branchId);
    }

    public List<Branch> allBranches() {
        // Copy the live map's values into a new list and wrap it as unmodifiable,
        // so callers get a safe snapshot instead of a view backed by the internal map.
        return Collections.unmodifiableList(new ArrayList<>(branches.values()));
    }

    public void addProduct(Product product) {
        // Catalog entries are keyed by SKU: the same product (price/name/category)
        // is shared across every branch, only stock quantity is branch-specific.
        productCatalog.put(product.getSku(), product);
    }

    public Product getProduct(String sku) {
        return productCatalog.get(sku);
    }

    public CustomerDirectory getCustomerDirectory() {
        return customerDirectory;
    }
}
