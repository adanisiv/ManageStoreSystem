package managestore.common.protocol;

public class LogEventDto {

    private final String type;
    private final String actor;
    private final String details;
    private final long timestampEpochMillis;

    public LogEventDto(String type, String actor, String details, long timestampEpochMillis) {
        this.type = type;
        this.actor = actor;
        this.details = details;
        this.timestampEpochMillis = timestampEpochMillis;
    }

    public String getType() {
        return type;
    }

    public String getActor() {
        return actor;
    }

    public String getDetails() {
        return details;
    }

    public long getTimestampEpochMillis() {
        return timestampEpochMillis;
    }
}
