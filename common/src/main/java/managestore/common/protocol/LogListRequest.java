package managestore.common.protocol;

/** typeFilter must match a {@link managestore.common.model.LogType} enum name, or null for all types. */
public class LogListRequest {

    private final String typeFilter;

    public LogListRequest(String typeFilter) {
        this.typeFilter = typeFilter;
    }

    public String getTypeFilter() {
        return typeFilter;
    }
}
