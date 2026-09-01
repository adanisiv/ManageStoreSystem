package managestore.common.model;

/** Category of a {@link LogEvent}, matching the log types required by the brief. */
public enum LogType {
    EMPLOYEE_REGISTERED,
    CUSTOMER_REGISTERED,
    /** Stock added to a branch's inventory (the brief's "purchase", as distinct from a customer "sale"). */
    PURCHASE,
    SALE,
    CHAT
}
