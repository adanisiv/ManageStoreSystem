package managestore.common.protocol;

import java.util.List;

/**
 * A report is always delivered as JSON. {@code wordFileBase64} is only
 * filled in when the request asked for {@link ReportFormat#WORD}.
 *
 * <p>In that case, the server also renders the same data into a real
 * .docx file (using Apache POI). The file's bytes are then Base64-encoded
 * into this field, so they can travel over the same line-based JSON
 * protocol as everything else. The client can then decode them back into
 * a real Word file.
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
