package managestore.common.protocol;

import java.util.List;

/** Sent to every participant (including a shift manager who just joined) when a chat session is active. */
public class ChatStartedNotice {

    private final String sessionId;
    private final List<String> participantEmployeeNumbers;

    public ChatStartedNotice(String sessionId, List<String> participantEmployeeNumbers) {
        this.sessionId = sessionId;
        this.participantEmployeeNumbers = participantEmployeeNumbers;
    }

    public String getSessionId() {
        return sessionId;
    }

    public List<String> getParticipantEmployeeNumbers() {
        return participantEmployeeNumbers;
    }
}
