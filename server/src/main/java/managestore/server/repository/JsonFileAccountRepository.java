package managestore.server.repository;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
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

/** Same JSON-file-backed approach as {@link JsonFileEmployeeRepository}, for login credentials. */
public class JsonFileAccountRepository implements AccountRepository {

    private static final Type LIST_TYPE = new TypeToken<List<Account>>() {
    }.getType();

    private final Path file;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Map<String, Account> byUsername = new ConcurrentHashMap<>();

    public JsonFileAccountRepository(Path file) {
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
            // Parse the whole JSON array into a List<Account> in one call.
            List<Account> accounts = gson.fromJson(reader, LIST_TYPE);
            if (accounts != null) {
                // Rebuild the lookup map keyed by username, one entry per account.
                for (Account account : accounts) {
                    byUsername.put(account.getUsername(), account);
                }
            }
        } catch (IOException e) {
            // Wrap the checked IOException in an unchecked one — a failure to
            // read the data file is treated as fatal to startup, not something
            // callers are expected to recover from.
            throw new IllegalStateException("Failed to load " + file, e);
        }
    }

    /** Same crash-safe write-then-atomic-rename approach as {@link JsonFileEmployeeRepository#persist()}. */
    private synchronized void persist() {
        try {
            // Make sure the folder the data file lives in actually exists
            // before trying to write into it (e.g. on first run).
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            // Build the path for a temporary sibling file, e.g. "accounts.json.tmp".
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            // Write the FULL current snapshot of all accounts to the temp
            // file first — the real file is not touched yet at this point.
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(new java.util.ArrayList<>(byUsername.values()), LIST_TYPE, writer);
            }
            // Only once the temp file is fully and successfully written, swap
            // it in for the real file with a single atomic filesystem move.
            // REPLACE_EXISTING allows overwriting the existing accounts file,
            // and ATOMIC_MOVE guarantees the swap happens as one indivisible
            // step — there is no in-between state where the file is half-written.
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            // Any failure along the way (can't create the folder, can't
            // write the temp file, can't move it) is treated as fatal —
            // wrap it in an unchecked exception rather than silently losing data.
            throw new IllegalStateException("Failed to save " + file, e);
        }
    }

    @Override
    public Optional<Account> findByUsername(String username) {
        return Optional.ofNullable(byUsername.get(username));
    }

    @Override
    public synchronized void save(Account account) {
        // Upsert into the in-memory map keyed by username...
        byUsername.put(account.getUsername(), account);
        // ...then immediately rewrite the whole JSON file so the change survives a restart.
        persist();
    }

    @Override
    public synchronized void deleteByEmployeeNumber(String employeeNumber) {
        // Accounts are keyed by username, not employee number, so first scan
        // all accounts to find the one (if any) belonging to this employee
        // and capture its username — orElse(null) means "not found".
        String username = byUsername.values().stream()
                .filter(account -> account.getEmployeeNumber().equals(employeeNumber))
                .map(Account::getUsername)
                .findFirst().orElse(null);
        // Only remove and rewrite the file if a matching account was actually found and removed.
        if (username != null && byUsername.remove(username) != null) {
            persist();
        }
    }
}
