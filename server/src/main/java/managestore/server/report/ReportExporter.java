package managestore.server.report;

import managestore.common.protocol.ReportLineDto;

import java.util.List;

/**
 * Strategy pattern: {@link JsonReportExporter} and {@link WordReportExporter}
 * both implement this from the same report data, so a report can be produced
 * in either format without the caller knowing which.
 */
public interface ReportExporter {

    byte[] export(String title, List<ReportLineDto> lines, int totalQuantity, double totalRevenue);
}
