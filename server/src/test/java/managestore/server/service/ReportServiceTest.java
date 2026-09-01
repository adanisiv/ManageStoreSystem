package managestore.server.service;

import managestore.common.model.Branch;
import managestore.common.model.NewCustomer;
import managestore.common.model.Product;
import managestore.common.model.PurchaseResult;
import managestore.common.model.SalesRecord;
import managestore.common.model.StoreChain;
import managestore.common.protocol.ReportFormat;
import managestore.common.protocol.ReportLineDto;
import managestore.common.protocol.ReportResponse;
import managestore.common.protocol.ReportScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReportServiceTest {

    private StoreChain storeChain;
    private ReportService reportService;
    private List<SalesRecord> records;

    private final Product shirt = new Product("SKU-SHIRT", "Shirt", "Tops", 100.0);
    private final Product hat = new Product("SKU-HAT", "Hat", "Accessories", 50.0);
    private final NewCustomer customer = new NewCustomer("1", "Dana", "050-0");

    @BeforeEach
    void setUp() {
        storeChain = new StoreChain();
        storeChain.addBranch(new Branch("B1", "Downtown"));
        storeChain.addBranch(new Branch("B2", "Uptown"));
        reportService = new ReportService(storeChain, (title, lines, qty, revenue) -> "fake-docx-bytes".getBytes());

        records = new ArrayList<>();
        records.add(sale("B1", shirt, 2, 200.0));
        records.add(sale("B1", hat, 1, 50.0));
        records.add(sale("B2", shirt, 3, 300.0));
    }

    private SalesRecord sale(String branchId, Product product, int quantity, double amount) {
        PurchaseResult result = new PurchaseResult(customer, product, quantity, amount, amount);
        return new SalesRecord(branchId, result);
    }

    private SalesRecord saleOn(String branchId, Product product, int quantity, double amount, LocalDate day) {
        PurchaseResult result = new PurchaseResult(customer, product, quantity, amount, amount);
        return new SalesRecord(branchId, result, day.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600));
    }

    @Test
    void groupsByBranchAndResolvesBranchName() {
        ReportResponse response = reportService.generate(records, ReportScope.BRANCH, null, ReportFormat.JSON);

        assertEquals(2, response.getLines().size());
        assertEquals(6, response.getTotalQuantity());
        assertEquals(550.0, response.getTotalRevenue());
        ReportLineDto downtown = findByLabel(response.getLines(), "Downtown");
        assertEquals(3, downtown.getQuantitySold());
        assertEquals(250.0, downtown.getRevenue());
    }

    @Test
    void groupsByProduct() {
        ReportResponse response = reportService.generate(records, ReportScope.PRODUCT, null, ReportFormat.JSON);

        assertEquals(2, response.getLines().size());
        ReportLineDto shirtLine = findByLabelContaining(response.getLines(), "Shirt");
        assertEquals(5, shirtLine.getQuantitySold());
        assertEquals(500.0, shirtLine.getRevenue());
    }

    @Test
    void groupsByCategory() {
        ReportResponse response = reportService.generate(records, ReportScope.CATEGORY, null, ReportFormat.JSON);

        assertEquals(2, response.getLines().size());
        ReportLineDto tops = findByLabel(response.getLines(), "Tops");
        assertEquals(5, tops.getQuantitySold());
    }

    @Test
    void filterValueNarrowsToASingleGroup() {
        ReportResponse response = reportService.generate(records, ReportScope.BRANCH, "B1", ReportFormat.JSON);

        assertEquals(1, response.getLines().size());
        assertEquals(3, response.getTotalQuantity());
    }

    @Test
    void allScopeProducesOneGrandTotalLine() {
        ReportResponse response = reportService.generate(records, ReportScope.ALL, null, ReportFormat.JSON);

        assertEquals(1, response.getLines().size());
        assertEquals(6, response.getTotalQuantity());
        assertEquals(550.0, response.getTotalRevenue());
    }

    @Test
    void dayFilterNarrowsToOnlySalesOnThatCalendarDay() {
        List<SalesRecord> mixedDays = new ArrayList<>();
        LocalDate today = LocalDate.of(2026, 8, 17);
        LocalDate yesterday = today.minusDays(1);
        mixedDays.add(saleOn("B1", shirt, 2, 200.0, today));
        mixedDays.add(saleOn("B1", hat, 1, 50.0, yesterday));
        mixedDays.add(saleOn("B2", shirt, 3, 300.0, today));

        ReportResponse response = reportService.generate(mixedDays, ReportScope.ALL, null, ReportFormat.JSON, today);

        assertEquals(1, response.getLines().size());
        assertEquals(5, response.getTotalQuantity(), "should only count the two sales made on 'today', not yesterday's hat");
        assertEquals(500.0, response.getTotalRevenue());
    }

    @Test
    void noDayFilterIncludesSalesFromEveryDay() {
        List<SalesRecord> mixedDays = new ArrayList<>();
        LocalDate today = LocalDate.of(2026, 8, 17);
        mixedDays.add(saleOn("B1", shirt, 2, 200.0, today));
        mixedDays.add(saleOn("B1", hat, 1, 50.0, today.minusDays(1)));

        ReportResponse response = reportService.generate(mixedDays, ReportScope.ALL, null, ReportFormat.JSON, null);

        assertEquals(3, response.getTotalQuantity());
    }

    @Test
    void dayFilterTitleMentionsTheDate() {
        LocalDate today = LocalDate.of(2026, 8, 17);
        ReportResponse response = reportService.generate(records, ReportScope.ALL, null, ReportFormat.JSON, today);

        assertTrue(response.getTitle().contains("2026-08-17"));
    }

    @Test
    void jsonFormatLeavesWordFileEmpty() {
        ReportResponse response = reportService.generate(records, ReportScope.ALL, null, ReportFormat.JSON);

        assertNull(response.getWordFileBase64());
    }

    @Test
    void wordFormatPopulatesBase64EncodedFile() {
        ReportResponse response = reportService.generate(records, ReportScope.ALL, null, ReportFormat.WORD);

        assertNotNull(response.getWordFileBase64());
        assertTrue(response.getWordFileBase64().length() > 0);
    }

    private ReportLineDto findByLabel(List<ReportLineDto> lines, String label) {
        return lines.stream().filter(l -> l.getLabel().equals(label)).findFirst()
                .orElseThrow(() -> new AssertionError("No line labeled " + label));
    }

    private ReportLineDto findByLabelContaining(List<ReportLineDto> lines, String substring) {
        return lines.stream().filter(l -> l.getLabel().contains(substring)).findFirst()
                .orElseThrow(() -> new AssertionError("No line containing " + substring));
    }
}
