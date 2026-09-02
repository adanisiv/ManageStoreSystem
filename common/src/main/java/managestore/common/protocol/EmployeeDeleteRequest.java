package managestore.common.protocol;

public class EmployeeDeleteRequest {

    private final String employeeNumber;

    public EmployeeDeleteRequest(String employeeNumber) {
        this.employeeNumber = employeeNumber;
    }

    public String getEmployeeNumber() {
        return employeeNumber;
    }
}
