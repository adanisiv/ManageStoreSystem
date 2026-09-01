package managestore.common.protocol;

import java.util.List;

public class BranchListResponse {

    private final List<BranchDto> branches;

    public BranchListResponse(List<BranchDto> branches) {
        this.branches = branches;
    }

    public List<BranchDto> getBranches() {
        return branches;
    }
}
