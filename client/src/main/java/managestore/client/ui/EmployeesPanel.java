package managestore.client.ui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import managestore.client.net.ServerConnection;
import managestore.common.model.Employee;
import managestore.common.model.Role;
import managestore.common.protocol.EmployeeAddRequest;
import managestore.common.protocol.EmployeeAddResponse;
import managestore.common.protocol.EmployeeListResponse;
import managestore.common.protocol.MessageType;

/** Employee roster for the network; the "add employee" form is the brief's Admin screen, admin-only. */
public class EmployeesPanel {

    private final ServerConnection connection;
    private final Employee currentEmployee;

    public EmployeesPanel(ServerConnection connection, Employee currentEmployee) {
        this.connection = connection;
        this.currentEmployee = currentEmployee;
    }

    public BorderPane build() {
        TableView<Employee> table = new TableView<>();
        table.getColumns().add(column("Employee #", "employeeNumber"));
        table.getColumns().add(column("Full Name", "fullName"));
        table.getColumns().add(column("Phone", "phone"));
        table.getColumns().add(column("Branch", "branchId"));
        table.getColumns().add(column("Role", "role"));

        connection.on(MessageType.EMPLOYEE_LIST_RESPONSE, message -> {
            EmployeeListResponse response = message.readPayload(connection.getGson(), EmployeeListResponse.class);
            table.getItems().setAll(response.getEmployees());
        });
        connection.send(MessageType.EMPLOYEE_LIST_REQUEST, new Object());

        BorderPane pane = new BorderPane();
        pane.setCenter(table);
        if (currentEmployee.getRole() == Role.ADMIN) {
            pane.setBottom(buildAddEmployeeForm());
        }
        return pane;
    }

    private HBox buildAddEmployeeForm() {
        TextField numberField = new TextField();
        numberField.setPromptText("Employee #");
        TextField nameField = new TextField();
        nameField.setPromptText("Full name");
        TextField personalIdField = new TextField();
        personalIdField.setPromptText("Personal ID");
        TextField phoneField = new TextField();
        phoneField.setPromptText("Phone");
        TextField accountField = new TextField();
        accountField.setPromptText("Account #");
        TextField branchField = new TextField();
        branchField.setPromptText("Branch ID");
        ChoiceBox<Role> roleChoice = new ChoiceBox<>(FXCollections.observableArrayList(Role.values()));
        roleChoice.getSelectionModel().selectFirst();
        TextField usernameField = new TextField();
        usernameField.setPromptText("Username");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        Button addButton = new Button("Add Employee");
        Label statusLabel = new Label();

        connection.on(MessageType.EMPLOYEE_ADD_RESPONSE, message -> {
            EmployeeAddResponse response = message.readPayload(connection.getGson(), EmployeeAddResponse.class);
            statusLabel.setText(response.isSuccess() ? "Employee added." : "Failed: " + response.getErrorMessage());
            if (response.isSuccess()) {
                connection.send(MessageType.EMPLOYEE_LIST_REQUEST, new Object());
            }
        });

        addButton.setOnAction(e -> connection.send(MessageType.EMPLOYEE_ADD_REQUEST, new EmployeeAddRequest(
                numberField.getText().trim(), nameField.getText().trim(), personalIdField.getText().trim(),
                phoneField.getText().trim(), accountField.getText().trim(), branchField.getText().trim(),
                roleChoice.getValue().name(), usernameField.getText().trim(), passwordField.getText())));

        HBox form = new HBox(6, numberField, nameField, personalIdField, phoneField, accountField, branchField,
                roleChoice, usernameField, passwordField, addButton, statusLabel);
        form.setPadding(new Insets(8));
        return form;
    }

    private TableColumn<Employee, ?> column(String title, String property) {
        TableColumn<Employee, Object> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        return col;
    }
}
