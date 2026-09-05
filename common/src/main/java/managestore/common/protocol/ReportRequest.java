package managestore.common.protocol;

/**
 * {@code filterValue} narrows the scope to one specific branch id / product
 * sku / category name; null means "all of them, grouped by {@code scope}".
 *
 * <p>{@code day} additionally narrows to sales made on one calendar day
 * (ISO-8601 {@code yyyy-MM-dd}, e.g. {@code "2026-08-17"}), compared against
 * each sale's timestamp in UTC; null means "no day restriction, every sale
 * on record". Kept as a plain string
 * (rather than {@code java.time.LocalDate}) so the wire format stays a
 * simple JSON string with no date-specific Gson adapter required.
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
