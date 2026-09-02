package managestore.client.ui;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import managestore.client.net.ServerConnection;
import managestore.common.protocol.CustomerAddRequest;
import managestore.common.protocol.CustomerAddResponse;
import managestore.common.protocol.CustomerDto;
import managestore.common.protocol.CustomerListResponse;
import managestore.common.protocol.CustomerUpdateNotice;
import managestore.common.protocol.MessageType;

import java.util.LinkedHashMap;
import java.util.Map;

/** Network-wide customer directory: one list, shared and kept live across every branch's employees. */
public class CustomersPanel {

    private final ServerConnection connection;
    private final Map<String, CustomerDto> byPersonalId = new LinkedHashMap<>();
    private final ObservableList<CustomerDto> rows = FXCollections.observableArrayList();

    public CustomersPanel(ServerConnection connection) {
        this.connection = connection;
    }

    public BorderPane build() {
        TableView<CustomerDto> table = new TableView<>(rows);
        table.getColumns().add(column("Personal ID", "personalId"));
        table.getColumns().add(column("Full Name", "fullName"));
        table.getColumns().add(column("Phone", "phone"));
        table.getColumns().add(column("Type", "customerType"));

        TextField idField = new TextField();
        idField.setPromptText("Personal ID");
        TextField nameField = new TextField();
        nameField.setPromptText("Full name");
        TextField phoneField = new TextField();
        phoneField.setPromptText("Phone");
        ChoiceBox<String> typeChoice = new ChoiceBox<>(FXCollections.observableArrayList("NEW", "RETURNING", "VIP"));
        typeChoice.getSelectionModel().selectFirst();
        Button addButton = new Button("Add Customer");
        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("status-label");

        addButton.setOnAction(e -> {
            String id = idField.getText().trim();
            if (id.isEmpty() || nameField.getText().trim().isEmpty()) {
                UiUtil.setStatus(statusLabel, false, "Personal ID and full name are required.");
                return;
            }
            connection.send(MessageType.CUSTOMER_ADD_REQUEST,
                    new CustomerAddRequest(id, nameField.getText().trim(), phoneField.getText().trim(), typeChoice.getValue()));
        });

        HBox addBar = new HBox(8, idField, nameField, phoneField, typeChoice, addButton, statusLabel);
        addBar.getStyleClass().add("toolbar");
        addBar.setPadding(new Insets(8));

        connection.on(MessageType.CUSTOMER_LIST_RESPONSE, message -> {
            CustomerListResponse response = message.readPayload(connection.getGson(), CustomerListResponse.class);
            byPersonalId.clear();
            for (CustomerDto customer : response.getCustomers()) {
                byPersonalId.put(customer.getPersonalId(), customer);
            }
            rows.setAll(byPersonalId.values());
        });

        // Keeps the table live for every connected employee (including other admins adding
        // customers elsewhere), independent of whether *this* form's own submission succeeded.
        connection.on(MessageType.CUSTOMER_UPDATE_BROADCAST, message -> {
            CustomerUpdateNotice notice = message.readPayload(connection.getGson(), CustomerUpdateNotice.class);
            byPersonalId.put(notice.getCustomer().getPersonalId(), notice.getCustomer());
            rows.setAll(byPersonalId.values());
        });

        // This form's own feedback: a direct response tied to the request it just sent, instead of
        // inferring success by matching the broadcast above against whatever's still typed in the
        // fields (which also had no way to represent failure at all — a rejected add, e.g. an
        // invalid personal ID, used to only surface as a separate global popup with no visible
        // connection back to this form).
        connection.on(MessageType.CUSTOMER_ADD_RESPONSE, message -> {
            CustomerAddResponse response = message.readPayload(connection.getGson(), CustomerAddResponse.class);
            if (response.isSuccess()) {
                UiUtil.setStatus(statusLabel, true, "Added " + nameField.getText().trim() + ".");
                idField.clear();
                nameField.clear();
                phoneField.clear();
            } else {
                UiUtil.setStatus(statusLabel, false, "Failed: " + response.getErrorMessage());
            }
        });

        connection.send(MessageType.CUSTOMER_LIST_REQUEST, new Object());

        BorderPane pane = new BorderPane();
        pane.setCenter(table);
        pane.setBottom(addBar);
        return pane;
    }

    private TableColumn<CustomerDto, ?> column(String title, String property) {
        TableColumn<CustomerDto, Object> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        return col;
    }
}
