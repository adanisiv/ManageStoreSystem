package managestore.server.report;

import managestore.common.protocol.ReportLineDto;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Locale;

/**
 * Renders a sales report as an actual .docx via Apache POI (the "self-study"
 * library the brief calls out) — a title, then a table of the same lines
 * {@link JsonReportExporter} would serialize, plus a totals row.
 */
public class WordReportExporter implements ReportExporter {

    @Override
    public byte[] export(String title, List<ReportLineDto> lines, int totalQuantity, double totalRevenue) {
        try (XWPFDocument document = new XWPFDocument()) {
            writeTitle(document, title);
            writeTable(document, lines, totalQuantity, totalRevenue);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render Word report", e);
        }
    }

    private void writeTitle(XWPFDocument document, String title) {
        XWPFParagraph titleParagraph = document.createParagraph();
        titleParagraph.setAlignment(ParagraphAlignment.CENTER);
        XWPFRun run = titleParagraph.createRun();
        run.setText(title);
        run.setBold(true);
        run.setFontSize(16);
    }

    private void writeTable(XWPFDocument document, List<ReportLineDto> lines, int totalQuantity, double totalRevenue) {
        XWPFTable table = document.createTable(lines.size() + 2, 3);

        setRow(table.getRow(0), "Label", "Quantity Sold", "Revenue");
        for (int i = 0; i < lines.size(); i++) {
            ReportLineDto line = lines.get(i);
            setRow(table.getRow(i + 1), line.getLabel(), String.valueOf(line.getQuantitySold()),
                    formatCurrency(line.getRevenue()));
        }
        setRow(table.getRow(lines.size() + 1), "TOTAL", String.valueOf(totalQuantity), formatCurrency(totalRevenue));
    }

    private void setRow(XWPFTableRow row, String label, String quantity, String revenue) {
        row.getCell(0).setText(label);
        row.getCell(1).setText(quantity);
        row.getCell(2).setText(revenue);
    }

    private String formatCurrency(double amount) {
        return String.format(Locale.US, "%.2f", amount);
    }
}
