package managestore.server.service;

import managestore.common.model.LogEvent;
import managestore.common.model.LogType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LogManagerTest {

    private final LogManager logManager = LogManager.getInstance();

    @BeforeEach
    void setUp() {
        // LogManager is a process-wide singleton other test classes also write to (surefire runs
        // classes in one JVM by default) — clear on both ends so run order can't leak state in.
        logManager.clear();
    }

    @AfterEach
    void tearDown() {
        logManager.clear();
    }

    @Test
    void logsAreRecordedAndFilterableByType() {
        logManager.log(new LogEvent(LogType.SALE, "E1", "sold something"));
        logManager.log(new LogEvent(LogType.CUSTOMER_REGISTERED, "E1", "added a customer"));
        logManager.log(new LogEvent(LogType.SALE, "E2", "sold something else"));

        assertEquals(3, logManager.all().size());
        assertEquals(2, logManager.byType(LogType.SALE).size());
        assertEquals(1, logManager.byType(LogType.CUSTOMER_REGISTERED).size());
    }
}
