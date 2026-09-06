package managestore.server.service;

import managestore.common.model.Branch;
import managestore.common.model.Customer;
import managestore.common.model.Product;
import managestore.common.model.PurchaseResult;

/**
 * Thin orchestration layer over the network: looks up the branch/product/
 * customer, then hands off to {@link Customer#purchase}, which is where the
 * actual Template Method + polymorphic discount logic lives. Kept separate
 * from {@link managestore.server.net.ClientHandler} so purchase logic is
 * testable without a socket.
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
