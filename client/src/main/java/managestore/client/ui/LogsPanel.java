package managestore.client.ui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import managestore.client.net.ServerConnection;
import managestore.common.model.LogType;
import managestore.common.protocol.LogEventDto;
import managestore.common.protocol.LogListRequest;
import managestore.common.protocol.LogListResponse;
import managestore.common.protocol.MessageType;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Admin-only system log: employee/customer registration, sales, and chat transcripts. */
public class LogsPanel {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final ServerConnection connection;

    public LogsPanel(ServerConnection connection) {
        this.connection = connection;
    }

    public BorderPane build() {
        TableView<LogEventDto> table = new TableView<>();
        table.getColumns().add(timeColumn());
        table.getColumns().add(column("Type", "type"));
        table.getColumns().add(column("Actor", "actor"));
        table.getColumns().add(column("Details", "details"));

        // "All types" is represented as a null item so the choice box can drive LogListRequest's
        // nullable typeFilter directly, without a separate sentinel/string to translate.
        ChoiceBox<LogType> typeFilter = new ChoiceBox<>(FXCollections.observableArrayList(
                withAllTypesOption()));
        typeFilter.getSelectionModel().selectFirst();
        Button refreshButton = new Button("Refresh");

        Runnable refresh = () -> {
            LogType selected = typeFilter.getValue();
            connection.send(MessageType.LOG_LIST_REQUEST, new LogListRequest(selected != null ? selected.name() : null));
        };
        refreshButton.setOnAction(e -> refresh.run());
        typeFilter.valueProperty().addListener((obs, old, current) -> refresh.run());

        HBox toolbar = new HBox(8, new Label("Filter by type:"), typeFilter, refreshButton);
        toolbar.getStyleClass().add("toolbar");
        toolbar.setPadding(new Insets(8));

        connection.on(MessageType.LOG_LIST_RESPONSE, message -> {
            LogListResponse response = message.readPayload(connection.getGson(), LogListResponse.class);
            // The server returns entries oldest-first (the natural order to append/store them in);
            // an admin skimming a live system log expects the newest activity at the top instead.
            List<LogEventDto> newestFirst = new ArrayList<>(response.getEvents());
            newestFirst.sort(Comparator.comparingLong(LogEventDto::getTimestampEpochMillis).reversed());
            table.getItems().setAll(newestFirst);
        });

        connection.send(MessageType.LOG_LIST_REQUEST, new LogListRequest(null));

        BorderPane pane = new BorderPane();
        pane.setTop(toolbar);
        pane.setCenter(table);
        return pane;
    }

    private static LogType[] withAllTypesOption() {
        // null is a legal element of an ObservableList<LogType> and JavaFX's default ChoiceBox
        // cell renders it as an empty string, which reads as "no filter" clearly enough here.
        LogType[] all = LogType.values();
        LogType[] withNull = new LogType[all.length + 1];
        System.arraycopy(all, 0, withNull, 1, all.length);
        return withNull;
    }

    private TableColumn<LogEventDto, ?> timeColumn() {
        TableColumn<LogEventDto, String> col = new TableColumn<>("Time");
        col.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                TIME_FORMAT.format(Instant.ofEpochMilli(data.getValue().getTimestampEpochMillis()))));
        col.setPrefWidth(150);
        return col;
    }

    private TableColumn<LogEventDto, ?> column(String title, String property) {
        TableColumn<LogEventDto, Object> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        return col;
    }
}
