package managestore.common.model;

/** Category of a {@link LogEvent} — one per kind of audited action. */
public enum LogType {
    EMPLOYEE_REGISTERED,
    /** Added for symmetry with {@code EMPLOYEE_REGISTERED}: every other admin mutation is audited, so removal should be too. */
    EMPLOYEE_REMOVED,
    CUSTOMER_REGISTERED,
    /** A brand-new product added to the chain-wide catalog, as distinct from restocking an existing one. */
    PRODUCT_ADDED,
    /** Stock added to a branch's inventory from the supplier, as distinct from a customer sale. */
    PURCHASE,
    SALE,
    CHAT
}
