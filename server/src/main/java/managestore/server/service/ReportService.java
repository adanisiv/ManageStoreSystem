package managestore.server.service;

import managestore.common.model.Branch;
import managestore.common.model.Product;
import managestore.common.model.SalesRecord;
import managestore.common.model.StoreChain;
import managestore.common.protocol.ReportFormat;
import managestore.common.protocol.ReportLineDto;
import managestore.common.protocol.ReportResponse;
import managestore.common.protocol.ReportScope;
import managestore.server.report.ReportExporter;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups a list of {@link SalesRecord}s into a {@link ReportResponse}. The
 * grouping is controlled by {@link ReportScope}: by branch, by product, by
 * category, or one grand total for everything.
 *
 * <p>When the caller asks for {@link ReportFormat#WORD}, this class also
 * hands the grouped data off to a {@link ReportExporter} to build the actual
 * Word document. Using an exporter object like this is the Strategy pattern:
 * the export step is swappable without changing the grouping logic above it.
 */
public class ReportService {

    private final StoreChain storeChain;
    private final ReportExporter wordExporter;

    public ReportService(StoreChain storeChain, ReportExporter wordExporter) {
        this.storeChain = storeChain;
        this.wordExporter = wordExporter;
    }

    public ReportResponse generate(List<SalesRecord> records, ReportScope scope, String filterValue, ReportFormat format) {
        return generate(records, scope, filterValue, format, null);
    }

    /**
     * Same as {@link #generate(List, ReportScope, String, ReportFormat)}, additionally
     * narrowed to one calendar day when {@code day} is non-null.
     */
    public ReportResponse generate(List<SalesRecord> records, ReportScope scope, String filterValue,
                                    ReportFormat format, LocalDate day) {
        // Narrow to one day first (if requested), then apply the scope/filterValue filter on
        // top of that narrower set.
        List<SalesRecord> filtered = filter(filterByDay(records, day), scope, filterValue);
        // LinkedHashMap so the report lines come out in first-seen order, not shuffled by
        // hashing — makes the output stable and readable.
        Map<String, ReportLineDto> byKey = new LinkedHashMap<>();

        for (SalesRecord record : filtered) {
            // The grouping key (e.g. branch id, SKU, category, or "ALL") this record rolls up into.
            String key = keyFor(scope, record);
            String label = labelFor(scope, record);
            // If this key already has a running total, fold this record's quantity/revenue
            // into it instead of starting over, so multiple sales for the same key accumulate.
            ReportLineDto existing = byKey.get(key);
            int quantity = record.getQuantity() + (existing != null ? existing.getQuantitySold() : 0);
            double revenue = record.getAmountCharged() + (existing != null ? existing.getRevenue() : 0);
            byKey.put(key, new ReportLineDto(label, quantity, revenue));
        }

        // Grand totals across every line, computed once the per-key aggregation is done.
        List<ReportLineDto> lines = new ArrayList<>(byKey.values());
        int totalQuantity = lines.stream().mapToInt(ReportLineDto::getQuantitySold).sum();
        double totalRevenue = lines.stream().mapToDouble(ReportLineDto::getRevenue).sum();
        String title = titleFor(scope, filterValue, day);

        // Only build the Word document when the caller actually asked for that format — the
        // export is comparatively expensive, so it's skipped entirely for JSON/plain responses.
        String wordBase64 = null;
        if (format == ReportFormat.WORD) {
            byte[] bytes = wordExporter.export(title, lines, totalQuantity, totalRevenue);
            // Encode the binary .docx bytes as Base64 text so they can travel inside the
            // same DTO/protocol as the rest of the (text) report data.
            wordBase64 = Base64.getEncoder().encodeToString(bytes);
        }

        return new ReportResponse(title, lines, totalQuantity, totalRevenue, format, wordBase64);
    }

    /** Compares each sale's {@link SalesRecord#getTimestamp()} against {@code day} in UTC, so results are deterministic regardless of server timezone. */
    private List<SalesRecord> filterByDay(List<SalesRecord> records, LocalDate day) {
        // No day filter requested — pass every record through unchanged.
        if (day == null) {
            return records;
        }
        List<SalesRecord> filtered = new ArrayList<>();
        for (SalesRecord record : records) {
            // Convert the record's instant to a calendar date in UTC before comparing, so the
            // same sale is always classified into the same day no matter what timezone the
            // server happens to be running in.
            if (day.equals(record.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate())) {
                filtered.add(record);
            }
        }
        return filtered;
    }

    /**
     * The comparison here is case-insensitive on purpose. A branch id or SKU is really just a
     * code, so its case doesn't matter. But a category name like "Tops" reads as an ordinary
     * word. If someone types "tops" in the Reports filter field and gets an empty table back
     * with no explanation, it looks like the report is broken — not like they made a typo.
     */
    private List<SalesRecord> filter(List<SalesRecord> records, ReportScope scope, String filterValue) {
        // No filter value — every record in scope stays.
        if (filterValue == null) {
            return records;
        }
        String trimmed = filterValue.trim();
        List<SalesRecord> filtered = new ArrayList<>();
        for (SalesRecord record : records) {
            // Case-insensitive comparison against this record's grouping key for the scope.
            if (trimmed.equalsIgnoreCase(keyFor(scope, record))) {
                filtered.add(record);
            }
        }
        return filtered;
    }

    private String keyFor(ReportScope scope, SalesRecord record) {
        switch (scope) {
            case BRANCH:
                return record.getBranchId();
            case PRODUCT:
                return record.getProduct().getSku();
            case CATEGORY:
                return record.getProduct().getCategory();
            case ALL:
            default:
                return "ALL";
        }
    }

    private String labelFor(ReportScope scope, SalesRecord record) {
        switch (scope) {
            case BRANCH:
                Branch branch = storeChain.getBranch(record.getBranchId());
                return branch != null ? branch.getName() : record.getBranchId();
            case PRODUCT:
                Product product = record.getProduct();
                return product.getName() + " (" + product.getSku() + ")";
            case CATEGORY:
                return record.getProduct().getCategory();
            case ALL:
            default:
                return "All Sales";
        }
    }

    private String titleFor(ReportScope scope, String filterValue, LocalDate day) {
        String base;
        switch (scope) {
            case BRANCH:
                base = "Sales by Branch";
                break;
            case PRODUCT:
                base = "Sales by Product";
                break;
            case CATEGORY:
                base = "Sales by Category";
                break;
            case ALL:
            default:
                base = "Sales Report";
        }
        // Layer on optional suffixes: first the filter value in parentheses, then the day —
        // either, both, or neither may be present depending on what the caller asked for.
        String withFilter = filterValue != null ? base + " (" + filterValue + ")" : base;
        return day != null ? withFilter + " on " + day : withFilter;
    }
}
