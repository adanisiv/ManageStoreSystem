package managestore.common.protocol;

public class EmployeeDeleteResponse {

    private final boolean success;
    private final String errorMessage;

    private EmployeeDeleteResponse(boolean success, String errorMessage) {
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public static EmployeeDeleteResponse success() {
        return new EmployeeDeleteResponse(true, null);
    }

    public static EmployeeDeleteResponse failure(String errorMessage) {
        return new EmployeeDeleteResponse(false, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
