package managestore.server.report;

import managestore.common.protocol.ReportLineDto;

import java.util.List;

/**
 * Strategy pattern: {@link JsonReportExporter} and {@link WordReportExporter}
 * both implement this from the same report data, satisfying the brief's
 * requirement that reports can be produced in either format.
 */
public interface ReportExporter {

    byte[] export(String title, List<ReportLineDto> lines, int totalQuantity, double totalRevenue);
}
