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

        // "All types" is represented as a null item in the dropdown. That way the
        // choice box can feed straight into LogListRequest's typeFilter, which also
        // accepts null for "no filter". We avoid needing a separate placeholder value
        // that we would have to translate back to null ourselves.
        ChoiceBox<LogType> typeFilter = new ChoiceBox<>(FXCollections.observableArrayList(
                withAllTypesOption()));
        typeFilter.getSelectionModel().selectFirst();
        Button refreshButton = new Button("Refresh");

        // Both the Refresh button and the filter dropdown use this same logic: build
        // a request using whichever type is currently selected (null means "all types")
        // and send it.
        Runnable refresh = () -> {
            LogType selected = typeFilter.getValue();
            connection.send(MessageType.LOG_LIST_REQUEST, new LogListRequest(selected != null ? selected.name() : null));
        };
        // "Refresh" clicked: re-request logs with the current filter.
        refreshButton.setOnAction(e -> refresh.run());
        // Changing the filter dropdown re-requests right away, with no extra click needed.
        typeFilter.valueProperty().addListener((obs, old, current) -> refresh.run());

        HBox toolbar = new HBox(8, new Label("Filter by type:"), typeFilter, refreshButton);
        toolbar.getStyleClass().add("toolbar");
        toolbar.setPadding(new Insets(8));

        // This handles the reply to whichever LOG_LIST_REQUEST was sent most recently:
        // the initial load, a Refresh click, or a filter change. It re-sorts the
        // entries and replaces the whole table.
        connection.on(MessageType.LOG_LIST_RESPONSE, message -> {
            LogListResponse response = message.readPayload(connection.getGson(), LogListResponse.class);
            // The server returns entries oldest-first, which is the natural order to
            // store them in. But an admin skimming a live log expects to see the
            // newest activity at the top, so we flip the order here.
            List<LogEventDto> newestFirst = new ArrayList<>(response.getEvents());
            newestFirst.sort(Comparator.comparingLong(LogEventDto::getTimestampEpochMillis).reversed());
            table.getItems().setAll(newestFirst);
        });

        // Initial load, unfiltered, when the panel is first built.
        connection.send(MessageType.LOG_LIST_REQUEST, new LogListRequest(null));

        BorderPane pane = new BorderPane();
        pane.setTop(toolbar);
        pane.setCenter(table);
        return pane;
    }

    private static LogType[] withAllTypesOption() {
        // A null value is allowed inside an ObservableList<LogType>. JavaFX's default
        // ChoiceBox cell shows it as an empty string, which reads clearly enough here as "no filter".
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
