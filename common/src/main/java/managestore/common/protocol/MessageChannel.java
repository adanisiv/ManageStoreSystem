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
 * Wraps a {@link Socket} to send/receive one {@link Message} per line of
 * JSON. Used identically by the server's ClientHandler and the client's
 * ServerConnection, so the wire format only needs to be implemented once.
 *
 * <p>{@link #send} is synchronized because, once a client is logged in, both
 * the connection's own handler thread and the server's broadcast/notification
 * threads (inventory updates, chat notices) may write to the same socket
 * concurrently.
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
        reader.close();
        writer.close();
        socket.close();
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
