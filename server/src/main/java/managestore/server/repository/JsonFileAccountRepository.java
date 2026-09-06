package managestore.server.repository;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;
import managestore.server.model.Account;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Stores login credentials the same way {@link JsonFileEmployeeRepository} stores employees: as a JSON file. */
public class JsonFileAccountRepository implements AccountRepository {

    private static final Type LIST_TYPE = new TypeToken<List<Account>>() {
    }.getType();

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Account> byUsername = new ConcurrentHashMap<>();

    public JsonFileAccountRepository(Path file) {
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
            // Parse the whole JSON array into a List<Account> in one call.
            List<Account> accounts = gson.fromJson(reader, LIST_TYPE);
            if (accounts != null) {
                // Rebuild the lookup map, one entry per account, keyed by username.
                for (Account account : accounts) {
                    byUsername.put(account.getUsername(), account);
                }
            }
        } catch (IOException e) {
            // If we cannot read the data file, treat it as a startup failure
            // instead of asking callers to handle it. So wrap it in an
            // unchecked exception, since IOException is a checked one.
            throw new IllegalStateException("Failed to load " + file, e);
        } catch (JsonParseException e) {
            // The file exists and could be read, but the text inside it isn't valid JSON.
            // Gson reports this as its own unchecked exception, not an IOException, so it
            // needs its own catch to get the same clean failure message instead of a raw
            // Gson stack trace with no context about which file caused it.
            throw new IllegalStateException("Failed to load " + file + " (invalid JSON)", e);
        }
    }

    /**
     * Saves data the crash-safe way, the same as {@link JsonFileEmployeeRepository#persist()}:
     * write everything to a new temp file first, then swap it in for the real file in one
     * step. This way a crash or power loss during the write can never leave the accounts
     * file half-written. Whoever reads it next sees either the complete old file or the
     * complete new one, never something in between.
     */
    private synchronized void persist() {
        try {
            // Make sure the folder the data file lives in actually exists
            // before we try to write into it (e.g. on first run).
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            // Build the path for a temporary sibling file, e.g. "accounts.json.tmp".
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            // Write the full, current list of all accounts to the temp file
            // first. The real file is not touched yet.
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(new java.util.ArrayList<>(byUsername.values()), LIST_TYPE, writer);
            }
            // Only once the temp file is fully written, swap it in for the
            // real file. REPLACE_EXISTING lets this overwrite the existing
            // accounts file. ATOMIC_MOVE makes the swap happen as one single
            // step, so there is no moment where the file is only half-written.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Something failed along the way: making the folder, writing the
            // temp file, or moving it. Treat this as a fatal error instead of
            // silently losing data, by wrapping it in an unchecked exception.
            throw new IllegalStateException("Failed to save " + file, e);
        }
    }

    @Override
    public Optional<Account> findByUsername(String username) {
        return Optional.ofNullable(byUsername.get(username));
    }

    @Override
    public synchronized void save(Account account) {
        // Add or replace the entry in the in-memory map, keyed by username.
        byUsername.put(account.getUsername(), account);
        // Then immediately rewrite the whole JSON file, so this change is still there after a restart.
        persist();
    }

    @Override
    public synchronized void deleteByEmployeeNumber(String employeeNumber) {
        // Accounts are keyed by username, not employee number. So first scan
        // all accounts to find the one that belongs to this employee, and
        // read its username. If none match, orElse(null) gives us null.
        String username = byUsername.values().stream()
                .filter(account -> account.getEmployeeNumber().equals(employeeNumber))
                .map(Account::getUsername)
                .findFirst().orElse(null);
        // Only rewrite the file if we actually found and removed a matching account.
        if (username != null && byUsername.remove(username) != null) {
            persist();
        }
    }
}
