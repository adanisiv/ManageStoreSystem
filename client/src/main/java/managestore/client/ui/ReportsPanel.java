package managestore.client.ui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
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
 * Sales reports by branch/product/category (or one grand total), delivered
 * as JSON either way per the brief — WORD format additionally comes back
 * with the actual .docx bytes (Base64-encoded over the same JSON protocol)
 * so "Save as Word" just decodes and writes them to disk.
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
        filterField.setPromptText("Filter value (optional)");
        ChoiceBox<ReportFormat> formatChoice = new ChoiceBox<>(FXCollections.observableArrayList(ReportFormat.values()));
        formatChoice.getSelectionModel().select(ReportFormat.JSON);
        Button generateButton = new Button("Generate");
        Button saveWordButton = new Button("Save as Word...");
        saveWordButton.setDisable(true);
        Label titleLabel = new Label();
        Label totalsLabel = new Label();

        TableView<ReportLineDto> table = new TableView<>();
        table.getColumns().add(column("Label", "label"));
        table.getColumns().add(column("Quantity Sold", "quantitySold"));
        table.getColumns().add(column("Revenue", "revenue"));

        HBox controls = new HBox(8, scopeChoice, filterField, formatChoice, generateButton, saveWordButton);
        controls.setPadding(new Insets(8));

        generateButton.setOnAction(e -> {
            saveWordButton.setDisable(true);
            pendingWordFile = null;
            String filterValue = filterField.getText().trim().isEmpty() ? null : filterField.getText().trim();
            connection.send(MessageType.REPORT_REQUEST,
                    new ReportRequest(scopeChoice.getValue(), filterValue, formatChoice.getValue()));
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
            totalsLabel.setText("Total quantity: " + response.getTotalQuantity() + "   Total revenue: " + response.getTotalRevenue());
            if (response.getWordFileBase64() != null) {
                pendingWordFile = Base64.getDecoder().decode(response.getWordFileBase64());
                saveWordButton.setDisable(false);
            }
        });

        BorderPane pane = new BorderPane();
        pane.setTop(controls);
        pane.setCenter(table);
        pane.setBottom(new VBox(titleLabel, totalsLabel));
        return pane;
    }

    private TableColumn<ReportLineDto, ?> column(String title, String property) {
        TableColumn<ReportLineDto, Object> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        return col;
    }
}
