package managestore.common.protocol;

/**
 * Sent to an employee once they become free again, telling them who tried
 * to reach them while they were busy, so they can call that person back.
 */
public class ChatFreeNotice {

    private final String fromEmployeeNumber;
    private final String fromEmployeeName;

    public ChatFreeNotice(String fromEmployeeNumber, String fromEmployeeName) {
        this.fromEmployeeNumber = fromEmployeeNumber;
        this.fromEmployeeName = fromEmployeeName;
    }

    public String getFromEmployeeNumber() {
        return fromEmployeeNumber;
    }

    public String getFromEmployeeName() {
        return fromEmployeeName;
    }
}
