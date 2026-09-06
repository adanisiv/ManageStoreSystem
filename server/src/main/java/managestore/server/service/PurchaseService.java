package managestore.server.service;

import managestore.common.model.Branch;
import managestore.common.model.Customer;
import managestore.common.model.Product;
import managestore.common.model.PurchaseResult;

/**
 * A thin layer between the network code and the actual purchase logic. It
 * looks up the branch, product, and customer, then hands off to
 * {@link Customer#purchase}. That method is where the real work happens:
 * checking stock and calculating the discount for that type of customer.
 * Keeping this separate from {@link managestore.server.net.ClientHandler}
 * means the purchase logic can be tested without opening a socket.
 */
public class PurchaseService {

    public PurchaseResult purchase(Branch branch, Product product, int quantity, Customer customer) {
        // Delegate straight to the customer: stock validation, discount calculation and
        // decrementing inventory all happen inside Customer#purchase, not here.
        return customer.purchase(product, quantity, branch.getInventory());
    }

    /**
     * Adds stock of an existing product to a branch's inventory — a
     * restock from the supplier, as distinct from {@link #purchase}
     * (a customer buying from stock).
     */
    public int restock(Branch branch, Product product, int quantity) {
        // Increase the branch's stock for this product...
        branch.getInventory().addStock(product, quantity);
        // ...then read back the new total so the caller can confirm/display it.
        return branch.getInventory().getQuantity(product);
    }
}
