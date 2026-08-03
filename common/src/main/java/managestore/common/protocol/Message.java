package managestore.common.protocol;

import com.google.gson.Gson;
import com.google.gson.JsonElement;

/**
 * Envelope for every message on the wire: a {@link MessageType} tag plus a
 * raw JSON payload. Keeping the payload as a {@link JsonElement} (rather than
 * a generic type parameter, which Gson can't deserialize reliably due to
 * type erasure) lets the receiver decide which concrete DTO class to parse
 * it into based on {@link #getType()} — see {@link MessageChannel}.
 */
public class Message {

    private final MessageType type;
    private final JsonElement payload;

    public Message(MessageType type, JsonElement payload) {
        this.type = type;
        this.payload = payload;
    }

    public static Message of(Gson gson, MessageType type, Object payloadObject) {
        return new Message(type, gson.toJsonTree(payloadObject));
    }

    public MessageType getType() {
        return type;
    }

    public JsonElement getPayload() {
        return payload;
    }

    public <T> T readPayload(Gson gson, Class<T> payloadClass) {
        return gson.fromJson(payload, payloadClass);
    }
}
