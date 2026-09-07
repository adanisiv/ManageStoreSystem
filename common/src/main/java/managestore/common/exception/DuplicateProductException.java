package managestore.common.exception;

/**
 * Someone tried to add a product using a SKU that is already in the chain's catalog.
 *
 * <p>The SKU is a product's identity — {@code Product.equals}/{@code hashCode} are built
 * from it alone, and every branch's {@code Inventory} keys its stock by the product itself.
 * Letting a second product claim an existing SKU would overwrite the catalog entry while
 * every branch's stock map still pointed at the old one, so the same SKU could report a
 * different name and price depending on which map it was read from.
 */
public class DuplicateProductException extends InvalidRequestException {

    private static final long serialVersionUID = 1L;

    private final String sku;

    public DuplicateProductException(String sku) {
        super("A product with SKU " + sku + " already exists");
        this.sku = sku;
    }

    public String getSku() {
        return sku;
    }
}
