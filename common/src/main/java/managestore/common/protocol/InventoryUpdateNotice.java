package managestore.common.protocol;

/** Pushed to every client subscribed to a branch whenever that branch's inventory changes (Observer pattern). */
public class InventoryUpdateNotice {

    private final String branchId;
    private final StockEntry entry;

    public InventoryUpdateNotice(String branchId, StockEntry entry) {
        this.branchId = branchId;
        this.entry = entry;
    }

    public String getBranchId() {
        return branchId;
    }

    public StockEntry getEntry() {
        return entry;
    }
}
