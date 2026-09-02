package managestore.client.ui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import managestore.client.net.ServerConnection;
import managestore.common.protocol.MessageType;
import managestore.common.protocol.ReportFormat;
import managestore.common.protocol.ReportLineDto;
import managestore.common.protocol.ReportRequest;
import managestore.common.protocol.ReportResponse;
import managestore.common.protocol.ReportScope;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;

/**
 * Sales reports by branch/product/category (or one grand total), optionally
 * narrowed to one calendar day — the brief's "daily report" — delivered as
 * JSON either way per the brief. WORD format additionally comes back with
 * the actual .docx bytes (Base64-encoded over the same JSON protocol) so
 * "Save as Word" just decodes and writes them to disk.
 */
public class ReportsPanel {

    private final ServerConnection connection;
    private byte[] pendingWordFile;

    public ReportsPanel(ServerConnection connection) {
        this.connection = connection;
    }

    public BorderPane build() {
        ChoiceBox<ReportScope> scopeChoice = new ChoiceBox<>(FXCollections.observableArrayList(ReportScope.values()));
        scopeChoice.getSelectionModel().select(ReportScope.BRANCH);
        TextField filterField = new TextField();
        filterField.setPromptText(filterHintFor(ReportScope.BRANCH));
        scopeChoice.valueProperty().addListener((obs, oldScope, newScope) -> filterField.setPromptText(filterHintFor(newScope)));
        DatePicker dayPicker = new DatePicker();
        dayPicker.setPromptText("Day (optional)");
        ChoiceBox<ReportFormat> formatChoice = new ChoiceBox<>(FXCollections.observableArrayList(ReportFormat.values()));
        formatChoice.getSelectionModel().select(ReportFormat.JSON);
        Button generateButton = new Button("Generate");
        Button saveWordButton = new Button("Save as Word...");
        saveWordButton.setDisable(true);
        saveWordButton.getStyleClass().add("secondary");
        Label titleLabel = new Label();
        titleLabel.setStyle("-fx-font-size: 15px; -fx-font-weight: bold;");
        Label totalsLabel = new Label();
        totalsLabel.setStyle("-fx-text-fill: -muted;");

        TableView<ReportLineDto> table = new TableView<>();
        table.getColumns().add(column("Label", "label"));
        table.getColumns().add(column("Quantity Sold", "quantitySold"));
        table.getColumns().add(revenueColumn());
        // An empty result (no sales matched the filter) otherwise just looks like a blank,
        // possibly-broken table with no indication anything happened.
        table.setPlaceholder(new Label("No sales match this filter."));

        HBox controls = new HBox(8, scopeChoice, filterField, dayPicker, formatChoice, generateButton, saveWordButton);
        controls.getStyleClass().add("toolbar");
        controls.setPadding(new Insets(8));

        generateButton.setOnAction(e -> {
            saveWordButton.setDisable(true);
            pendingWordFile = null;
            String filterValue = filterField.getText().trim().isEmpty() ? null : filterField.getText().trim();
            String day = dayPicker.getValue() != null ? dayPicker.getValue().toString() : null;
            connection.send(MessageType.REPORT_REQUEST,
                    new ReportRequest(scopeChoice.getValue(), filterValue, formatChoice.getValue(), day));
        });

        saveWordButton.setOnAction(e -> {
            if (pendingWordFile == null) {
                return;
            }
            FileChooser chooser = new FileChooser();
            chooser.setInitialFileName("report.docx");
            chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Word Document", "*.docx"));
            java.io.File file = chooser.showSaveDialog(saveWordButton.getScene().getWindow());
            if (file != null) {
                try {
                    Files.write(file.toPath(), pendingWordFile);
                } catch (IOException ex) {
                    new Alert(Alert.AlertType.ERROR, "Could not save file: " + ex.getMessage()).showAndWait();
                }
            }
        });

        connection.on(MessageType.REPORT_RESPONSE, message -> {
            ReportResponse response = message.readPayload(connection.getGson(), ReportResponse.class);
            titleLabel.setText(response.getTitle());
            table.getItems().setAll(response.getLines());
            totalsLabel.setText("Total quantity: " + response.getTotalQuantity()
                    + "   Total revenue: " + formatCurrency(response.getTotalRevenue()));
            if (response.getWordFileBase64() != null) {
                pendingWordFile = Base64.getDecoder().decode(response.getWordFileBase64());
                saveWordButton.setDisable(false);
            }
        });

        VBox summary = new VBox(4, titleLabel, totalsLabel);
        summary.setPadding(new Insets(10, 8, 4, 8));

        BorderPane pane = new BorderPane();
        pane.setTop(controls);
        pane.setCenter(table);
        pane.setBottom(summary);
        return pane;
    }

    /** What the free-text filter field actually expects, which otherwise isn't obvious from "Filter value". */
    private String filterHintFor(ReportScope scope) {
        switch (scope) {
            case BRANCH:
                return "Branch ID, e.g. B1 (blank = all branches)";
            case PRODUCT:
                return "Product SKU, e.g. SKU-TSHIRT (blank = all products)";
            case CATEGORY:
                return "Category name, e.g. Tops (blank = all categories)";
            case ALL:
            default:
                return "(no filter for ALL — grand total)";
        }
    }

    /** Revenue is a plain double; PropertyValueFactory would render it via Double.toString (e.g. "245.0"). */
    private TableColumn<ReportLineDto, ?> revenueColumn() {
        TableColumn<ReportLineDto, String> col = new TableColumn<>("Revenue");
        col.setCellValueFactory(data -> new javafx.beans.property.SimpleStringProperty(
                formatCurrency(data.getValue().getRevenue())));
        return col;
    }

    private String formatCurrency(double amount) {
        return String.format(java.util.Locale.US, "%.2f", amount);
    }

    private TableColumn<ReportLineDto, ?> column(String title, String property) {
        TableColumn<ReportLineDto, Object> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        return col;
    }
}
