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
        // Open the raw TCP connection to the server. This blocks until the
        // connection succeeds or fails, so callers should invoke this off the
        // JavaFX UI thread (e.g. from LoginScreen's background task).
        socket = new Socket(host, port);
        // Wrap the raw socket in a MessageChannel, which knows how to serialize
        // and deserialize Message objects to/from the socket's streams using gson.
        channel = new MessageChannel(socket, gson);
        // Spin up a single background thread whose whole job is to sit in a loop
        // reading whatever the server sends, for as long as the connection lives.
        readerThread = new Thread(this::readLoop, "server-connection-reader");
        // Daemon so this thread never keeps the JVM alive on its own — if the
        // rest of the app shuts down, this reader shuts down with it.
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public void send(MessageType type, Object payload) {
        // Build a Message (type + JSON-serialized payload) and hand it to the
        // channel to write out over the socket. Called from the UI thread by
        // screens that want to ask the server to do something.
        channel.send(Message.of(gson, type, payload));
    }

    public Gson getGson() {
        return gson;
    }

    public void on(MessageType type, Consumer<Message> listener) {
        // Register interest in one MessageType. computeIfAbsent lazily creates
        // the listener list for this type the first time anyone subscribes to
        // it; CopyOnWriteArrayList is used because listeners can be added while
        // the reader thread is concurrently iterating the list to dispatch a
        // message, and this way that iteration never sees a ConcurrentModificationException.
        listeners.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void close() {
        try {
            if (channel != null) {
                // Closing the channel closes the underlying socket, which in turn
                // makes the blocking channel.receive() call in readLoop() throw an
                // IOException — that's how the reader thread learns to stop.
                channel.close();
            }
        } catch (IOException ignored) {
            // closing on the way out; nothing useful to do with this
        }
    }

    private void readLoop() {
        try {
            Message message;
            // Block on receive() until a full message arrives, and keep doing so
            // for the lifetime of the connection; receive() returns null if the
            // channel is cleanly ended, which stops the loop.
            while ((message = channel.receive()) != null) {
                // Look up whoever registered interest in this particular message
                // type via on(...). If nobody did, the message is simply dropped.
                List<Consumer<Message>> forType = listeners.get(message.getType());
                if (forType != null) {
                    // Captured in a final local because the lambda below runs later,
                    // asynchronously, and the loop variable "message" will have moved
                    // on to the next iteration by the time it executes.
                    Message finalMessage = message;
                    // We're on the background reader thread here, and JavaFX UI nodes
                    // may only be touched from the JavaFX Application Thread. Platform.runLater
                    // schedules the listener notifications to run on that thread instead,
                    // so every registered listener can safely update UI in response.
                    Platform.runLater(() -> forType.forEach(listener -> listener.accept(finalMessage)));
                }
            }
        } catch (IOException e) {
            // Connection dropped (server closed it, or we did via close()) — nothing more to read.
        }
    }
}
