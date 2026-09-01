package managestore.common.protocol;

/** One line of an inventory snapshot/update: a product plus its current on-hand quantity. */
public class StockEntry {

    private final String sku;
    private final String name;
    private final String category;
    private final double price;
    private final int quantity;

    public StockEntry(String sku, String name, String category, double price, int quantity) {
        this.sku = sku;
        this.name = name;
        this.category = category;
        this.price = price;
        this.quantity = quantity;
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

    public int getQuantity() {
        return quantity;
    }

    /** Drives how this entry displays itself in a JavaFX ChoiceBox (product picker in InventoryPanel). */
    @Override
    public String toString() {
        return name + " — " + sku + " (" + quantity + " in stock)";
    }
}
