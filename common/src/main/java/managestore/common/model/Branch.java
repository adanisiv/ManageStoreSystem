package managestore.common.model;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** A single store branch: its own inventory and its own staff list. */
public class Branch {

    private final String id;
    private String name;
    private final Inventory inventory = new Inventory();
    private final List<Employee> staff = new CopyOnWriteArrayList<>();

    public Branch(String id, String name) {
        this.id = Objects.requireNonNull(id);
        this.name = Objects.requireNonNull(name);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Inventory getInventory() {
        return inventory;
    }

    public List<Employee> getStaff() {
        return staff;
    }

    public void addEmployee(Employee employee) {
        staff.add(employee);
    }

    public void removeEmployee(Employee employee) {
        staff.remove(employee);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Branch)) return false;
        Branch branch = (Branch) o;
        return id.equals(branch.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
