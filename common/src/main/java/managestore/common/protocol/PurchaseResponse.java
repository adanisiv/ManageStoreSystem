package managestore.common.protocol;

public class PurchaseResponse {

    private final boolean success;
    private final String errorMessage;
    private final double listTotal;
    private final double amountCharged;
    private final int newQuantity;

    private PurchaseResponse(boolean success, String errorMessage, double listTotal, double amountCharged, int newQuantity) {
        this.success = success;
        this.errorMessage = errorMessage;
        this.listTotal = listTotal;
        this.amountCharged = amountCharged;
        this.newQuantity = newQuantity;
    }

    public static PurchaseResponse success(double listTotal, double amountCharged, int newQuantity) {
        return new PurchaseResponse(true, null, listTotal, amountCharged, newQuantity);
    }

    public static PurchaseResponse failure(String errorMessage) {
        return new PurchaseResponse(false, errorMessage, 0, 0, 0);
    }

    public boolean isSuccess() {
        return success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public double getListTotal() {
        return listTotal;
    }

    public double getAmountCharged() {
        return amountCharged;
    }

    public int getNewQuantity() {
        return newQuantity;
    }
}
