package managestore.server.repository;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import managestore.common.model.Employee;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Employees persisted as a single JSON array file (employees.json), loaded
 * fully into memory at startup and rewritten on every save. Sits behind the
 * {@link EmployeeRepository} interface so a real database could replace it
 * later without touching any caller.
 */
public class JsonFileEmployeeRepository implements EmployeeRepository {

    private static final Type LIST_TYPE = new TypeToken<List<Employee>>() {
    }.getType();

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Employee> byEmployeeNumber = new ConcurrentHashMap<>();

    public JsonFileEmployeeRepository(Path file) {
        this.file = file;
        // Populate the in-memory map from disk immediately, so the
        // repository is ready to answer queries as soon as it's constructed.
        load();
    }

    private synchronized void load() {
        // Nothing to load yet (first run, file never created) — start with an
        // empty in-memory map instead of treating a missing file as an error.
        if (!Files.exists(file)) {
            return;
        }
        // Open the file for reading as UTF-8 text; try-with-resources closes
        // it automatically once the block ends, even if an exception is thrown.
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // Parse the whole JSON array into a List<Employee> in one call.
            List<Employee> employees = gson.fromJson(reader, LIST_TYPE);
            if (employees != null) {
                // Rebuild the lookup map keyed by employee number, one entry per employee.
                for (Employee employee : employees) {
                    byEmployeeNumber.put(employee.getEmployeeNumber(), employee);
                }
            }
        } catch (IOException e) {
            // Wrap the checked IOException in an unchecked one — a failure to
            // read the data file is treated as fatal to startup, not something
            // callers are expected to recover from.
            throw new IllegalStateException("Failed to load " + file, e);
        }
    }

    /**
     * Writes to a sibling temp file and atomically renames it over the real
     * file, so a crash or power loss mid-write can never leave employees.json
     * half-written/corrupted — readers only ever see the old complete
     * version or the new complete version, never a partial one.
     */
    private synchronized void persist() {
        try {
            // Make sure the folder the data file lives in actually exists
            // before trying to write into it (e.g. on first run).
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            // Build the path for a temporary sibling file, e.g. "employees.json.tmp".
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            // Write the FULL current snapshot of all employees to the temp
            // file first — the real file is not touched yet at this point.
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(new java.util.ArrayList<>(byEmployeeNumber.values()), LIST_TYPE, writer);
            }
            // Only once the temp file is fully and successfully written, swap
            // it in for the real file with a single atomic filesystem move.
            // REPLACE_EXISTING allows overwriting employees.json, and
            // ATOMIC_MOVE guarantees the swap happens as one indivisible step
            // — there is no in-between state where the file is half-written.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Any failure along the way (can't create the folder, can't
            // write the temp file, can't move it) is treated as fatal —
            // wrap it in an unchecked exception rather than silently losing data.
            throw new IllegalStateException("Failed to save " + file, e);
        }
    }

    @Override
    public Optional<Employee> findByEmployeeNumber(String employeeNumber) {
        return Optional.ofNullable(byEmployeeNumber.get(employeeNumber));
    }

    @Override
    public List<Employee> findAll() {
        // Copy into a LinkedHashMap first to snapshot a stable iteration
        // order, then into an ArrayList — so callers get an independent list
        // that won't change if another thread modifies byEmployeeNumber afterwards.
        return new java.util.ArrayList<>(new LinkedHashMap<>(byEmployeeNumber).values());
    }

    @Override
    public synchronized void save(Employee employee) {
        // Upsert into the in-memory map keyed by employee number...
        byEmployeeNumber.put(employee.getEmployeeNumber(), employee);
        // ...then immediately rewrite the whole JSON file so the change survives a restart.
        persist();
    }

    @Override
    public synchronized void delete(String employeeNumber) {
        // remove() returns the removed value, or null if that key wasn't present.
        // Only rewrite the file on disk if something actually changed.
        if (byEmployeeNumber.remove(employeeNumber) != null) {
            persist();
        }
    }
}
