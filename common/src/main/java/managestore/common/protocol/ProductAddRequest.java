package managestore.common.protocol;

/**
 * Adds a brand-new product to the chain-wide catalog, and stocks it at the requesting
 * employee's own branch. Shift-manager-only action.
 *
 * <p>{@code initialQuantity} is part of the same request on purpose. A product that exists in
 * the catalog but has never been stocked anywhere shows up in no branch's inventory at all —
 * {@code Inventory} only holds an entry for a product once stock has been added for it — so
 * adding one without an opening quantity would leave it invisible and un-sellable until
 * someone restocked it, and Restock only offers products already in the branch's list.
 */
public class ProductAddRequest {

    private final String sku;
    private final String name;
    private final String category;
    private final double price;
    private final int initialQuantity;

    public ProductAddRequest(String sku, String name, String category, double price, int initialQuantity) {
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.price = price;
        this.initialQuantity = initialQuantity;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public String getCategory() {
        return category;
    }

    public double getPrice() {
        return price;
    }

    public int getInitialQuantity() {
        return initialQuantity;
    }
}
