package managestore.common.protocol;

public class CustomerAddResponse {

    private final boolean success;
    private final String errorMessage;

    private CustomerAddResponse(boolean success, String errorMessage) {
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public static CustomerAddResponse success() {
        return new CustomerAddResponse(true, null);
    }

    public static CustomerAddResponse failure(String errorMessage) {
        return new CustomerAddResponse(false, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
