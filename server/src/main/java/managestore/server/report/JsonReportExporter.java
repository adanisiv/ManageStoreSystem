package managestore.server.report;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import managestore.common.protocol.ReportLineDto;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class JsonReportExporter implements ReportExporter {

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public byte[] export(String title, List<ReportLineDto> lines, int totalQuantity, double totalRevenue) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("title", title);
        json.put("lines", lines);
        json.put("totalQuantity", totalQuantity);
        json.put("totalRevenue", totalRevenue);
        return gson.toJson(json).getBytes(StandardCharsets.UTF_8);
    }
}
