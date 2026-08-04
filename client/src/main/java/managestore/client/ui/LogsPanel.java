package managestore.client.ui;

import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import managestore.client.net.ServerConnection;
import managestore.common.protocol.LogEventDto;
import managestore.common.protocol.LogListRequest;
import managestore.common.protocol.LogListResponse;
import managestore.common.protocol.MessageType;

/** Admin-only system log: employee/customer registration, sales, and chat transcripts. */
public class LogsPanel {

    private final ServerConnection connection;

    public LogsPanel(ServerConnection connection) {
        this.connection = connection;
    }

    public BorderPane build() {
        TableView<LogEventDto> table = new TableView<>();
        table.getColumns().add(column("Type", "type"));
        table.getColumns().add(column("Actor", "actor"));
        table.getColumns().add(column("Details", "details"));

        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> connection.send(MessageType.LOG_LIST_REQUEST, new LogListRequest(null)));
        refreshButton.setPadding(new Insets(8));

        connection.on(MessageType.LOG_LIST_RESPONSE, message -> {
            LogListResponse response = message.readPayload(connection.getGson(), LogListResponse.class);
            table.getItems().setAll(response.getEvents());
        });

        connection.send(MessageType.LOG_LIST_REQUEST, new LogListRequest(null));

        BorderPane pane = new BorderPane();
        pane.setTop(refreshButton);
        pane.setCenter(table);
        return pane;
    }

    private TableColumn<LogEventDto, ?> column(String title, String property) {
        TableColumn<LogEventDto, Object> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        return col;
    }
}
