package managestore.client.ui;

import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.css.PseudoClass;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import managestore.client.net.ServerConnection;
import managestore.common.protocol.CustomerDto;
import managestore.common.protocol.CustomerListResponse;
import managestore.common.protocol.CustomerUpdateNotice;
import managestore.common.protocol.InventorySnapshotResponse;
import managestore.common.protocol.InventoryUpdateNotice;
import managestore.common.protocol.MessageType;
import managestore.common.protocol.PurchaseRequest;
import managestore.common.protocol.PurchaseResponse;
import managestore.common.protocol.RestockRequest;
import managestore.common.protocol.RestockResponse;
import managestore.common.protocol.StockEntry;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * Shows this employee's branch inventory and lets them both sell a product
 * to a customer and restock one from the supplier. Populated once via
 * INVENTORY_SNAPSHOT_REQUEST/RESPONSE, then kept live by INVENTORY_UPDATE
 * pushes — including ones caused by other employees, which is the whole
 * point of the Observer wiring on the server.
 *
 * <p>Product and customer are picked from dropdowns (backed by the same
 * live data the table/customer directory already have), not typed as raw
 * SKU/personal-ID strings — nobody using this screen should have to
 * memorize a SKU to sell something.
 */
public class InventoryPanel {

    private static final int LOW_STOCK_THRESHOLD = 10;
    private static final PseudoClass LOW_STOCK = PseudoClass.getPseudoClass("low-stock");

    private final ServerConnection connection;
    private final Map<String, StockEntry> bySku = new LinkedHashMap<>();
    private final ObservableList<StockEntry> rows = FXCollections.observableArrayList();
    private final Map<String, CustomerDto> customersById = new LinkedHashMap<>();
    private final ObservableList<CustomerDto> customerRows = FXCollections.observableArrayList();

    public InventoryPanel(ServerConnection connection) {
        this.connection = connection;
    }

    public BorderPane build() {
        TableView<StockEntry> table = new TableView<>(rows);
        table.getColumns().add(column("SKU", "sku"));
        table.getColumns().add(column("Name", "name"));
        table.getColumns().add(categoryColumn());
        table.getColumns().add(priceColumn());
        table.getColumns().add(column("Quantity", "quantity"));
        // A row this low reads as "needs restocking soon" at a glance, without having to scan
        // the number in every row — same idea as a low-battery or low-fuel indicator.
        table.setRowFactory(tv -> new TableRow<StockEntry>() {
            @Override
            protected void updateItem(StockEntry item, boolean empty) {
                super.updateItem(item, empty);
                pseudoClassStateChanged(LOW_STOCK, !empty && item != null && item.getQuantity() < LOW_STOCK_THRESHOLD);
            }
        });

        ChoiceBox<StockEntry> productChoice = new ChoiceBox<>(rows);
        productChoice.setPrefWidth(240);
        ChoiceBox<CustomerDto> customerChoice = new ChoiceBox<>(customerRows);
        customerChoice.setPrefWidth(200);
        Spinner<Integer> quantitySpinner = new Spinner<>(1, 1000, 1);
        Button sellButton = new Button("🛒 Sell");
        Button restockButton = new Button("📦 Restock (purchase)");
        Label statusLabel = new Label();

        sellButton.setOnAction(e -> {
            StockEntry product = productChoice.getValue();
            CustomerDto customer = customerChoice.getValue();
            if (product == null || customer == null) {
                UiUtil.setStatus(statusLabel, false, "Pick a product and a customer first.");
                return;
            }
            connection.send(MessageType.PURCHASE_REQUEST,
                    new PurchaseRequest(product.getSku(), quantitySpinner.getValue(), customer.getPersonalId()));
        });

        restockButton.setOnAction(e -> {
            StockEntry product = productChoice.getValue();
            if (product == null) {
                UiUtil.setStatus(statusLabel, false, "Pick a product first.");
                return;
            }
            connection.send(MessageType.RESTOCK_REQUEST, new RestockRequest(product.getSku(), quantitySpinner.getValue()));
        });

        HBox sellBar = new HBox(8,
                new Label("Product:"), productChoice,
                new Label("Qty:"), quantitySpinner,
                new Label("Customer:"), customerChoice,
                sellButton, restockButton);
        sellBar.getStyleClass().add("toolbar");
        sellBar.setPadding(new Insets(8));
        statusLabel.getStyleClass().add("status-label");

        connection.on(MessageType.INVENTORY_SNAPSHOT_RESPONSE, message -> {
            InventorySnapshotResponse response = message.readPayload(connection.getGson(), InventorySnapshotResponse.class);
            bySku.clear();
            for (StockEntry entry : response.getItems()) {
                bySku.put(entry.getSku(), entry);
            }
            refreshRowsKeepingSelection(rows, bySku.values(), productChoice, StockEntry::getSku);
        });

        connection.on(MessageType.INVENTORY_UPDATE, message -> {
            InventoryUpdateNotice notice = message.readPayload(connection.getGson(), InventoryUpdateNotice.class);
            bySku.put(notice.getEntry().getSku(), notice.getEntry());
            refreshRowsKeepingSelection(rows, bySku.values(), productChoice, StockEntry::getSku);
        });

        connection.on(MessageType.CUSTOMER_LIST_RESPONSE, message -> {
            CustomerListResponse response = message.readPayload(connection.getGson(), CustomerListResponse.class);
            customersById.clear();
            for (CustomerDto customer : response.getCustomers()) {
                customersById.put(customer.getPersonalId(), customer);
            }
            refreshRowsKeepingSelection(customerRows, customersById.values(), customerChoice, CustomerDto::getPersonalId);
        });

        connection.on(MessageType.CUSTOMER_UPDATE_BROADCAST, message -> {
            CustomerUpdateNotice notice = message.readPayload(connection.getGson(), CustomerUpdateNotice.class);
            customersById.put(notice.getCustomer().getPersonalId(), notice.getCustomer());
            refreshRowsKeepingSelection(customerRows, customersById.values(), customerChoice, CustomerDto::getPersonalId);
        });

        connection.on(MessageType.PURCHASE_RESPONSE, message -> {
            PurchaseResponse response = message.readPayload(connection.getGson(), PurchaseResponse.class);
            UiUtil.setStatus(statusLabel, response.isSuccess(), response.isSuccess()
                    ? "Sold — charged " + formatCurrency(response.getAmountCharged()) + " (list " + formatCurrency(response.getListTotal()) + ")"
                    : "Sale failed: " + response.getErrorMessage());
        });

        connection.on(MessageType.RESTOCK_RESPONSE, message -> {
            RestockResponse response = message.readPayload(connection.getGson(), RestockResponse.class);
            UiUtil.setStatus(statusLabel, response.isSuccess(), response.isSuccess()
                    ? "Restocked — new quantity " + response.getNewQuantity()
                    : "Restock failed: " + response.getErrorMessage());
        });

        connection.send(MessageType.INVENTORY_SNAPSHOT_REQUEST, new Object());
        connection.send(MessageType.CUSTOMER_LIST_REQUEST, new Object());

        BorderPane pane = new BorderPane();
        pane.setCenter(table);
        pane.setBottom(new VBox(sellBar, statusLabel));
        return pane;
    }

