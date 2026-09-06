package managestore.common.protocol;

/**
 * {@code filterValue} narrows the report down to one branch id, one product
 * sku, or one category name. If it is null, the report covers all of them,
 * grouped by {@code scope}.
 *
 * <p>{@code day} narrows the report further, down to sales made on one
 * calendar day. It uses the ISO-8601 format {@code yyyy-MM-dd} (for example
 * {@code "2026-08-17"}), compared against each sale's timestamp in UTC. If
 * it is null, there is no day restriction, so every sale on record counts.
 *
 * <p>{@code day} is kept as a plain string, not a {@code java.time.LocalDate}.
 * This keeps the wire format a simple JSON string, with no need for a
 * date-specific Gson adapter.
 */
public class ReportRequest {

    private final ReportScope scope;
    private final String filterValue;
    private final ReportFormat format;
    private final String day;

    public ReportRequest(ReportScope scope, String filterValue, ReportFormat format) {
        this(scope, filterValue, format, null);
    }

    public ReportRequest(ReportScope scope, String filterValue, ReportFormat format, String day) {
        this.scope = scope;
        this.filterValue = filterValue;
        this.format = format;
        this.day = day;
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

    public String getDay() {
        return day;
    }
}
