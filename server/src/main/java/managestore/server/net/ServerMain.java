package managestore.server.net;

import com.google.gson.Gson;
import managestore.common.model.StoreChain;
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
    public static final int DEFAULT_PORT = 5050;

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        Path dataDir = Paths.get("data");

        EmployeeRepository employeeRepository = new JsonFileEmployeeRepository(dataDir.resolve("employees.json"));
        AccountRepository accountRepository = new JsonFileAccountRepository(dataDir.resolve("accounts.json"));
        AuthService authService = new AuthService(accountRepository, employeeRepository);
        ServerContext context = new ServerContext(new StoreChain(), authService, employeeRepository, new Gson());

        try (ServerSocket serverSocket = bind(port)) {
            ExecutorService clientPool = Executors.newCachedThreadPool();
            try {
                acceptLoop(serverSocket, context, clientPool);
            } finally {
                clientPool.shutdownNow();
            }
        }
    }

    public static ServerSocket bind(int port) throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        LOG.info("ManageStoreSystem server listening on port " + serverSocket.getLocalPort());
        return serverSocket;
    }

    /** Blocks, accepting connections and handing each to its own {@link ClientHandler} thread, until the socket is closed. */
    public static void acceptLoop(ServerSocket serverSocket, ServerContext context, ExecutorService clientPool) {
        while (!serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientPool.submit(new ClientHandler(clientSocket, context));
            } catch (IOException e) {
                if (serverSocket.isClosed()) {
                    return;
                }
                LOG.log(Level.WARNING, "Failed to accept connection", e);
            }
        }
    }
}
