package managestore.common.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Wraps a {@link Socket} to send and receive one {@link Message} per line
 * of JSON. Both the server's ClientHandler and the client's
 * ServerConnection use this same class the same way, so the wire format
 * only has to be implemented once.
 *
 * <p>{@link #send} is synchronized because more than one thread can write
 * to the same socket at once, once a client is logged in. The connection's
 * own handler thread can write, and so can the server's broadcast and
 * notification threads (for things like inventory updates and chat
 * notices).
 */
public class MessageChannel implements AutoCloseable {

    private final Socket socket;
    private final Gson gson;
    private final BufferedReader reader;
    private final PrintWriter writer;

    public MessageChannel(Socket socket, Gson gson) throws IOException {
        this.socket = socket;
        this.gson = gson;
        this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        this.writer = new PrintWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), false);
    }

    public synchronized void send(Message message) {
        RawMessage raw = new RawMessage(message.getType(), message.getPayload());
        writer.println(gson.toJson(raw));
        writer.flush();
    }

    /** Blocks until the next full message arrives, or returns null on stream end (peer disconnected). */
    public Message receive() throws IOException {
        String line = reader.readLine();
        if (line == null) {
            return null;
        }
        RawMessage raw = gson.fromJson(line, RawMessage.class);
        return new Message(raw.type, raw.payload);
    }

    @Override
    public void close() throws IOException {
        // Close the socket FIRST, not the reader. BufferedReader.close() and
        // BufferedReader.readLine() both synchronize on the same internal lock. If another
        // thread is blocked inside readLine() waiting for the next message (the normal state
        // of the reader thread, most of the time), it is holding that lock for as long as the
        // blocking read takes -- which, with nothing more to read, is forever. Calling
        // reader.close() from this thread would then block forever too, waiting for a lock
        // that will never be released: a deadlock between this thread and the reader thread.
        // Closing the socket instead makes the reader thread's in-progress read fail with an
        // IOException right away, which is exactly what unblocks it and lets it release that
        // lock -- so by the time we get to reader.close() below, the lock is free.
        socket.close();
        try {
            reader.close();
        } catch (IOException ignored) {
            // The socket is already closed; closing the reader is just cleanup at this point.
        }
        writer.close();
    }

    /** Wire shape: {"type": "...", "payload": {...}}. */
    private static class RawMessage {
        final MessageType type;
        final JsonElement payload;

        RawMessage(MessageType type, JsonElement payload) {
            this.type = type;
            this.payload = payload;
        }
    }
}
