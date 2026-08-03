package managestore.common.protocol;

/** Sent by a shift manager to join the chat session that {@code targetEmployeeNumber} is currently in. */
public class ChatJoinRequest {

    private final String targetEmployeeNumber;

    public ChatJoinRequest(String targetEmployeeNumber) {
        this.targetEmployeeNumber = targetEmployeeNumber;
    }

    public String getTargetEmployeeNumber() {
        return targetEmployeeNumber;
    }
}
