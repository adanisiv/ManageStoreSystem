package managestore.server.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {

    private final SessionManager sessionManager = SessionManager.getInstance();

    @AfterEach
    void tearDown() {
        // SessionManager is a process-wide singleton, so tests must clean up after themselves.
        sessionManager.logout("dana");
    }

    @Test
    void secondLoginForSameUsernameIsRejected() {
        assertTrue(sessionManager.tryLogin("dana", "session-A"));

        boolean secondAttempt = sessionManager.tryLogin("dana", "session-B");

        assertFalse(secondAttempt);
    }

    @Test
    void logoutFreesUpTheUsernameForAnotherLogin() {
        sessionManager.tryLogin("dana", "session-A");

        sessionManager.logout("dana");

        assertTrue(sessionManager.tryLogin("dana", "session-B"));
    }
}
