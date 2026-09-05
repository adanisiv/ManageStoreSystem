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
 * The main app window after login: one tabbed window holding every screen
 * rather than several separate ones. Which employee is logged in (and their
 * role) is shown in the header and gates which tabs are visible — an admin
 * sees the Employees and System Log tabs that other roles don't.
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
        tabs.getTabs().add(tab("📦 Inventory", new InventoryPanel(connection).build()));
        tabs.getTabs().add(tab("👥 Customers", new CustomersPanel(connection).build()));
        tabs.getTabs().add(tab("📊 Reports", new ReportsPanel(connection).build()));
        tabs.getTabs().add(tab("💬 Chat", new ChatPanel(connection, employee).build()));
        tabs.getTabs().add(tab("🧑‍💼 Employees", new EmployeesPanel(connection, employee).build()));
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
