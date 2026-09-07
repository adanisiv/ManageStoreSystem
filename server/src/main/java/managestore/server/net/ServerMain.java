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
import java.util.concurrent.Semaphore;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Entry point: opens the listening socket and spawns one {@link ClientHandler} thread per connection. */
public class ServerMain {

    private static final Logger LOG = Logger.getLogger(ServerMain.class.getName());

    // Caps how many clients can be connected to this server at the same time. The thread pool in
    // main() below has no limit of its own -- newCachedThreadPool() grows without bound -- so
    // without this, a client (or a bug, or someone probing the server) that opens far more
    // connections than a real store chain ever would could exhaust the server's threads and file
    // handles. A Semaphore is the standard tool for capping how many callers can hold a limited
    // resource at once: each connection acquires one of a fixed number of permits when it's
    // accepted, and releases it back when that client disconnects.
    private static final int MAX_CONCURRENT_CLIENTS = 50;

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

    /** Same as {@link #acceptLoop(ServerSocket, ServerContext, ExecutorService, int)}, using the default connection limit. */
    public static void acceptLoop(ServerSocket serverSocket, ServerContext context, ExecutorService clientPool) {
        acceptLoop(serverSocket, context, clientPool, MAX_CONCURRENT_CLIENTS);
    }

    /**
     * Blocks, accepting connections and handing each to its own {@link ClientHandler} thread,
     * until the socket is closed. Never lets more than {@code maxConcurrentClients} clients be
     * connected at once — see {@link #MAX_CONCURRENT_CLIENTS} for why.
     *
     * <p>A permit is acquired here, on the accept thread, right after a connection comes in. If
     * the server is already at the limit, {@code acquire()} blocks, which in turn stops this loop
     * from calling {@code accept()} again — so once at capacity, further incoming connections
     * simply wait in the operating system's own connection queue instead of each spawning an
     * unbounded new thread. The permit is released once that client's handler finishes, whether
     * it disconnected normally or the connection failed.
     */
    public static void acceptLoop(ServerSocket serverSocket, ServerContext context, ExecutorService clientPool,
                                   int maxConcurrentClients) {
        Semaphore connectionSlots = new Semaphore(maxConcurrentClients);
        // Keep accepting new connections until someone closes the server socket (e.g. on shutdown).
        while (!serverSocket.isClosed()) {
            try {
                // Blocks here until a client connects.
                Socket clientSocket = serverSocket.accept();
                // Blocks here too, but only once every permit is already taken by a connected
                // client -- see the javadoc above for what that means for new connections.
                connectionSlots.acquire();
                ClientHandler handler = new ClientHandler(clientSocket, context);
                // Each client gets its own ClientHandler (implements Runnable), run on a pooled
                // thread, so one client's blocking reads never hold up any other client. The
                // permit is released here, once the handler's run() method returns -- that only
                // happens when the client disconnects -- regardless of whether it ended normally
                // or by throwing, so a permit can never be leaked and never released twice.
                clientPool.submit(() -> {
                    try {
                        handler.run();
                    } finally {
                        connectionSlots.release();
                    }
                });
            } catch (IOException e) {
                // If the socket was closed while we were blocked in accept(), that's a normal shutdown,
                // not an error — just return instead of logging and looping again.
                if (serverSocket.isClosed()) {
                    return;
                }
                // Otherwise this is an unexpected accept failure; log it and keep serving other clients.
                LOG.log(Level.WARNING, "Failed to accept connection", e);
            } catch (InterruptedException e) {
                // We were interrupted while waiting for a free connection slot. That happens when
                // the server is shutting down, since clientPool.shutdownNow() interrupts pooled
                // threads and a caller can interrupt this accept thread the same way. Restore the
                // interrupt flag for whoever called us to see, and stop accepting new connections.
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
