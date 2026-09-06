package managestore.client.ui;

import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
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
import managestore.common.model.Employee;
import managestore.common.model.Role;
import managestore.common.protocol.BranchDto;
import managestore.common.protocol.BranchListResponse;
import managestore.common.protocol.EmployeeAddRequest;
import managestore.common.protocol.EmployeeAddResponse;
import managestore.common.protocol.EmployeeDeleteRequest;
import managestore.common.protocol.EmployeeDeleteResponse;
import managestore.common.protocol.EmployeeListResponse;
import managestore.common.protocol.MessageType;

import java.util.Optional;

/** Employee roster for the network; the "add employee" form and delete action are admin-only. */
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

        // Whenever the server answers a roster request — the initial one below, or a
        // manual refresh — replace the whole table with the latest list.
        connection.on(MessageType.EMPLOYEE_LIST_RESPONSE, message -> {
            EmployeeListResponse response = message.readPayload(connection.getGson(), EmployeeListResponse.class);
            table.getItems().setAll(response.getEmployees());
        });
        // Initial load of the roster when this panel is first built.
        connection.send(MessageType.EMPLOYEE_LIST_REQUEST, new Object());

        // Unlike Inventory or Customers, the roster has no live push. Nothing subscribes
        // to employee additions. So if a second admin has this tab open while a first
        // admin adds someone, the second admin cannot see the change without logging
        // out and back in. A Refresh button is the simple fix here, the same one
        // LogsPanel already uses for the same reason.
        Button refreshButton = new Button("Refresh");
        refreshButton.setOnAction(e -> connection.send(MessageType.EMPLOYEE_LIST_REQUEST, new Object()));
        HBox refreshBar = new HBox(8, refreshButton);
        refreshBar.getStyleClass().add("toolbar");
        refreshBar.setPadding(new Insets(8));

        // Role-based visibility: only admins get the delete button and add-employee form.
        if (currentEmployee.getRole() == Role.ADMIN) {
            refreshBar.getChildren().add(buildDeleteButton(table));
        }

        BorderPane pane = new BorderPane();
        pane.setTop(refreshBar);
        pane.setCenter(table);
        if (currentEmployee.getRole() == Role.ADMIN) {
            pane.setBottom(buildAddEmployeeForm());
        } else {
            // Non-admins see an explanatory note instead of the form. This makes clear
            // why the option is missing, instead of looking like a bug.
            Label adminOnlyNote = new Label("Only an ADMIN account can add new employees — log in as admin (e.g. admin / Admin1234 on the demo server) to use this form.");
            adminOnlyNote.setWrapText(true);
            adminOnlyNote.setStyle("-fx-text-fill: -muted; -fx-padding: 10px;");
            pane.setBottom(adminOnlyNote);
        }
        return pane;
    }

    /**
     * Admin-only, same as the add form. The button is disabled unless a row is
     * selected, and also disabled for the admin's own row.
     *
     * The server refuses self-deletion too — a logged-in admin should not be able
     * to delete the account they are currently using. We also check it here so we
     * skip a pointless round trip and confirmation dialog for a request that would
     * only get rejected anyway.
     */
    private Button buildDeleteButton(TableView<Employee> table) {
        Button deleteButton = new Button("Delete Selected");
        deleteButton.getStyleClass().add("secondary");
        // The button stays disabled when nothing is selected, or when the selected
        // row is the logged-in admin's own account. This binding is a JavaFX feature
        // that re-checks itself automatically every time the table selection changes,
        // so we don't have to update the button by hand.
        deleteButton.disableProperty().bind(javafx.beans.binding.Bindings.createBooleanBinding(
                () -> {
                    Employee selected = table.getSelectionModel().getSelectedItem();
                    return selected == null || selected.getEmployeeNumber().equals(currentEmployee.getEmployeeNumber());
                },
                table.getSelectionModel().selectedItemProperty()));

        // This is the reply to our own EMPLOYEE_DELETE_REQUEST. On success, we re-fetch
        // the roster so the deleted employee disappears from the table. On failure,
        // we show why.
        connection.on(MessageType.EMPLOYEE_DELETE_RESPONSE, message -> {
            EmployeeDeleteResponse response = message.readPayload(connection.getGson(), EmployeeDeleteResponse.class);
            if (response.isSuccess()) {
                connection.send(MessageType.EMPLOYEE_LIST_REQUEST, new Object());
            } else {
                new Alert(Alert.AlertType.ERROR, "Could not delete employee: " + response.getErrorMessage()).showAndWait();
            }
        });

        // "Delete Selected" clicked. Confirm with the user before doing anything
        // irreversible. Only send the delete request if they explicitly click OK.
        deleteButton.setOnAction(e -> {
            Employee selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) {
                return;
            }
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                    "Delete " + selected.getFullName() + " (" + selected.getEmployeeNumber() + ")? "
                            + "This also removes their login — they won't be able to sign in again.",
                    ButtonType.OK, ButtonType.CANCEL);
            confirm.setTitle("Delete employee");
            confirm.setHeaderText(null);
            Optional<ButtonType> choice = confirm.showAndWait();
            if (choice.isPresent() && choice.get() == ButtonType.OK) {
                connection.send(MessageType.EMPLOYEE_DELETE_REQUEST, new EmployeeDeleteRequest(selected.getEmployeeNumber()));
            }
        });

        return deleteButton;
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
        // Fill in the branch dropdown once the server answers, defaulting to the first branch.
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
        PasswordRevealField passwordField = new PasswordRevealField();
        passwordField.setPromptText("Password");
        // The actual rule is enforced server-side by PasswordPolicy, and a rejected password
        // already comes back with a specific reason. This tooltip just makes that same rule
        // visible up front on the admin screen, instead of only after a failed attempt.
        javafx.scene.control.Tooltip.install(passwordField.getNode(),
                new javafx.scene.control.Tooltip("Password policy: at least 6 characters, including at least one letter and one digit."));
        Button addButton = new Button("Add Employee");
        Label statusLabel = new Label();
        statusLabel.getStyleClass().add("status-label");

        // This is the reply to our own EMPLOYEE_ADD_REQUEST, sent from the "Add Employee" button below.
        connection.on(MessageType.EMPLOYEE_ADD_RESPONSE, message -> {
            EmployeeAddResponse response = message.readPayload(connection.getGson(), EmployeeAddResponse.class);
            UiUtil.setStatus(statusLabel, response.isSuccess(),
                    response.isSuccess() ? "Employee added." : "Failed: " + response.getErrorMessage());
            if (response.isSuccess()) {
                // Refresh the roster table so the new employee shows up immediately.
                connection.send(MessageType.EMPLOYEE_LIST_REQUEST, new Object());
                // Clear the whole form, not just the password field. If we left the
                // previous employee's number, personal ID, and username sitting there,
                // the user might click "Add Employee" again out of habit and hit a
                // duplicate-employee-number error, instead of typing a fresh entry.
                numberField.clear();
                nameField.clear();
                personalIdField.clear();
                phoneField.clear();
                accountField.clear();
                usernameField.clear();
                passwordField.clear();
            }
            addButton.setDisable(false);
        });

        // "Add Employee" clicked. First check that a branch is selected — this guards
        // against the edge case where the branch list has not loaded yet. Then send
        // the full form as one request. Field-level checks, like a duplicate employee
        // number, are left to the server. Its response comes back above.
        addButton.setOnAction(e -> {
            BranchDto branch = branchChoice.getValue();
            if (branch == null) {
                UiUtil.setStatus(statusLabel, false, "No branch available to assign.");
                return;
            }
            // Disable the button until EMPLOYEE_ADD_RESPONSE comes back, so a fast double-click
            // can't send this same new employee twice before the first attempt is answered.
            addButton.setDisable(true);
            connection.send(MessageType.EMPLOYEE_ADD_REQUEST, new EmployeeAddRequest(
                    numberField.getText().trim(), nameField.getText().trim(), personalIdField.getText().trim(),
                    phoneField.getText().trim(), accountField.getText().trim(), branch.getId(),
                    roleChoice.getValue().name(), usernameField.getText().trim(), passwordField.getText()));
        });

        HBox form = new HBox(6, numberField, nameField, personalIdField, phoneField, accountField, branchChoice,
                roleChoice, usernameField, passwordField.getNode(), addButton, statusLabel);
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
