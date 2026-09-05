package managestore.server.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An active chat. Starts with 2 participants (the two employees who
 * connected); a shift manager can join, becoming a 3rd participant — so
 * this deliberately holds a list, not a fixed pair.
 */
class ChatSession {

    private final String id = UUID.randomUUID().toString();
    private final List<String> participantEmployeeNumbers = new CopyOnWriteArrayList<>();
    private final List<String> transcript = new CopyOnWriteArrayList<>();

    String getId() {
        return id;
    }

    List<String> getParticipantEmployeeNumbers() {
        return participantEmployeeNumbers;
    }

    /** Optional saved transcript, kept for the system log entry when the chat ends. */
    List<String> getTranscript() {
        return transcript;
    }

    void appendToTranscript(String fromEmployeeNumber, String text) {
        transcript.add(fromEmployeeNumber + ": " + text);
    }

    void addParticipant(String employeeNumber) {
        participantEmployeeNumbers.add(employeeNumber);
    }

    void removeParticipant(String employeeNumber) {
        participantEmployeeNumbers.remove(employeeNumber);
    }

    boolean hasParticipant(String employeeNumber) {
        return participantEmployeeNumbers.contains(employeeNumber);
    }

    boolean isEmpty() {
        return participantEmployeeNumbers.isEmpty();
    }
}
