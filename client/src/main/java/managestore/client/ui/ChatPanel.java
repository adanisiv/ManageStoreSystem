package managestore.client.ui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import managestore.client.net.ServerConnection;
import managestore.common.model.Employee;
import managestore.common.model.Role;
import managestore.common.protocol.BranchDto;
import managestore.common.protocol.BranchListResponse;
import managestore.common.protocol.ChatEndNotice;
import managestore.common.protocol.ChatFreeNotice;
import managestore.common.protocol.ChatJoinRequest;
import managestore.common.protocol.ChatMessageDto;
import managestore.common.protocol.ChatQueuedNotice;
import managestore.common.protocol.ChatRequestDto;
import managestore.common.protocol.ChatStartedNotice;
import managestore.common.protocol.MessageType;

/**
 * Cross-branch chat. Server-side this is entirely driven by ChatMediator
 * (Mediator pattern + a per-branch queue) — this panel just reflects
 * whatever state that mediator pushes: queued, started, a callback notice
 * once someone frees up, messages, and end.
 */
public class ChatPanel {

    private final ServerConnection connection;
    private final Employee employee;
    private String activeSessionId;

    public ChatPanel(ServerConnection connection, Employee employee) {
        this.connection = connection;
        this.employee = employee;
    }

    public BorderPane build() {
        ChoiceBox<BranchDto> targetBranchChoice = new ChoiceBox<>();
        targetBranchChoice.setPrefWidth(220);
        // When the server answers our branch list request below, fill the dropdown
        // with the branches and default to the first one so it's never left empty.
        connection.on(MessageType.BRANCH_LIST_RESPONSE, message -> {
            BranchListResponse response = message.readPayload(connection.getGson(), BranchListResponse.class);
            targetBranchChoice.setItems(FXCollections.observableArrayList(response.getBranches()));
            if (!response.getBranches().isEmpty()) {
                targetBranchChoice.getSelectionModel().selectFirst();
            }
        });
        // Ask the server for the list of branches as soon as this screen is built.
        connection.send(MessageType.BRANCH_LIST_REQUEST, new Object());
        Button requestButton = new Button("Request Chat");
        Label statusLabel = new Label("Not in a chat.");

        TextArea transcript = new TextArea();
        transcript.setEditable(false);
        TextField messageField = new TextField();
        messageField.setPromptText("Type a message...");
        Button sendButton = new Button("Send");
        Button endButton = new Button("End Chat");
        sendButton.setDisable(true);
        endButton.setDisable(true);

        TextField joinTargetField = new TextField();
        joinTargetField.setPromptText("Employee # to join their chat");
        Button joinButton = new Button("Join as Shift Manager");

        // "Request Chat" clicked: ask the server to connect this employee with a
        // free employee at the chosen branch. The server decides whether someone
        // is free right now (CHAT_STARTED) or this request has to wait (CHAT_QUEUED).
        requestButton.setOnAction(e -> {
            BranchDto target = targetBranchChoice.getValue();
            if (target != null) {
                connection.send(MessageType.CHAT_REQUEST, new ChatRequestDto(target.getId()));
            }
        });

        // "Send" clicked (or Enter pressed in the message field, see below): only
        // sends if we're actually in a chat and there's non-blank text to send.
        sendButton.setOnAction(e -> {
            if (activeSessionId != null && !messageField.getText().trim().isEmpty()) {
                connection.send(MessageType.CHAT_MESSAGE,
                        new ChatMessageDto(activeSessionId, employee.getEmployeeNumber(), messageField.getText().trim()));
                // Echo our own message into the transcript immediately rather than waiting
                // for the server to broadcast it back to us.
                transcript.appendText("Me: " + messageField.getText().trim() + "\n");
                messageField.clear();
            }
        });

        // Pressing Enter in the message field behaves the same as clicking Send.
        messageField.setOnAction(e -> sendButton.fire());

        // "End Chat" clicked: tell the server this session is over.
        endButton.setOnAction(e -> connection.send(MessageType.CHAT_END, new ChatEndNotice(activeSessionId)));

        // "Join as Shift Manager" clicked: request to join another employee's
        // in-progress chat by their employee number (used for supervising/helping).
        joinButton.setOnAction(e -> connection.send(MessageType.CHAT_JOIN_REQUEST,
                new ChatJoinRequest(joinTargetField.getText().trim())));

        // Server says no one was free to chat right now, so this request is waiting in line.
        connection.on(MessageType.CHAT_QUEUED, message -> {
            ChatQueuedNotice notice = message.readPayload(connection.getGson(), ChatQueuedNotice.class);
            statusLabel.setText("Nobody free at " + notice.getTargetBranchId() + " right now — waiting in queue.");
            statusLabel.setGraphic(null);
            // Disabled the moment we're queued (not just once matched): otherwise clicking
            // "Request Chat" again while already queued would submit a second, independent queue
            // entry for the same person instead of just waiting on the first one.
            requestButton.setDisable(true);
        });

        // Server says a chat session has actually begun: remember the session id (needed
        // to send/end messages), reset the transcript, and flip the buttons into "in a chat" mode.
        connection.on(MessageType.CHAT_STARTED, message -> {
            ChatStartedNotice notice = message.readPayload(connection.getGson(), ChatStartedNotice.class);
            activeSessionId = notice.getSessionId();
            statusLabel.setText("Chat active with: " + String.join(", ", notice.getParticipantEmployeeNumbers()));
            // A "Call back X" button from an earlier CHAT_FREE_NOTICE would otherwise keep showing
            // (and stay clickable) even after that exact callback already connected.
            statusLabel.setGraphic(null);
            transcript.clear();
            sendButton.setDisable(false);
            endButton.setDisable(false);
            requestButton.setDisable(true);
        });

        // Someone tried to reach this employee while they were busy elsewhere; show who,
        // and offer a one-click button to start a new chat request back to that person.
        connection.on(MessageType.CHAT_FREE_NOTICE, message -> {
            ChatFreeNotice notice = message.readPayload(connection.getGson(), ChatFreeNotice.class);
            statusLabel.setText(notice.getFromEmployeeName() + " tried to reach you while you were busy.");
            Button callBackButton = new Button("Call back " + notice.getFromEmployeeName());
            callBackButton.setOnAction(e -> connection.send(MessageType.CHAT_REQUEST,
                    new ChatRequestDto(null, notice.getFromEmployeeNumber())));
            statusLabel.setGraphic(callBackButton);
        });

        // An incoming chat message pushed from the server (from whoever we're chatting
        // with) gets appended to the transcript, prefixed with their employee number.
        connection.on(MessageType.CHAT_MESSAGE, message -> {
            ChatMessageDto dto = message.readPayload(connection.getGson(), ChatMessageDto.class);
            transcript.appendText(dto.getFromEmployeeNumber() + ": " + dto.getText() + "\n");
        });

        // The chat session ended (either side ended it, or the server closed it):
        // clear the session id and flip the buttons back into "not in a chat" mode.
        connection.on(MessageType.CHAT_END, message -> {
            activeSessionId = null;
            statusLabel.setText("Chat ended.");
            statusLabel.setGraphic(null);
            sendButton.setDisable(true);
            endButton.setDisable(true);
            requestButton.setDisable(false);
        });

        endButton.getStyleClass().add("secondary");

        HBox requestBar = new HBox(8, new Label("Chat with a free employee at:"), targetBranchChoice, requestButton, statusLabel);
        requestBar.getStyleClass().add("toolbar");
        requestBar.setPadding(new Insets(8));
        HBox sendBar = new HBox(8, messageField, sendButton, endButton);
        sendBar.getStyleClass().add("toolbar");
        sendBar.setPadding(new Insets(8));

        VBox top = new VBox(8, requestBar);
        // The "join another employee's chat" bar is only shown to shift managers —
        // regular employees don't get the option to insert themselves into someone else's session.
        if (employee.getRole() == Role.SHIFT_MANAGER) {
            joinTargetField.setPromptText("Employee # to join (see the Employees tab)");
            HBox joinBar = new HBox(8, joinTargetField, joinButton);
            joinBar.getStyleClass().add("toolbar");
            joinBar.setPadding(new Insets(8));
            top.getChildren().add(joinBar);
        }

        BorderPane pane = new BorderPane();
        pane.setTop(top);
        pane.setCenter(transcript);
        pane.setBottom(sendBar);
        return pane;
    }
}
