package managestore.common.model;

/** Category of a {@link LogEvent}, matching the log types required by the brief. */
public enum LogType {
    EMPLOYEE_REGISTERED,
    /** Not one of the brief's four required log categories, but a natural, symmetric extension: every other mutation an admin can make gets logged, so removal should too. */
    EMPLOYEE_REMOVED,
    CUSTOMER_REGISTERED,
    /** Stock added to a branch's inventory (the brief's "purchase", as distinct from a customer "sale"). */
    PURCHASE,
    SALE,
    CHAT
}
