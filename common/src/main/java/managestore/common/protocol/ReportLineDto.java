package managestore.common.protocol;

/** One grouped row of a sales report, e.g. one branch's totals, or one product's totals. */
public class ReportLineDto {

    private final String label;
    private final int quantitySold;
    private final double revenue;

    public ReportLineDto(String label, int quantitySold, double revenue) {
        this.label = label;
        this.quantitySold = quantitySold;
        this.revenue = revenue;
    }

    public String getLabel() {
        return label;
    }

    public int getQuantitySold() {
        return quantitySold;
    }

    public double getRevenue() {
        return revenue;
    }
}
