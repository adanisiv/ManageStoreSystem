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
 * Builds a sales report as a real Word (.docx) file, using the Apache POI
 * library. The finished document has a title at the top, then a table with
 * the same report lines that {@link JsonReportExporter} would turn into
 * JSON, plus one extra row at the bottom with the totals.
 */
public class WordReportExporter implements ReportExporter {

    @Override
    public byte[] export(String title, List<ReportLineDto> lines, int totalQuantity, double totalRevenue) {
        // XWPFDocument is POI's object that represents a whole .docx file in
        // memory. Everything we add to it below (paragraphs, tables, ...)
        // becomes part of that document. try-with-resources closes it for us
        // when we are done, even if something throws.
        try (XWPFDocument document = new XWPFDocument()) {
            // Add the bold, centered title line first...
            writeTitle(document, title);
            // ...then the data table right below it.
            writeTable(document, lines, totalQuantity, totalRevenue);

            // Convert the whole document into the actual .docx file format
            // (which is really a zip file full of XML) and write those bytes
            // into an in-memory buffer we can return.
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to render Word report", e);
        }
    }

    private void writeTitle(XWPFDocument document, String title) {
        // Add a new, empty paragraph (like a new line) at the end of the document.
        XWPFParagraph titleParagraph = document.createParagraph();
        // Center this line horizontally on the page.
        titleParagraph.setAlignment(ParagraphAlignment.CENTER);
        // In Word, a "run" is a piece of text with its own formatting
        // (bold, size, etc). A paragraph needs at least one run before any
        // text will actually show up. Here we add the title text itself,
        // make it bold, and set its font size to 16.
        XWPFRun run = titleParagraph.createRun();
        run.setText(title);
        run.setBold(true);
        run.setFontSize(16);
    }

    private void writeTable(XWPFDocument document, List<ReportLineDto> lines, int totalQuantity, double totalRevenue) {
        // Create the table itself. It needs 3 columns (label, quantity,
        // revenue) and one row for each of: the header, every report line,
        // and the totals at the end. POI automatically fills every cell with
        // an empty paragraph, ready for us to put text into.
        XWPFTable table = document.createTable(lines.size() + 2, 3);

        // Row 0 is the header row with the column names.
        setRow(table.getRow(0), "Label", "Quantity Sold", "Revenue");
        // Rows 1 through lines.size() hold one report line each, in the same order as the input list.
        for (int i = 0; i < lines.size(); i++) {
            ReportLineDto line = lines.get(i);
            setRow(table.getRow(i + 1), line.getLabel(), String.valueOf(line.getQuantitySold()),
                    formatCurrency(line.getRevenue()));
        }
        // The last row shows the grand totals. We use the totals that were
        // already calculated and passed in, instead of adding up the lines again here.
        setRow(table.getRow(lines.size() + 1), "TOTAL", String.valueOf(totalQuantity), formatCurrency(totalRevenue));
    }

    private void setRow(XWPFTableRow row, String label, String quantity, String revenue) {
        // getCell(n) gets the cell that already exists at that column index.
        // setText() replaces whatever is in that cell with our plain text.
        row.getCell(0).setText(label);
        row.getCell(1).setText(quantity);
        row.getCell(2).setText(revenue);
    }

    private String formatCurrency(double amount) {
        // Always format the number the US way (a dot for the decimal point)
        // no matter what language or region the server is set to, and always
        // show exactly 2 digits after the decimal point.
        return String.format(Locale.US, "%.2f", amount);
    }
}
