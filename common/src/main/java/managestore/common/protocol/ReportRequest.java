package managestore.common.protocol;

/**
 * {@code filterValue} narrows the scope to one specific branch id / product
 * sku / category name; null means "all of them, grouped by {@code scope}".
 */
public class ReportRequest {

    private final ReportScope scope;
    private final String filterValue;
    private final ReportFormat format;

    public ReportRequest(ReportScope scope, String filterValue, ReportFormat format) {
        this.scope = scope;
        this.filterValue = filterValue;
        this.format = format;
    }

    public ReportScope getScope() {
        return scope;
    }

    public String getFilterValue() {
        return filterValue;
    }

    public ReportFormat getFormat() {
        return format;
    }
}
