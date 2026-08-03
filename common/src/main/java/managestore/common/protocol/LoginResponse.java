package managestore.common.protocol;

import managestore.common.model.Employee;

public class LoginResponse {

    private final boolean success;
    private final String errorMessage;
    private final Employee employee;

    private LoginResponse(boolean success, String errorMessage, Employee employee) {
        this.success = success;
        this.errorMessage = errorMessage;
        this.employee = employee;
    }

    public static LoginResponse success(Employee employee) {
        return new LoginResponse(true, null, employee);
    }

    public static LoginResponse failure(String errorMessage) {
        return new LoginResponse(false, errorMessage, null);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public Employee getEmployee() {
        return employee;
    }
}
