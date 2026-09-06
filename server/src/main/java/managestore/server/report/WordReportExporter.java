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
 * Renders a sales report as an actual .docx via Apache POI — a title, then a
 * table of the same lines {@link JsonReportExporter} would serialize, plus a
 * totals row.
 */
public class WordReportExporter implements ReportExporter {

    @Override
    public byte[] export(String title, List<ReportLineDto> lines, int totalQuantity, double totalRevenue) {
        // XWPFDocument is POI's in-memory model of a .docx file — everything
        // added to it below (paragraphs, tables, ...) becomes part of that
        // document. try-with-resources closes/releases it when done.
        try (XWPFDocument document = new XWPFDocument()) {
            // Add the bold centered heading first...
            writeTitle(document, title);
            // ...then the data table right below it.
            writeTable(document, lines, totalQuantity, totalRevenue);

            // Serialize the whole in-memory document out to the .docx binary
            // format (a zip of XML parts) into an in-memory byte buffer.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render Word report", e);
        }
    }

    private void writeTitle(XWPFDocument document, String title) {
        // Add a new, empty paragraph at the end of the document.
        XWPFParagraph titleParagraph = document.createParagraph();
        // Center the paragraph horizontally on the page.
        titleParagraph.setAlignment(ParagraphAlignment.CENTER);
        // A "run" is a span of text sharing one set of formatting; a
        // paragraph needs at least one run to actually display any text.
        XWPFRun run = titleParagraph.createRun();
        run.setText(title);
        run.setBold(true);
        run.setFontSize(16);
    }

    private void writeTable(XWPFDocument document, List<ReportLineDto> lines, int totalQuantity, double totalRevenue) {
        // Create the table with one header row, one row per report line, and
        // one totals row at the end, each with 3 columns (label/qty/revenue).
        // POI pre-fills every cell with an empty paragraph automatically.
        XWPFTable table = document.createTable(lines.size() + 2, 3);

        // Row 0: column headers.
        setRow(table.getRow(0), "Label", "Quantity Sold", "Revenue");
        // Rows 1..lines.size(): one row per report line, in the same order as the input list.
        for (int i = 0; i < lines.size(); i++) {
            ReportLineDto line = lines.get(i);
            setRow(table.getRow(i + 1), line.getLabel(), String.valueOf(line.getQuantitySold()),
                    formatCurrency(line.getRevenue()));
        }
        // Final row: grand totals, using the precomputed values passed in
        // rather than re-summing the lines here.
        setRow(table.getRow(lines.size() + 1), "TOTAL", String.valueOf(totalQuantity), formatCurrency(totalRevenue));
    }

    private void setRow(XWPFTableRow row, String label, String quantity, String revenue) {
        // getCell(n) fetches the existing (already created) cell at that
        // column index; setText() replaces its contents with plain text.
        row.getCell(0).setText(label);
        row.getCell(1).setText(quantity);
        row.getCell(2).setText(revenue);
    }

    private String formatCurrency(double amount) {
        // Force US-style formatting (dot as decimal separator) regardless of
        // the server's default locale, and always show exactly 2 decimal places.
        return String.format(Locale.US, "%.2f", amount);
    }
}
