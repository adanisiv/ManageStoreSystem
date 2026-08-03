package managestore.common.protocol;

import java.util.List;

/**
 * Report delivery is JSON either way (this whole object is sent as JSON per
 * the brief's requirement) — {@code wordFileBase64} is populated only when
 * the request asked for {@link ReportFormat#WORD}: the server renders the
 * same data into an actual .docx (via Apache POI) and Base64-encodes the
 * file bytes into this field so it can travel over the same line-based JSON
 * protocol as everything else, letting the client save it as a real Word file.
 */
public class ReportResponse {

    private final String title;
    private final List<ReportLineDto> lines;
    private final int totalQuantity;
    private final double totalRevenue;
    private final ReportFormat format;
    private final String wordFileBase64;

    public ReportResponse(String title, List<ReportLineDto> lines, int totalQuantity, double totalRevenue,
                           ReportFormat format, String wordFileBase64) {
        this.title = title;
        this.lines = lines;
        this.totalQuantity = totalQuantity;
        this.totalRevenue = totalRevenue;
        this.format = format;
        this.wordFileBase64 = wordFileBase64;
    }

    public String getTitle() {
        return title;
    }

    public List<ReportLineDto> getLines() {
        return lines;
    }

    public int getTotalQuantity() {
        return totalQuantity;
    }

    public double getTotalRevenue() {
        return totalRevenue;
    }

    public ReportFormat getFormat() {
        return format;
    }

    public String getWordFileBase64() {
        return wordFileBase64;
    }
}
