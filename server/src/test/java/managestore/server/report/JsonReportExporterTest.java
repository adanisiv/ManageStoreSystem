package managestore.server.report;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import managestore.common.protocol.ReportLineDto;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonReportExporterTest {

    private final JsonReportExporter exporter = new JsonReportExporter();
    private final Gson gson = new Gson();

    @Test
    void producesValidJsonWithExpectedFields() {
        byte[] bytes = exporter.export("Sales Report",
                Collections.singletonList(new ReportLineDto("Shirt", 2, 200.0)), 2, 200.0);

        JsonObject json = gson.fromJson(new String(bytes, StandardCharsets.UTF_8), JsonObject.class);

        assertEquals("Sales Report", json.get("title").getAsString());
        assertEquals(2, json.get("totalQuantity").getAsInt());
        assertEquals(200.0, json.get("totalRevenue").getAsDouble());
        assertEquals(1, json.getAsJsonArray("lines").size());
    }
}
