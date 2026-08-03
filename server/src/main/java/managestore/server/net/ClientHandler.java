package managestore.server.net;

import managestore.common.model.Employee;
import managestore.common.protocol.LoginRequest;
import managestore.common.protocol.LoginResponse;
import managestore.common.protocol.Message;
import managestore.common.protocol.MessageChannel;
import managestore.common.protocol.MessageType;
import managestore.server.service.SessionManager;

import java.io.IOException;
import java.net.Socket;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * One thread per connected client (the standard shape for a simple
 * multithreaded socket server): reads {@link Message}s from its socket in a
 * loop and dispatches by {@link MessageType}. Later tasks (inventory sync,
 * chat, reports) add more cases to {@link #dispatch}; this first pass wires
 * up login/logout and duplicate-session rejection.
 */
public class ClientHandler implements Runnable {

    private static final Logger LOG = Logger.getLogger(ClientHandler.class.getName());

    private final Socket socket;
    private final ServerContext context;
    private final String sessionId = UUID.randomUUID().toString();

    private volatile String loggedInUsername;
    private volatile Employee loggedInEmployee;

    public ClientHandler(Socket socket, ServerContext context) {
        this.socket = socket;
        this.context = context;
    }

    public Employee getLoggedInEmployee() {
        return loggedInEmployee;
    }

    @Override
    public void run() {
        try (MessageChannel channel = new MessageChannel(socket, context.getGson())) {
            Message message;
            while ((message = channel.receive()) != null) {
                dispatch(channel, message);
            }
        } catch (IOException e) {
            LOG.log(Level.FINE, "Connection closed: " + e.getMessage());
        } finally {
            if (loggedInUsername != null) {
                SessionManager.getInstance().logout(loggedInUsername);
            }
        }
    }

    private void dispatch(MessageChannel channel, Message message) {
        switch (message.getType()) {
            case LOGIN_REQUEST:
                handleLogin(channel, message);
                break;
            case LOGOUT:
                handleLogout();
                break;
            default:
                LOG.warning("Unhandled message type: " + message.getType());
        }
    }

    private void handleLogin(MessageChannel channel, Message message) {
        LoginRequest request = message.readPayload(context.getGson(), LoginRequest.class);
        LoginResponse response = context.getAuthService().login(request.getUsername(), request.getPassword());

        if (response.isSuccess()) {
            boolean sessionAcquired = SessionManager.getInstance().tryLogin(request.getUsername(), sessionId);
            if (!sessionAcquired) {
                response = LoginResponse.failure("This user is already logged in on another computer");
            } else {
                loggedInUsername = request.getUsername();
                loggedInEmployee = response.getEmployee();
            }
        }

        channel.send(Message.of(context.getGson(), MessageType.LOGIN_RESPONSE, response));
    }

    private void handleLogout() {
        if (loggedInUsername != null) {
            SessionManager.getInstance().logout(loggedInUsername);
            loggedInUsername = null;
            loggedInEmployee = null;
        }
    }
}
