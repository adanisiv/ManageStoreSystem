package managestore.server.repository;

import managestore.server.model.Account;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Same coverage as {@link JsonFileEmployeeRepositoryTest}, for login credentials. */
class JsonFileAccountRepositoryTest {

    @Test
    void savedAccountSurvivesReloadFromDisk(@TempDir Path dir) {
        Path file = dir.resolve("accounts.json");
        Account account = new Account("E1", "dana", "hash123", "salt123");

        new JsonFileAccountRepository(file).save(account);

        JsonFileAccountRepository reloaded = new JsonFileAccountRepository(file);
        Optional<Account> found = reloaded.findByUsername("dana");
        assertTrue(found.isPresent());
        assertEquals("E1", found.get().getEmployeeNumber());
        assertEquals("hash123", found.get().getPasswordHash());
        assertEquals("salt123", found.get().getPasswordSalt());
    }

    @Test
    void persistDoesNotLeaveATemporaryFileBehind(@TempDir Path dir) {
        Path file = dir.resolve("accounts.json");
        new JsonFileAccountRepository(file).save(new Account("E1", "dana", "hash123", "salt123"));

        assertTrue(Files.exists(file));
        assertFalse(Files.exists(dir.resolve("accounts.json.tmp")),
                "the temp file used for the atomic rename should never remain after a successful save");
    }
}
