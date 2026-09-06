package managestore.common.model;

import java.util.Objects;

/**
 * A sellable product. Two Product objects are the same product if they have
 * the same SKU, not if they are the same object in memory. This way the
 * same product is still recognized across branches, and after it is
 * deserialized.
 */
public class Product {

    private final String sku;
    private String name;
    private String category;
    private double price;

    public Product(String sku, String name, String category, double price) {
        this.sku = Objects.requireNonNull(sku);
        this.name = Objects.requireNonNull(name);
        this.category = category;
        this.price = price;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public double getPrice() {
        return price;
    }

    public void setPrice(double price) {
        this.price = price;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Product)) return false;
        Product product = (Product) o;
        return sku.equals(product.sku);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sku);
    }

    @Override
    public String toString() {
        return "Product{" + sku + ", " + name + ", " + category + ", price=" + price + "}";
    }
}
