package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.StoreChain;
import managestore.common.protocol.NetworkDefaults;
import managestore.server.repository.AccountRepository;
import managestore.server.repository.EmployeeRepository;
import managestore.server.repository.JsonFileAccountRepository;
import managestore.server.repository.JsonFileEmployeeRepository;
import managestore.server.service.AuthService;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Entry point: opens the listening socket and spawns one {@link ClientHandler} thread per connection. */
public class ServerMain {

    private static final Logger LOG = Logger.getLogger(ServerMain.class.getName());

    public static void main(String[] args) throws IOException {
        // Use a port passed on the command line if given, otherwise fall back to the default port.
        int port = args.length > 0 ? Integer.parseInt(args[0]) : NetworkDefaults.DEFAULT_PORT;
        // All persisted server data (employees, accounts, ...) lives under this local "data" folder.
        Path dataDir = Paths.get("data");

        // Wire up the repositories (JSON-file backed) and the auth service that sits on top of them.
        EmployeeRepository employeeRepository = new JsonFileEmployeeRepository(dataDir.resolve("employees.json"));
        AccountRepository accountRepository = new JsonFileAccountRepository(dataDir.resolve("accounts.json"));
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        // A brand-new, empty StoreChain: no branches/products/accounts exist yet on a clean run,
        // so BootstrapAdmin (or an existing admin) has to create everything from here.
        ServerContext context = new ServerContext(new StoreChain(), authService, employeeRepository, new Gson());

        // Open the listening socket, then hand off to acceptLoop until the socket is closed;
        // the try-with-resources guarantees the socket gets closed even if acceptLoop exits abnormally.
        try (ServerSocket serverSocket = bind(port)) {
            // One pooled thread per connected client; the pool grows/shrinks with demand.
            ExecutorService clientPool = Executors.newCachedThreadPool();
            try {
                acceptLoop(serverSocket, context, clientPool);
            } finally {
                // Stop accepting new work and interrupt any still-running client threads on shutdown.
                clientPool.shutdownNow();
            }
        }
    }

    public static ServerSocket bind(int port) throws IOException {
        // Binding to port 0 would let the OS pick a free port, but callers always pass an explicit
        // port here — getLocalPort() below just confirms back what we actually bound to.
        ServerSocket serverSocket = new ServerSocket(port);
        LOG.info("ManageStoreSystem server listening on port " + serverSocket.getLocalPort());
        return serverSocket;
    }

    /** Blocks, accepting connections and handing each to its own {@link ClientHandler} thread, until the socket is closed. */
    public static void acceptLoop(ServerSocket serverSocket, ServerContext context, ExecutorService clientPool) {
        // Keep accepting new connections until someone closes the server socket (e.g. on shutdown).
        while (!serverSocket.isClosed()) {
            try {
                // Blocks here until a client connects.
                Socket clientSocket = serverSocket.accept();
                // Each client gets its own ClientHandler (implements Runnable), run on a pooled thread,
                // so one client's blocking reads never hold up any other client.
                clientPool.submit(new ClientHandler(clientSocket, context));
            } catch (IOException e) {
                // If the socket was closed while we were blocked in accept(), that's a normal shutdown,
                // not an error — just return instead of logging and looping again.
                if (serverSocket.isClosed()) {
                    return;
                }
                // Otherwise this is an unexpected accept failure; log it and keep serving other clients.
                LOG.log(Level.WARNING, "Failed to accept connection", e);
            }
        }
    }
}