    /**
     * Replaces a ChoiceBox's backing list wholesale (as every snapshot/push here does) while
     * keeping its current selection — otherwise every push, including the one confirming the
     * user's own just-completed sale/restock, silently clears whatever they had picked: the new
     * list holds brand-new {@link StockEntry}/{@link CustomerDto} instances (plain data classes,
     * no {@code equals()} override), so the ChoiceBox's old selected reference is never found in
     * it and the selection collapses to nothing. Re-selects by the stable key (SKU / personal ID)
     * instead of relying on object identity surviving the refresh.
     */
    private <T> void refreshRowsKeepingSelection(ObservableList<T> rows, Collection<T> newValues,
                                                  ChoiceBox<T> choice, Function<T, String> keyOf) {
        T selected = choice.getValue();
        String selectedKey = selected != null ? keyOf.apply(selected) : null;
        rows.setAll(newValues);
        if (selectedKey != null) {
            for (T candidate : rows) {
                if (selectedKey.equals(keyOf.apply(candidate))) {
                    choice.getSelectionModel().select(candidate);
                    break;
                }
            }
        }
    }

    private TableColumn<StockEntry, ?> categoryColumn() {
        TableColumn<StockEntry, String> col = new TableColumn<>("Category");
        col.setCellValueFactory(data -> new SimpleStringProperty(categoryIcon(data.getValue().getCategory()) + data.getValue().getCategory()));
        return col;
    }

    private static String categoryIcon(String category) {
        if (category == null) {
            return "";
        }
        switch (category) {
            case "Tops":
                return "👕 ";
            case "Bottoms":
                return "👖 ";
            case "Footwear":
                return "👟 ";
            case "Outerwear":
                return "🧥 ";
            case "Accessories":
                return "🧢 ";
            default:
                return "";
        }
    }

    private TableColumn<StockEntry, ?> priceColumn() {
        TableColumn<StockEntry, String> col = new TableColumn<>("Price");
        col.setCellValueFactory(data -> new SimpleStringProperty(formatCurrency(data.getValue().getPrice())));
        return col;
    }

    private static String formatCurrency(double amount) {
        return String.format(Locale.US, "%.2f", amount);
    }

    private TableColumn<StockEntry, ?> column(String title, String property) {
        TableColumn<StockEntry, Object> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        return col;
    }
}
