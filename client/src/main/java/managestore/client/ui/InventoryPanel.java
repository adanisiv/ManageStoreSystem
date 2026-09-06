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
 * Shows this employee's branch inventory. From here they can sell a product
 * to a customer, or restock one from the supplier.
 *
 * <p>The table is filled once, using an INVENTORY_SNAPSHOT_REQUEST and its
 * response. After that it stays live: the server pushes an INVENTORY_UPDATE
 * message any time the stock changes, even when another employee is the one
 * who sold or restocked something. This works through the Observer pattern:
 * the server keeps a list of "observers" (screens like this one) and notifies
 * all of them whenever inventory changes, instead of each screen having to
 * ask "did anything change?" over and over.
 *
 * <p>Product and customer are picked from dropdowns, backed by the same live
 * data as the table and the customer directory. Nobody using this screen
 * should have to type or memorize a raw SKU or personal ID just to sell something.
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
        // Highlighting a low-quantity row lets someone spot "needs restocking soon"
        // at a glance, instead of reading the number in every row. Same idea as a
        // low-battery or low-fuel warning light.
        table.setRowFactory(tv -> new TableRow<StockEntry>() {
            @Override
            protected void updateItem(StockEntry item, boolean empty) {
                super.updateItem(item, empty);
                pseudoClassStateChanged(LOW_STOCK, !empty && item != null && item.getQuantity() < LOW_STOCK_THRESHOLD);
            }
        });

        // productChoice and customerChoice are built from the same observable lists
        // that back the inventory table and customer directory ("observable" means
        // JavaFX watches the list and updates anything built from it automatically).
        // So both dropdowns update on their own whenever rows or customerRows changes below.
        ChoiceBox<StockEntry> productChoice = new ChoiceBox<>(rows);
        productChoice.setPrefWidth(240);
        ChoiceBox<CustomerDto> customerChoice = new ChoiceBox<>(customerRows);
        customerChoice.setPrefWidth(200);
        Spinner<Integer> quantitySpinner = new Spinner<>(1, 1000, 1);
        Button sellButton = new Button("🛒 Sell");
        Button restockButton = new Button("📦 Restock (purchase)");
        Label statusLabel = new Label();

        // "Sell" clicked. Check that a product and a customer are both selected,
        // then ask the server to process the sale. The result (success or failure)
        // arrives later, via PURCHASE_RESPONSE below. This handler does not update
        // the UI itself.
        sellButton.setOnAction(e -> {
            StockEntry product = productChoice.getValue();
            CustomerDto customer = customerChoice.getValue();
            if (product == null || customer == null) {
                UiUtil.setStatus(statusLabel, false, "Pick a product and a customer first.");
                return;
            }
            // Disable the button right away. Inventory's stock checks are correct even if two
            // sales arrive at once, so a fast double-click wouldn't corrupt anything — but it
            // would still ring up two real sales instead of one. PURCHASE_RESPONSE re-enables it.
            sellButton.setDisable(true);
            connection.send(MessageType.PURCHASE_REQUEST,
                    new PurchaseRequest(product.getSku(), quantitySpinner.getValue(), customer.getPersonalId()));
        });

        // "Restock" clicked. Check that a product is selected, then ask the server
        // to add the chosen quantity back into stock. The result comes back via RESTOCK_RESPONSE.
        restockButton.setOnAction(e -> {
            StockEntry product = productChoice.getValue();
            if (product == null) {
                UiUtil.setStatus(statusLabel, false, "Pick a product first.");
                return;
            }
            // Same reason as the Sell button above: block a double-click from sending two
            // real restock requests. RESTOCK_RESPONSE re-enables it.
            restockButton.setDisable(true);
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

        // The first full load of inventory (we send the request once, below).
        // This is the only listener that clears the bySku map and rebuilds it from
        // scratch. It then pushes the result into the table and dropdown using the
        // shared refresh helper below.
        connection.on(MessageType.INVENTORY_SNAPSHOT_RESPONSE, message -> {
            InventorySnapshotResponse response = message.readPayload(connection.getGson(), InventorySnapshotResponse.class);
            bySku.clear();
            for (StockEntry entry : response.getItems()) {
                bySku.put(entry.getSku(), entry);
            }
            refreshRowsKeepingSelection(rows, bySku.values(), productChoice, StockEntry::getSku);
        });

        // A live push telling us one product's stock changed. This can be from our
        // own sale or restock, or from another employee's, anywhere in the branch.
        // We only update that one SKU in the map, not the whole list, then refresh
        // the table and dropdown.
        connection.on(MessageType.INVENTORY_UPDATE, message -> {
            InventoryUpdateNotice notice = message.readPayload(connection.getGson(), InventoryUpdateNotice.class);
            bySku.put(notice.getEntry().getSku(), notice.getEntry());
            refreshRowsKeepingSelection(rows, bySku.values(), productChoice, StockEntry::getSku);
        });

        // The first full load of the customer directory (we send the request once, below).
        connection.on(MessageType.CUSTOMER_LIST_RESPONSE, message -> {
            CustomerListResponse response = message.readPayload(connection.getGson(), CustomerListResponse.class);
            customersById.clear();
            for (CustomerDto customer : response.getCustomers()) {
                customersById.put(customer.getPersonalId(), customer);
            }
            refreshRowsKeepingSelection(customerRows, customersById.values(), customerChoice, CustomerDto::getPersonalId);
        });

        // A live push for when a customer's data changes elsewhere, for example
        // another screen edited them. Same "update one entry, then refresh" approach as inventory.
        connection.on(MessageType.CUSTOMER_UPDATE_BROADCAST, message -> {
            CustomerUpdateNotice notice = message.readPayload(connection.getGson(), CustomerUpdateNotice.class);
            customersById.put(notice.getCustomer().getPersonalId(), notice.getCustomer());
            refreshRowsKeepingSelection(customerRows, customersById.values(), customerChoice, CustomerDto::getPersonalId);
        });

        // This is the reply to our own PURCHASE_REQUEST, sent from the "Sell" button
        // above. On success, report the amount actually charged. On failure, show
        // the server's reason, for example not enough stock.
        // Note that the inventory table itself is NOT updated here. It gets updated
        // separately, by the INVENTORY_UPDATE push that the sale triggers on the server.
        connection.on(MessageType.PURCHASE_RESPONSE, message -> {
            PurchaseResponse response = message.readPayload(connection.getGson(), PurchaseResponse.class);
            UiUtil.setStatus(statusLabel, response.isSuccess(), response.isSuccess()
                    ? "Sold — charged " + formatCurrency(response.getAmountCharged()) + " (list " + formatCurrency(response.getListTotal()) + ")"
                    : "Sale failed: " + response.getErrorMessage());
            sellButton.setDisable(false);
        });

        // This is the reply to our own RESTOCK_REQUEST, sent from the "Restock" button above.
        connection.on(MessageType.RESTOCK_RESPONSE, message -> {
            RestockResponse response = message.readPayload(connection.getGson(), RestockResponse.class);
            UiUtil.setStatus(statusLabel, response.isSuccess(), response.isSuccess()
                    ? "Restocked — new quantity " + response.getNewQuantity()
                    : "Restock failed: " + response.getErrorMessage());
            restockButton.setDisable(false);
        });

        // Kick off the initial loads as soon as the panel is built. The responses
        // are handled by the listeners registered just above.
        connection.send(MessageType.INVENTORY_SNAPSHOT_REQUEST, new Object());
        connection.send(MessageType.CUSTOMER_LIST_REQUEST, new Object());

        BorderPane pane = new BorderPane();
        pane.setCenter(table);
        pane.setBottom(new VBox(sellBar, statusLabel));
        return pane;
    }

    /**
     * Replaces a ChoiceBox's backing list completely, the way every snapshot or
     * push here does, while keeping whatever item was selected.
     *
     * Without this, every push — even the one confirming the user's own sale or
     * restock — would silently clear their selection. Here is why: the new list
     * holds brand-new {@link StockEntry} or {@link CustomerDto} objects. These are
     * plain data classes with no custom {@code equals()} method, so Java compares
     * them by identity, not by their field values. The ChoiceBox's old selected
     * object is never found in the new list, since it is technically a different
     * object even if its data is the same, so the selection just disappears.
     *
     * To avoid that, we re-select by a stable key (the SKU or personal ID) instead
     * of relying on the same object surviving the refresh.
     */
    private <T> void refreshRowsKeepingSelection(ObservableList<T> rows, Collection<T> newValues,
                                                  ChoiceBox<T> choice, Function<T, String> keyOf) {
        // Remember what's currently selected, by its stable key, before wiping the list.
        T selected = choice.getValue();
        String selectedKey = selected != null ? keyOf.apply(selected) : null;
        // Wholesale replace of the backing list with the fresh data.
        rows.setAll(newValues);
        if (selectedKey != null) {
            // Find the new object that has the same key as what was selected before,
            // and re-select it so the user's choice survives the refresh.
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
