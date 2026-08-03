package managestore.common.protocol;

public class ChatEndNotice {

    private final String sessionId;

    public ChatEndNotice(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
