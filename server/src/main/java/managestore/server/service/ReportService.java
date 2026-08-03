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
        List<SalesRecord> filtered = filter(records, scope, filterValue);
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
        String title = titleFor(scope, filterValue);

        String wordBase64 = null;
        if (format == ReportFormat.WORD) {
            byte[] bytes = wordExporter.export(title, lines, totalQuantity, totalRevenue);
            wordBase64 = Base64.getEncoder().encodeToString(bytes);
        }

        return new ReportResponse(title, lines, totalQuantity, totalRevenue, format, wordBase64);
    }

    private List<SalesRecord> filter(List<SalesRecord> records, ReportScope scope, String filterValue) {
        if (filterValue == null) {
            return records;
        }
        List<SalesRecord> filtered = new ArrayList<>();
        for (SalesRecord record : records) {
            if (filterValue.equals(keyFor(scope, record))) {
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

    private String titleFor(ReportScope scope, String filterValue) {
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
        return filterValue != null ? base + " (" + filterValue + ")" : base;
    }
}
