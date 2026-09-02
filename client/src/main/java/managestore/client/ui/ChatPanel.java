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
        connection.on(MessageType.BRANCH_LIST_RESPONSE, message -> {
            BranchListResponse response = message.readPayload(connection.getGson(), BranchListResponse.class);
            targetBranchChoice.setItems(FXCollections.observableArrayList(response.getBranches()));
            if (!response.getBranches().isEmpty()) {
                targetBranchChoice.getSelectionModel().selectFirst();
            }
        });
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

        requestButton.setOnAction(e -> {
            BranchDto target = targetBranchChoice.getValue();
            if (target != null) {
                connection.send(MessageType.CHAT_REQUEST, new ChatRequestDto(target.getId()));
            }
        });

        sendButton.setOnAction(e -> {
            if (activeSessionId != null && !messageField.getText().trim().isEmpty()) {
                connection.send(MessageType.CHAT_MESSAGE,
                        new ChatMessageDto(activeSessionId, employee.getEmployeeNumber(), messageField.getText().trim()));
                transcript.appendText("Me: " + messageField.getText().trim() + "\n");
                messageField.clear();
            }
        });

        messageField.setOnAction(e -> sendButton.fire());

        endButton.setOnAction(e -> connection.send(MessageType.CHAT_END, new ChatEndNotice(activeSessionId)));

        joinButton.setOnAction(e -> connection.send(MessageType.CHAT_JOIN_REQUEST,
                new ChatJoinRequest(joinTargetField.getText().trim())));

        connection.on(MessageType.CHAT_QUEUED, message -> {
            ChatQueuedNotice notice = message.readPayload(connection.getGson(), ChatQueuedNotice.class);
            statusLabel.setText("Nobody free at " + notice.getTargetBranchId() + " right now — waiting in queue.");
            statusLabel.setGraphic(null);
            // Disabled the moment we're queued (not just once matched): otherwise clicking
            // "Request Chat" again while already queued would submit a second, independent queue
            // entry for the same person instead of just waiting on the first one.
            requestButton.setDisable(true);
        });

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

        connection.on(MessageType.CHAT_FREE_NOTICE, message -> {
            ChatFreeNotice notice = message.readPayload(connection.getGson(), ChatFreeNotice.class);
            statusLabel.setText(notice.getFromEmployeeName() + " tried to reach you while you were busy.");
            Button callBackButton = new Button("Call back " + notice.getFromEmployeeName());
            callBackButton.setOnAction(e -> connection.send(MessageType.CHAT_REQUEST,
                    new ChatRequestDto(null, notice.getFromEmployeeNumber())));
            statusLabel.setGraphic(callBackButton);
        });

        connection.on(MessageType.CHAT_MESSAGE, message -> {
            ChatMessageDto dto = message.readPayload(connection.getGson(), ChatMessageDto.class);
            transcript.appendText(dto.getFromEmployeeNumber() + ": " + dto.getText() + "\n");
        });

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
