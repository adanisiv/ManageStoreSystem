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
import managestore.common.protocol.BranchDto;
import managestore.common.protocol.BranchListResponse;
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
        table.getColumns().add(column("Personal ID", "personalId"));
        table.getColumns().add(column("Phone", "phone"));
        table.getColumns().add(column("Account #", "accountNumber"));
        table.getColumns().add(column("Branch", "branchId"));
        table.getColumns().add(column("Role", "role"));

        connection.on(MessageType.EMPLOYEE_LIST_RESPONSE, message -> {
            EmployeeListResponse response = message.readPayload(connection.getGson(), EmployeeListResponse.class);
            table.getItems().setAll(response.getEmployees());
        });
        connection.send(MessageType.EMPLOYEE_LIST_REQUEST, new Object());

        // Unlike Inventory/Customers, the roster has no live push (nothing subscribes to employee
        // additions), so if a second admin is looking at this tab while a first admin adds someone,
        // the second admin has no way to see it short of logging out and back in — a Refresh button
        // is the minimal fix, matching the one LogsPanel already has for the same reason.
        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> connection.send(MessageType.EMPLOYEE_LIST_REQUEST, new Object()));
        HBox refreshBar = new HBox(refreshButton);
        refreshBar.getStyleClass().add("toolbar");
        refreshBar.setPadding(new Insets(8));

        BorderPane pane = new BorderPane();
        pane.setTop(refreshBar);
        pane.setCenter(table);
        if (currentEmployee.getRole() == Role.ADMIN) {
            pane.setBottom(buildAddEmployeeForm());
        } else {
            Label adminOnlyNote = new Label("Only an ADMIN account can add new employees — log in as admin (e.g. admin / Admin1234 on the demo server) to use this form.");
            adminOnlyNote.setWrapText(true);
            adminOnlyNote.setStyle("-fx-text-fill: -muted; -fx-padding: 10px;");
            pane.setBottom(adminOnlyNote);
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
        ChoiceBox<BranchDto> branchChoice = new ChoiceBox<>();
        connection.on(MessageType.BRANCH_LIST_RESPONSE, message -> {
            BranchListResponse response = message.readPayload(connection.getGson(), BranchListResponse.class);
            branchChoice.setItems(FXCollections.observableArrayList(response.getBranches()));
            if (!response.getBranches().isEmpty()) {
                branchChoice.getSelectionModel().selectFirst();
            }
        });
        connection.send(MessageType.BRANCH_LIST_REQUEST, new Object());
        ChoiceBox<Role> roleChoice = new ChoiceBox<>(FXCollections.observableArrayList(Role.values()));
        roleChoice.getSelectionModel().selectFirst();
        TextField usernameField = new TextField();
        usernameField.setPromptText("Username");
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        Button addButton = new Button("Add Employee");
        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("status-label");

        connection.on(MessageType.EMPLOYEE_ADD_RESPONSE, message -> {
            EmployeeAddResponse response = message.readPayload(connection.getGson(), EmployeeAddResponse.class);
            UiUtil.setStatus(statusLabel, response.isSuccess(),
                    response.isSuccess() ? "Employee added." : "Failed: " + response.getErrorMessage());
            if (response.isSuccess()) {
                connection.send(MessageType.EMPLOYEE_LIST_REQUEST, new Object());
                // Clear the whole form, not just the obviously-sensitive password field: leaving
                // the previous employee's number/personal ID/username sitting there invites
                // clicking "Add Employee" again by habit and hitting the now-rejected duplicate
                // employee number, instead of just typing the next one.
                numberField.clear();
                nameField.clear();
                personalIdField.clear();
                phoneField.clear();
                accountField.clear();
                usernameField.clear();
                passwordField.clear();
            }
        });

        addButton.setOnAction(e -> {
            BranchDto branch = branchChoice.getValue();
            if (branch == null) {
                UiUtil.setStatus(statusLabel, false, "No branch available to assign.");
                return;
            }
            connection.send(MessageType.EMPLOYEE_ADD_REQUEST, new EmployeeAddRequest(
                    numberField.getText().trim(), nameField.getText().trim(), personalIdField.getText().trim(),
                    phoneField.getText().trim(), accountField.getText().trim(), branch.getId(),
                    roleChoice.getValue().name(), usernameField.getText().trim(), passwordField.getText()));
        });

        HBox form = new HBox(6, numberField, nameField, personalIdField, phoneField, accountField, branchChoice,
                roleChoice, usernameField, passwordField, addButton, statusLabel);
        form.getStyleClass().add("toolbar");
        form.setPadding(new Insets(8));
        return form;
    }

    private TableColumn<Employee, ?> column(String title, String property) {
        TableColumn<Employee, Object> col = new TableColumn<>(title);
        col.setCellValueFactory(new PropertyValueFactory<>(property));
        return col;
    }
}
