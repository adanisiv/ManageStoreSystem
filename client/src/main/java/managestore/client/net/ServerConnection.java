package managestore.client.net;

import com.google.gson.Gson;
import javafx.application.Platform;
import managestore.common.protocol.Message;
import managestore.common.protocol.MessageChannel;
import managestore.common.protocol.MessageType;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Client-side networking. The protocol has no request/response correlation
 * id, and a push (e.g. INVENTORY_UPDATE from another employee's purchase)
 * can legitimately arrive interleaved with the reply to a request this
 * client just sent. So rather than "send, then block waiting for one
 * specific reply", every screen just registers a listener per
 * {@link MessageType} it cares about, and a single background reader thread
 * dispatches every incoming message to whichever listeners are registered
 * for its type — an event-driven design that has no ordering assumptions to
 * get wrong. Listener callbacks always run via {@link Platform#runLater} so
 * screens can touch JavaFX UI nodes directly without extra marshaling.
 */
public class ServerConnection {

    private final Gson gson = new Gson();
    private final Map<MessageType, List<Consumer<Message>>> listeners = new ConcurrentHashMap<>();

    private Socket socket;
    private MessageChannel channel;
    private Thread readerThread;

    public void connect(String host, int port) throws IOException {
        socket = new Socket(host, port);
        channel = new MessageChannel(socket, gson);
        readerThread = new Thread(this::readLoop, "server-connection-reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public void send(MessageType type, Object payload) {
        channel.send(Message.of(gson, type, payload));
    }

    public Gson getGson() {
        return gson;
    }

    public void on(MessageType type, Consumer<Message> listener) {
        listeners.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void close() {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            // closing on the way out; nothing useful to do with this
        }
    }

    private void readLoop() {
        try {
            Message message;
            while ((message = channel.receive()) != null) {
                List<Consumer<Message>> forType = listeners.get(message.getType());
                if (forType != null) {
                    Message finalMessage = message;
                    Platform.runLater(() -> forType.forEach(listener -> listener.accept(finalMessage)));
                }
            }
        } catch (IOException e) {
            // Connection dropped (server closed it, or we did via close()) — nothing more to read.
        }
    }
}
