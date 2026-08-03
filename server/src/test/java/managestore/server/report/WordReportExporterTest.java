package managestore.server.report;

import managestore.common.protocol.ReportLineDto;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WordReportExporterTest {

    private final WordReportExporter exporter = new WordReportExporter();

    @Test
    void producesARealDocxWithTitleAndDataRows() throws IOException {
        List<ReportLineDto> lines = Arrays.asList(
                new ReportLineDto("Downtown Branch", 5, 500.0),
                new ReportLineDto("Uptown Branch", 3, 300.0));

        byte[] bytes = exporter.export("Sales by Branch", lines, 8, 800.0);

        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            XWPFParagraph titleParagraph = document.getParagraphs().get(0);
            assertTrue(titleParagraph.getText().contains("Sales by Branch"));

            XWPFTable table = document.getTables().get(0);
            // header + 2 data rows + totals row
            assertEquals(4, table.getRows().size());
            assertEquals("Downtown Branch", table.getRow(1).getCell(0).getText());
            assertEquals("5", table.getRow(1).getCell(1).getText());
            assertEquals("TOTAL", table.getRow(3).getCell(0).getText());
            assertEquals("8", table.getRow(3).getCell(1).getText());
        }
    }
}
