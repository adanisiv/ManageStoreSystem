package managestore.common.model;

import java.time.Instant;

/**
 * A single entry in the system log. {@code details} is a short human-readable
 * summary; for CHAT events it optionally holds the full transcript, per the
 * brief's "option to save the chat content" requirement.
 */
public class LogEvent {

    private final LogType type;
    private final String actor;
    private final String details;
    private final Instant timestamp;

    public LogEvent(LogType type, String actor, String details) {
        this.type = type;
        this.actor = actor;
        this.details = details;
        this.timestamp = Instant.now();
    }

    public LogType getType() {
        return type;
    }

    public String getActor() {
        return actor;
    }

    public String getDetails() {
        return details;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "[" + timestamp + "] " + type + " by " + actor + ": " + details;
    }
}
