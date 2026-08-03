package managestore.common.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The whole network: every {@link Branch} plus the shared {@link CustomerDirectory}. */
public class StoreChain {

    private final Map<String, Branch> branches = new ConcurrentHashMap<>();
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

    public CustomerDirectory getCustomerDirectory() {
        return customerDirectory;
    }
}
