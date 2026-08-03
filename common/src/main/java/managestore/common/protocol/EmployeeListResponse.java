package managestore.common.protocol;

import managestore.common.model.Employee;

import java.util.List;

/** Employee (unlike Customer) isn't polymorphic, so it can be sent over the wire directly — no DTO needed. */
public class EmployeeListResponse {

    private final List<Employee> employees;

    public EmployeeListResponse(List<Employee> employees) {
        this.employees = employees;
    }

    public List<Employee> getEmployees() {
        return employees;
    }
}
