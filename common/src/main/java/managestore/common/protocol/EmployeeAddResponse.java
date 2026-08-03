package managestore.common.protocol;

public class EmployeeAddResponse {

    private final boolean success;
    private final String errorMessage;

    private EmployeeAddResponse(boolean success, String errorMessage) {
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public static EmployeeAddResponse success() {
        return new EmployeeAddResponse(true, null);
    }

    public static EmployeeAddResponse failure(String errorMessage) {
        return new EmployeeAddResponse(false, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
