package managestore.common.protocol;

import java.util.List;

public class InventorySnapshotResponse {

    private final String branchId;
    private final List<StockEntry> items;

    public InventorySnapshotResponse(String branchId, List<StockEntry> items) {
        this.branchId = branchId;
        this.items = items;
    }

    public String getBranchId() {
        return branchId;
    }

    public List<StockEntry> getItems() {
        return items;
    }
}
