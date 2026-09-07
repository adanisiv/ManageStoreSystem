package managestore.common.protocol;

public class ProductAddResponse {

    private final boolean success;
    private final String errorMessage;

    private ProductAddResponse(boolean success, String errorMessage) {
        this.success = success;
        this.errorMessage = errorMessage;
    }

    public static ProductAddResponse success() {
        return new ProductAddResponse(true, null);
    }

    public static ProductAddResponse failure(String errorMessage) {
        return new ProductAddResponse(false, errorMessage);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }
}
