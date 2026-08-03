package managestore.server.service;

import managestore.common.model.LogEvent;
import managestore.common.model.LogType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Singleton pattern, same reasoning as {@link SessionManager}: the whole
 * point of a system log is one shared record everyone writes to. Every
 * service that produces a loggable action (customer/employee registration,
 * sales, chat) calls {@code LogManager.getInstance().log(...)} directly
 * rather than threading a LogManager reference through every constructor.
 *
 * <p>In-memory only, same deliberate simplification as {@link
 * managestore.server.repository.SalesRecordRepository} — history resets on
 * server restart, which is an acceptable scope cut for this project (a real
 * deployment would flush entries to a file or DB).
 */
public final class LogManager {

    private static final LogManager INSTANCE = new LogManager();

    private final List<LogEvent> events = new CopyOnWriteArrayList<>();

    private LogManager() {
    }

    public static LogManager getInstance() {
        return INSTANCE;
    }

    public void log(LogEvent event) {
        events.add(event);
    }

    public List<LogEvent> all() {
        return new ArrayList<>(events);
    }

    public List<LogEvent> byType(LogType type) {
        List<LogEvent> filtered = new ArrayList<>();
        for (LogEvent event : events) {
            if (event.getType() == type) {
                filtered.add(event);
            }
        }
        return filtered;
    }

    /** Test-only: LogManager is a process-wide singleton, so tests must clean up after themselves. */
    public void clear() {
        events.clear();
    }
}
