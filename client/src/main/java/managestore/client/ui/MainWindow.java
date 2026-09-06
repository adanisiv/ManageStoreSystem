package managestore.client.ui;

import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import managestore.client.net.ServerConnection;
import managestore.common.model.Employee;
import managestore.common.model.Role;

/**
 * The main app window shown after login. It is one tabbed window holding every
 * screen, instead of several separate windows.
 *
 * <p>Which employee is logged in, and their role, is shown in the header. The role
 * also decides which tabs are visible. For example, an admin sees the System Log
 * tab that other roles don't. But an admin loses the Inventory tab that every
 * branch-assigned employee gets, because ADMIN is the one role with no branch
 * of its own, so there is no stock to show them.
 */
public class MainWindow {

    private final ServerConnection connection;
    private final Employee employee;

    public MainWindow(ServerConnection connection, Employee employee) {
        this.connection = connection;
        this.employee = employee;
    }

    public void show(Stage stage) {
        Label header = new Label(employee.getFullName() + "  •  " + employee.getRole()
                + (employee.getBranchId() != null ? "  •  Branch " + employee.getBranchId() : ""));
        header.setId("header-bar");
        header.setMaxWidth(Double.MAX_VALUE);

        TabPane tabs = new TabPane();
        // Inventory is always scoped to one branch (see ClientHandler.requireLoginAndBranch).
        // An employee with no branchId — currently only ADMIN — has no branch inventory to show.
        // Without this check, that employee's Inventory tab would immediately fail its startup
        // snapshot request, and show a "not assigned to a branch" error dialog right on login.
        if (employee.getBranchId() != null) {
            tabs.getTabs().add(tab("📦 Inventory", new InventoryPanel(connection).build()));
        }
        tabs.getTabs().add(tab("👥 Customers", new CustomersPanel(connection).build()));
        tabs.getTabs().add(tab("📊 Reports", new ReportsPanel(connection).build()));
        tabs.getTabs().add(tab("💬 Chat", new ChatPanel(connection, employee).build()));
        // The Employees tab itself is visible to every role. EmployeesPanel handles the
        // finer-grained, role-based visibility internally (add and delete are admin-only).
        tabs.getTabs().add(tab("🧑‍💼 Employees", new EmployeesPanel(connection, employee).build()));
        // The System Log tab, on the other hand, is left out of the tab bar entirely
        // unless the logged-in employee is an admin.
        if (employee.getRole() == Role.ADMIN) {
            tabs.getTabs().add(tab("📋 System Log", new LogsPanel(connection).build()));
        }

        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(tabs);

        Scene scene = new Scene(root, 960, 640);
        scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
        stage.setTitle("ManageStoreSystem — " + employee.getFullName());
        stage.setScene(scene);
        stage.show();
    }

    private Tab tab(String title, javafx.scene.Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }
}
