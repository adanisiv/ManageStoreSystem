package managestore.server.repository;

import managestore.common.model.Employee;
import managestore.common.model.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the on-disk persistence layer itself (never exercised by the
 * service-level tests, which all use an in-memory fake): save() must survive
 * a full process restart (new repository instance re-reading the same file),
 * and the crash-safe write-to-temp-then-atomic-rename in persist() must not
 * leave a stray ".tmp" file behind or lose data.
 */
class JsonFileEmployeeRepositoryTest {

    @Test
    void savedEmployeeSurvivesReloadFromDisk(@TempDir Path dir) {
        Path file = dir.resolve("employees.json");
        Employee employee = new Employee("E1", "Dana Cohen", "123456789", "050-1111111", "ACC-1", "BRANCH-1", Role.CASHIER);

        new JsonFileEmployeeRepository(file).save(employee);

        // A brand-new repository instance, pointed at the same file, simulates the server restarting.
        JsonFileEmployeeRepository reloaded = new JsonFileEmployeeRepository(file);
        Optional<Employee> found = reloaded.findByEmployeeNumber("E1");
        assertTrue(found.isPresent());
        assertEquals("Dana Cohen", found.get().getFullName());
        assertEquals(Role.CASHIER, found.get().getRole());
    }

    @Test
    void multipleSavesAccumulateAndOverwriteByEmployeeNumber(@TempDir Path dir) {
        Path file = dir.resolve("employees.json");
        JsonFileEmployeeRepository repo = new JsonFileEmployeeRepository(file);

        repo.save(new Employee("E1", "Dana Cohen", "1", "050-1", "ACC-1", "BRANCH-1", Role.CASHIER));
        repo.save(new Employee("E2", "Roi Levi", "2", "050-2", "ACC-2", "BRANCH-1", Role.SELLER));
        repo.save(new Employee("E1", "Dana Cohen-Levi", "1", "050-1", "ACC-1", "BRANCH-2", Role.SHIFT_MANAGER));

        List<Employee> all = new JsonFileEmployeeRepository(file).findAll();
        assertEquals(2, all.size(), "re-saving E1 should update it in place, not add a duplicate");
        Employee updated = all.stream().filter(e -> e.getEmployeeNumber().equals("E1")).findFirst()
                .orElseThrow(() -> new AssertionError("E1 should still be present"));
        assertEquals("Dana Cohen-Levi", updated.getFullName());
        assertEquals(Role.SHIFT_MANAGER, updated.getRole());
    }

    @Test
    void persistDoesNotLeaveATemporaryFileBehind(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("employees.json");
        new JsonFileEmployeeRepository(file).save(
                new Employee("E1", "Dana Cohen", "1", "050-1", "ACC-1", "BRANCH-1", Role.CASHIER));

        assertTrue(Files.exists(file));
        assertFalse(Files.exists(dir.resolve("employees.json.tmp")),
                "the temp file used for the atomic rename should never remain after a successful save");
    }
}
