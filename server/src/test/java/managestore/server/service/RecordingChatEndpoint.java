package managestore.server.service;

import managestore.common.protocol.MessageType;

import java.util.ArrayList;
import java.util.List;

/** Records every message sent to it, so tests can assert on what a ChatMediator pushed without any socket. */
class RecordingChatEndpoint implements ChatEndpoint {

    final List<MessageType> types = new ArrayList<>();
    final List<Object> payloads = new ArrayList<>();

    @Override
    public void send(MessageType type, Object payload) {
        types.add(type);
        payloads.add(payload);
    }

    <T> T lastPayload(Class<T> clazz) {
        return clazz.cast(payloads.get(payloads.size() - 1));
    }

    MessageType lastType() {
        return types.get(types.size() - 1);
    }
}
