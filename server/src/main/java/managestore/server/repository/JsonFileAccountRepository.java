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
        load();
    }

    private synchronized void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<Account> accounts = gson.fromJson(reader, LIST_TYPE);
            if (accounts != null) {
                for (Account account : accounts) {
                    byUsername.put(account.getUsername(), account);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load " + file, e);
        }
    }

    private synchronized void persist() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                gson.toJson(new java.util.ArrayList<>(byUsername.values()), LIST_TYPE, writer);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to save " + file, e);
        }
    }

    @Override
    public Optional<Account> findByUsername(String username) {
        return Optional.ofNullable(byUsername.get(username));
    }

    @Override
    public synchronized void save(Account account) {
        byUsername.put(account.getUsername(), account);
        persist();
    }
}
