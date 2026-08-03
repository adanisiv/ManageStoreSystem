package managestore.common.protocol;

/**
 * Sent by an employee asking to open a chat. Either targets any free
 * employee at {@code targetBranchId} (normal case), or, when
 * {@code targetEmployeeNumber} is set, a specific employee directly — used
 * for the callback flow: an employee who just received a {@link ChatFreeNotice}
 * calls back the specific person who originally tried to reach them.
 */
public class ChatRequestDto {

    private final String targetBranchId;
    private final String targetEmployeeNumber;

    public ChatRequestDto(String targetBranchId) {
        this(targetBranchId, null);
    }

    public ChatRequestDto(String targetBranchId, String targetEmployeeNumber) {
        this.targetBranchId = targetBranchId;
        this.targetEmployeeNumber = targetEmployeeNumber;
    }

    public String getTargetBranchId() {
        return targetBranchId;
    }

    public String getTargetEmployeeNumber() {
        return targetEmployeeNumber;
    }
}
