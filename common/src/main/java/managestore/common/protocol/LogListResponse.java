package managestore.common.protocol;

import java.util.List;

public class LogListResponse {

    private final List<LogEventDto> events;

    public LogListResponse(List<LogEventDto> events) {
        this.events = events;
    }

    public List<LogEventDto> getEvents() {
        return events;
    }
}
