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
 * Aggregates {@link SalesRecord}s into a {@link ReportResponse}, grouped by
 * {@link ReportScope} (branch / product / category / one grand total), then
 * hands off to a {@link ReportExporter} implementation when the caller asked
 * for {@link ReportFormat#WORD} — the Strategy pattern in action.
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
        List<SalesRecord> filtered = filter(filterByDay(records, day), scope, filterValue);
        Map<String, ReportLineDto> byKey = new LinkedHashMap<>();

        for (SalesRecord record : filtered) {
            String key = keyFor(scope, record);
            String label = labelFor(scope, record);
            ReportLineDto existing = byKey.get(key);
            int quantity = record.getQuantity() + (existing != null ? existing.getQuantitySold() : 0);
            double revenue = record.getAmountCharged() + (existing != null ? existing.getRevenue() : 0);
            byKey.put(key, new ReportLineDto(label, quantity, revenue));
        }

        List<ReportLineDto> lines = new ArrayList<>(byKey.values());
        int totalQuantity = lines.stream().mapToInt(ReportLineDto::getQuantitySold).sum();
        double totalRevenue = lines.stream().mapToDouble(ReportLineDto::getRevenue).sum();
        String title = titleFor(scope, filterValue, day);

        String wordBase64 = null;
        if (format == ReportFormat.WORD) {
            byte[] bytes = wordExporter.export(title, lines, totalQuantity, totalRevenue);
            wordBase64 = Base64.getEncoder().encodeToString(bytes);
        }

        return new ReportResponse(title, lines, totalQuantity, totalRevenue, format, wordBase64);
    }

    /** Compares each sale's {@link SalesRecord#getTimestamp()} against {@code day} in UTC, so results are deterministic regardless of server timezone. */
    private List<SalesRecord> filterByDay(List<SalesRecord> records, LocalDate day) {
        if (day == null) {
            return records;
        }
        List<SalesRecord> filtered = new ArrayList<>();
        for (SalesRecord record : records) {
            if (day.equals(record.getTimestamp().atZone(ZoneOffset.UTC).toLocalDate())) {
                filtered.add(record);
            }
        }
        return filtered;
    }

    /**
     * Case-insensitive on purpose: a branch id/SKU is effectively a code (unique regardless of
     * case), but a category name like "Tops" reads as an ordinary word — someone typing "tops" in
     * the Reports filter field getting back an empty table, with no hint why, looks like the
     * report is broken rather than like a typo.
     */
    private List<SalesRecord> filter(List<SalesRecord> records, ReportScope scope, String filterValue) {
        if (filterValue == null) {
            return records;
        }
        String trimmed = filterValue.trim();
        List<SalesRecord> filtered = new ArrayList<>();
        for (SalesRecord record : records) {
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
        String withFilter = filterValue != null ? base + " (" + filterValue + ")" : base;
        return day != null ? withFilter + " on " + day : withFilter;
    }
}
