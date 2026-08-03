package managestore.common.protocol;

public class ChatMessageDto {

    private final String sessionId;
    private final String fromEmployeeNumber;
    private final String text;

    public ChatMessageDto(String sessionId, String fromEmployeeNumber, String text) {
        this.sessionId = sessionId;
        this.fromEmployeeNumber = fromEmployeeNumber;
        this.text = text;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getFromEmployeeNumber() {
        return fromEmployeeNumber;
    }

    public String getText() {
        return text;
    }
}
