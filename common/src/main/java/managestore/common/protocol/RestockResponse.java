package managestore.common.protocol;

public class RestockResponse {

    private final boolean success;
    private final String errorMessage;
    private final int newQuantity;

    private RestockResponse(boolean success, String errorMessage, int newQuantity) {
        this.success = success;
        this.errorMessage = errorMessage;
        this.newQuantity = newQuantity;
    }

    public static RestockResponse success(int newQuantity) {
        return new RestockResponse(true, null, newQuantity);
    }

    public static RestockResponse failure(String errorMessage) {
        return new RestockResponse(false, errorMessage, 0);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getNewQuantity() {
        return newQuantity;
    }
}
