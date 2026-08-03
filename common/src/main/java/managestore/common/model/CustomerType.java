package managestore.common.model;

/** Tag used only to pick which {@link Customer} subclass {@link CustomerFactory} builds. */
public enum CustomerType {
    NEW,
    RETURNING,
    VIP
}
