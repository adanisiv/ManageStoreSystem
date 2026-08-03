package managestore.common.model;

/**
 * Job role of an {@link Employee} within a branch. ADMIN is not tied to a
 * single branch and is the only role allowed to open the Admin screen.
 */
public enum Role {
    ADMIN,
    SHIFT_MANAGER,
    CASHIER,
    SELLER
}
