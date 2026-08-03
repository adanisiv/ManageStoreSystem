package managestore.common.model;

import java.util.ArrayList;
import java.util.Collection;
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
        branches.put(branch.getId(), branch);
    }

    public Branch getBranch(String branchId) {
        return branches.get(branchId);
    }

    public List<Branch> allBranches() {
        return Collections.unmodifiableList(new ArrayList<>(branches.values()));
    }

    public void addProduct(Product product) {
        productCatalog.put(product.getSku(), product);
    }

    public Product getProduct(String sku) {
        return productCatalog.get(sku);
    }

    public Collection<Product> allProducts() {
        return productCatalog.values();
    }

    public CustomerDirectory getCustomerDirectory() {
        return customerDirectory;
    }
}
