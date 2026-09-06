package managestore.server.repository;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
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
 * Stores all employees in one JSON file (employees.json). The whole file is
 * loaded into memory when the server starts, and rewritten every time
 * something is saved. This class implements the {@link EmployeeRepository}
 * interface, so it could later be swapped for a real database without
 * changing any code that calls it.
 */
public class JsonFileEmployeeRepository implements EmployeeRepository {

    private static final Type LIST_TYPE = new TypeToken<List<Employee>>() {
    }.getType();

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Employee> byEmployeeNumber = new ConcurrentHashMap<>();

    public JsonFileEmployeeRepository(Path file) {
        this.file = file;
        // Load whatever is on disk right away, so the repository can answer
        // queries as soon as it is created.
        load();
    }

    private synchronized void load() {
        // First run: the file does not exist yet. That is not an error —
        // just start with an empty map instead.
        if (!Files.exists(file)) {
            return;
        }
        // Open the file as UTF-8 text. try-with-resources closes it for us
        // when the block ends, even if something below throws.
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            // Parse the whole JSON array into a List<Employee> in one call.
            List<Employee> employees = gson.fromJson(reader, LIST_TYPE);
            if (employees != null) {
                // Rebuild the lookup map, one entry per employee, keyed by employee number.
                for (Employee employee : employees) {
                    byEmployeeNumber.put(employee.getEmployeeNumber(), employee);
                }
            }
        } catch (IOException e) {
            // If we cannot read the data file, treat it as a startup failure
            // instead of asking callers to handle it. So wrap it in an
            // unchecked exception, since IOException is a checked one.
            throw new IllegalStateException("Failed to load " + file, e);
        } catch (JsonParseException e) {
            // The file exists and could be read, but the text inside it isn't valid JSON —
            // for example a hand-edited file with a missing comma, or a half-written file
            // left over from a crash. Gson reports this as its own unchecked exception, not
            // an IOException, so it needs its own catch here to get the same clean failure
            // message as above instead of a raw Gson stack trace with no context.
            throw new IllegalStateException("Failed to load " + file + " (invalid JSON)", e);
        }
    }

    /**
     * Saves data the crash-safe way: write everything to a new temp file
     * first, then swap it in for the real file in one step. Without this,
     * a crash or power loss partway through writing could leave
     * employees.json half-written and corrupted. With this approach,
     * whoever reads the file next always sees either the complete old
     * version or the complete new version, never something in between.
     */
    private synchronized void persist() {
        try {
            // Make sure the folder the data file lives in actually exists
            // before we try to write into it (e.g. on first run).
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            // Build the path for a temporary sibling file, e.g. "employees.json.tmp".
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            // Write the full, current list of all employees to the temp file
            // first. The real file is not touched yet.
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(new java.util.ArrayList<>(byEmployeeNumber.values()), LIST_TYPE, writer);
            }
            // Only once the temp file is fully written, swap it in for the
            // real file. REPLACE_EXISTING lets this overwrite employees.json.
            // ATOMIC_MOVE makes the swap happen as one single step, so there
            // is no moment where the file is only half-written.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Something failed along the way: making the folder, writing the
            // temp file, or moving it. Treat this as a fatal error instead of
            // silently losing data, by wrapping it in an unchecked exception.
            throw new IllegalStateException("Failed to save " + file, e);
        }
    }

    @Override
    public Optional<Employee> findByEmployeeNumber(String employeeNumber) {
        return Optional.ofNullable(byEmployeeNumber.get(employeeNumber));
    }

    @Override
    public List<Employee> findAll() {
        // Copy into a LinkedHashMap first to freeze the current order, then
        // into an ArrayList. This way the caller gets their own independent
        // list, which will not change even if another thread updates
        // byEmployeeNumber afterwards.
        return new java.util.ArrayList<>(new LinkedHashMap<>(byEmployeeNumber).values());
    }

    @Override
    public synchronized void save(Employee employee) {
        // Add or replace the entry in the in-memory map, keyed by employee number.
        byEmployeeNumber.put(employee.getEmployeeNumber(), employee);
        // Then immediately rewrite the whole JSON file, so this change is still there after a restart.
        persist();
    }

    @Override
    public synchronized void delete(String employeeNumber) {
        // remove() gives back the value it removed, or null if that key was not there.
        // Only rewrite the file on disk if something was actually removed.
        if (byEmployeeNumber.remove(employeeNumber) != null) {
            persist();
        }
    }
}
