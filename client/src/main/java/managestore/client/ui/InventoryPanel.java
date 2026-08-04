package managestore.client.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import managestore.client.net.ServerConnection;
import managestore.common.protocol.InventorySnapshotResponse;
import managestore.common.protocol.InventoryUpdateNotice;
import managestore.common.protocol.MessageType;
import managestore.common.protocol.PurchaseRequest;
import managestore.common.protocol.PurchaseResponse;
import managestore.common.protocol.StockEntry;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Shows this employee's branch inventory and lets them sell a product.
 * Populated once via INVENTORY_SNAPSHOT_REQUEST/RESPONSE, then kept live by
 * INVENTORY_UPDATE pushes — including ones caused by other employees, which
 * is the whole point of the Observer wiring on the server.
 */
public class InventoryPanel {

    private final ServerConnection connection;
    private final Map<String, StockEntry> bySku = new LinkedHashMap<>();
    private final ObservableList<StockEntry> rows = FXCollections.observableArrayList();

    public InventoryPanel(ServerConnection connection) {
        this.connection = connection;
    }

    public BorderPane build() {
        TableView<StockEntry> table = new TableView<>(rows);
        table.getColumns().add(column("SKU", "sku"));
        table.getColumns().add(column("Name", "name"));
        table.getColumns().add(column("Category", "category"));
        table.getColumns().add(column("Price", "price"));
        table.getColumns().add(column("Quantity", "quantity"));

        TextField skuField = new TextField();
        skuField.setPromptText("Product SKU");
        Spinner<Integer> quantitySpinner = new Spinner<>(1, 1000, 1);
        TextField customerIdField = new TextField();
        customerIdField.setPromptText("Customer ID");
        Button sellButton = new Button("Sell");
        Label statusLabel = new Label();

        sellButton.setOnAction(e -> connection.send(MessageType.PURCHASE_REQUEST,
                new PurchaseRequest(skuField.getText().trim(), quantitySpinner.getValue(), customerIdField.getText().trim())));

        HBox sellBar = new HBox(8, skuField, quantitySpinner, customerIdField, sellButton);
        sellBar.setPadding(new Insets(8));

        connection.on(MessageType.INVENTORY_SNAPSHOT_RESPONSE, message -> {
            InventorySnapshotResponse response = message.readPayload(connection.getGson(), InventorySnapshotResponse.class);
            bySku.clear();
            for (StockEntry entry : response.getItems()) {
                bySku.put(entry.getSku(), entry);
            }
            rows.setAll(bySku.values());
        });

        connection.on(MessageType.INVENTORY_UPDATE, message -> {
            InventoryUpdateNotice notice = message.readPayload(connection.getGson(), InventoryUpdateNotice.class);
            bySku.put(notice.getEntry().getSku(), notice.getEntry());
            rows.setAll(bySku.values());
        });

        connection.on(MessageType.PURCHASE_RESPONSE, message -> {
            PurchaseResponse response = message.readPayload(connection.getGson(), PurchaseResponse.class);
            statusLabel.setText(response.isSuccess()
                    ? "Sold — charged " + response.getAmountCharged() + " (list " + response.getListTotal() + ")"
                    : "Sale failed: " + response.getErrorMessage());
        });

        connection.send(MessageType.INVENTORY_SNAPSHOT_REQUEST, new Object());

        BorderPane pane = new BorderPane();
        pane.setCenter(table);
        pane.setBottom(new VBox(sellBar, statusLabel));
        return pane;
    }

    private TableColumn<StockEntry, ?> column(String title, String property) {
        TableColumn<StockEntry, Object> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        return col;
    }
}
