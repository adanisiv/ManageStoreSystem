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
 * Sales reports, grouped by branch, product, or category — or one grand total
 * across everything. Can optionally be narrowed down to a single calendar day.
 *
 * The response from the server is always JSON. When the requested format is
 * WORD, the same JSON response also carries the actual .docx file, encoded as
 * Base64 text so it can travel over the same JSON protocol. "Save as Word"
 * just decodes that text back into bytes and writes them to disk.
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
        // Update the filter field's placeholder text whenever the scope changes,
        // so it always shows an example that matches what's selected, such as a
        // branch ID or a SKU.
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
        // Without this message, an empty result (no sales matched the filter) would
        // just look like a blank table, with no indication of whether anything happened
        // or the app was broken.
        table.setPlaceholder(new Label("No sales match this filter."));

        HBox controls = new HBox(8, scopeChoice, filterField, dayPicker, formatChoice, generateButton, saveWordButton);
        controls.getStyleClass().add("toolbar");
        controls.setPadding(new Insets(8));

        // "Generate" clicked. Any Word file we fetched earlier no longer matches the
        // new report, so we clear it and disable Save until a new response arrives.
        // We also send an empty filter field as null, not as an empty string. That
        // way "no filter" reaches the server clearly, instead of an empty string
        // that a scope-specific match might treat differently.
        generateButton.setOnAction(e -> {
            saveWordButton.setDisable(true);
            pendingWordFile = null;
            String filterValue = filterField.getText().trim().isEmpty() ? null : filterField.getText().trim();
            String day = dayPicker.getValue() != null ? dayPicker.getValue().toString() : null;
            connection.send(MessageType.REPORT_REQUEST,
                    new ReportRequest(scopeChoice.getValue(), filterValue, formatChoice.getValue(), day));
        });

        // "Save as Word..." clicked. This only works once a WORD-format report
        // response has arrived and its file bytes have been decoded — see
        // pendingWordFile below. It opens the native save dialog, then writes the
        // already-decoded bytes straight to the chosen path.
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

        // This is the reply to our own REPORT_REQUEST. Every time, it fills in the
        // title, table rows, and totals from the JSON part of the response.
        // If the request asked for WORD format, the response also carries the .docx
        // file as Base64 text. We decode it once here and keep the raw bytes around,
        // so "Save as Word" can write them out later without asking the server again.
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

    /** Explains what the free-text filter field expects. This isn't obvious from a label like "Filter value" alone. */
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

    /**
     * Revenue is stored as a plain double. Left to the default PropertyValueFactory,
     * it would show up formatted like Double.toString does, for example "245.0".
     * We format it ourselves instead, so it reads like a normal price.
     */
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
