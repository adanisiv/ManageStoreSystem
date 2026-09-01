package managestore.common.protocol;

import managestore.common.model.Branch;

/** Wire-shape for a {@link Branch}: just enough to let a client pick one by name instead of guessing an id. */
public class BranchDto {

    private final String id;
    private final String name;

    public BranchDto(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public static BranchDto from(Branch branch) {
        return new BranchDto(branch.getId(), branch.getName());
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    @Override
    public String toString() {
        return name + " (" + id + ")";
    }
}
