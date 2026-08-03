package managestore.common.protocol;

/** Sent back to the requester when nobody was free: their request is waiting in that branch's queue. */
public class ChatQueuedNotice {

    private final String targetBranchId;

    public ChatQueuedNotice(String targetBranchId) {
        this.targetBranchId = targetBranchId;
    }

    public String getTargetBranchId() {
        return targetBranchId;
    }
}
