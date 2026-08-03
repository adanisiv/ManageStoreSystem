package managestore.server.service;

import java.time.Instant;

/** A chat request that couldn't be matched to a free employee immediately, waiting in a branch's queue. */
class ChatRequest {

    private final String fromEmployeeNumber;
    private final String targetBranchId;
    private final Instant requestedAt = Instant.now();

    ChatRequest(String fromEmployeeNumber, String targetBranchId) {
        this.fromEmployeeNumber = fromEmployeeNumber;
        this.targetBranchId = targetBranchId;
    }

    String getFromEmployeeNumber() {
        return fromEmployeeNumber;
    }

    String getTargetBranchId() {
        return targetBranchId;
    }

    Instant getRequestedAt() {
        return requestedAt;
    }
}
