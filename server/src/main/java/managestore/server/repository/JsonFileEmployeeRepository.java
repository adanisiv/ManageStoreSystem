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
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<Employee> employees = gson.fromJson(reader, LIST_TYPE);
            if (employees != null) {
                for (Employee employee : employees) {
                    byEmployeeNumber.put(employee.getEmployeeNumber(), employee);
                }
            }
        } catch (IOException e) {
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
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(new java.util.ArrayList<>(byEmployeeNumber.values()), LIST_TYPE, writer);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save " + file, e);
        }
    }

    @Override
    public Optional<Employee> findByEmployeeNumber(String employeeNumber) {
        return Optional.ofNullable(byEmployeeNumber.get(employeeNumber));
    }

    @Override
    public List<Employee> findAll() {
        return new java.util.ArrayList<>(new LinkedHashMap<>(byEmployeeNumber).values());
    }

    @Override
    public synchronized void save(Employee employee) {
        byEmployeeNumber.put(employee.getEmployeeNumber(), employee);
        persist();
    }

    @Override
    public synchronized void delete(String employeeNumber) {
        if (byEmployeeNumber.remove(employeeNumber) != null) {
            persist();
        }
    }
}
