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
 * Handles all client-side networking with the server.
 *
 * <p>The protocol has no request/response id to match a reply to the request
 * that caused it. Because of this, a push message from the server (for example
 * an INVENTORY_UPDATE caused by another employee's purchase) can arrive mixed
 * in with the reply to a request this client just sent. We cannot just
 * "send, then wait for one specific reply".
 *
 * <p>Instead, each screen registers a listener for every {@link MessageType}
 * it cares about. A single background reader thread reads every incoming
 * message and hands it to whichever listeners are registered for that
 * message's type. This event-driven design does not depend on messages
 * arriving in any particular order, so there is no ordering assumption that
 * can go wrong.
 *
 * <p>Listener callbacks always run through {@link Platform#runLater}, which
 * schedules code to run on the JavaFX UI thread. This lets screens update
 * JavaFX UI nodes directly from a listener, without extra setup.
 */
public class ServerConnection {

    private final Gson gson = new Gson();
    private final Map<MessageType, List<Consumer<Message>>> listeners = new ConcurrentHashMap<>();

    private Socket socket;
    private MessageChannel channel;
    private Thread readerThread;

    public void connect(String host, int port) throws IOException {
        // Open the raw TCP connection to the server. This call blocks until the
        // connection succeeds or fails. So callers should invoke this off the
        // JavaFX UI thread (for example from LoginScreen's background task),
        // otherwise the UI would freeze while waiting.
        socket = new Socket(host, port);
        // Wrap the raw socket in a MessageChannel. The channel knows how to
        // turn Message objects into bytes and back, using gson, over the
        // socket's streams.
        channel = new MessageChannel(socket, gson);
        // Start one background thread whose only job is to sit in a loop and
        // read whatever the server sends, for as long as the connection lives.
        readerThread = new Thread(this::readLoop, "server-connection-reader");
        // Mark it as a daemon thread so it never keeps the JVM alive by itself.
        // If the rest of the app shuts down, this reader thread shuts down with it.
        readerThread.setDaemon(true);
        readerThread.start();
    }

    public void send(MessageType type, Object payload) {
        // Build a Message (a type plus a JSON payload) and hand it to the
        // channel, which writes it out over the socket. Screens call this
        // from the UI thread whenever they want to ask the server to do
        // something.
        channel.send(Message.of(gson, type, payload));
    }

    public Gson getGson() {
        return gson;
    }

    public void on(MessageType type, Consumer<Message> listener) {
        // Register interest in one MessageType. computeIfAbsent creates the
        // listener list for this type the first time anyone subscribes to it.
        // We use CopyOnWriteArrayList because listeners can be added at the same
        // time the reader thread is looping over the list to deliver a message.
        // A plain list would throw a ConcurrentModificationException in that case;
        // CopyOnWriteArrayList is safe to read while it is being modified.
        listeners.computeIfAbsent(type, t -> new CopyOnWriteArrayList<>()).add(listener);
    }

    public void close() {
        try {
            if (channel != null) {
                // Closing the channel also closes the underlying socket. That, in
                // turn, makes the blocking channel.receive() call in readLoop()
                // throw an IOException. That exception is how the reader thread
                // finds out it should stop.
                channel.close();
            }
        } catch (IOException ignored) {
            // closing on the way out; nothing useful to do with this
        }
        // Wait for the reader thread to actually finish before this method returns, instead of
        // just asking it to stop and moving on. Closing the channel only triggers the shutdown;
        // the thread still needs a moment to notice the IOException and exit its loop. Without
        // this join, a caller that reconnects right after close() (LoginScreen does, on a retry)
        // could end up with the old reader thread and the new one both alive for a brief window.
        // A short timeout keeps this from blocking forever if the thread is somehow stuck.
        if (readerThread != null) {
            try {
                readerThread.join(2000);
            } catch (InterruptedException e) {
                // We were interrupted while waiting. Restore that signal for whoever called us,
                // and move on rather than waiting any longer -- close() should not hang.
                Thread.currentThread().interrupt();
            }
        }
    }

    private void readLoop() {
        try {
            Message message;
            // Wait on receive() until a full message arrives, and keep doing
            // this for as long as the connection lives. receive() returns null
            // when the channel ends cleanly, which is what stops this loop.
            while ((message = channel.receive()) != null) {
                // Look up whoever registered interest in this message type
                // through on(...). If nobody did, we simply drop the message.
                List<Consumer<Message>> forType = listeners.get(message.getType());
                if (forType != null) {
                    // We save the message in this final local variable because the
                    // lambda below runs later, asynchronously. By then the loop
                    // variable "message" will already have moved to the next message.
                    Message finalMessage = message;
                    // This code runs on the background reader thread, but JavaFX UI
                    // nodes may only be touched from the JavaFX Application Thread.
                    // Platform.runLater schedules a block of code to run on that UI
                    // thread instead, so every listener below can safely update the
                    // UI in response to the message.
                    Platform.runLater(() -> forType.forEach(listener -> listener.accept(finalMessage)));
                }
            }
        } catch (IOException e) {
            // Connection dropped (server closed it, or we did via close()) — nothing more to read.
        }
    }
}
