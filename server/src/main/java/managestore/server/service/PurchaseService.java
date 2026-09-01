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
        return customer.purchase(product, quantity, branch.getInventory());
    }

    /**
     * Adds stock of an existing product to a branch's inventory — the
     * brief's "purchase" side of "the interface will allow performing
     * purchase and sale of products", as distinct from {@link #purchase}
     * (a customer buying from stock).
     */
    public int restock(Branch branch, Product product, int quantity) {
        branch.getInventory().addStock(product, quantity);
        return branch.getInventory().getQuantity(product);
    }
}
