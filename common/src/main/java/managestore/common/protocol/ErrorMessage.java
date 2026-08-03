package managestore.common.protocol;

/** Generic payload for {@link MessageType#ERROR} (e.g. "not logged in", "unknown request"). */
public class ErrorMessage {

    private final String message;

    public ErrorMessage(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
