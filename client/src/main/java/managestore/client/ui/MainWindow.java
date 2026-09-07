package managestore.client.ui;

import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import managestore.client.net.ServerConnection;
import managestore.common.model.Employee;
import managestore.common.model.Role;
import managestore.common.protocol.MessageType;

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
    private final Runnable onLogout;

    /**
     * @param onLogout called after this window has told the server to log out and closed its
     *                  connection. The caller is responsible for what "log back in" means next —
     *                  see {@code ClientMain}, which shows a fresh login screen backed by a brand
     *                  new connection, rather than reusing this one.
     */
    public MainWindow(ServerConnection connection, Employee employee, Runnable onLogout) {
        this.connection = connection;
        this.employee = employee;
        this.onLogout = onLogout;
    }

    public void show(Stage stage) {
        Label identityLabel = new Label(employee.getFullName() + "  •  " + employee.getRole()
                + (employee.getBranchId() != null ? "  •  Branch " + employee.getBranchId() : ""));
        // #header-bar (below) is now an HBox, not a Label, and -fx-text-fill/-fx-font-* from
        // that rule don't cascade down to this label automatically -- so it needs its own copy
        // of the same look the whole bar used to have when it was just this one label.
        identityLabel.setStyle("-fx-text-fill: white; -fx-font-size: 15px; -fx-font-weight: bold;");

        Button logoutButton = new Button("Log Out");
        logoutButton.setStyle("-fx-background-color: rgba(255,255,255,0.15); -fx-text-fill: white;");
        // Tell the server we're done first (releases the single-session slot right away, and
        // unregisters this connection from every observer it's subscribed to), then close our
        // side. We deliberately do NOT reuse this connection or this stage's listeners for the
        // next login: onLogout hands control back to ClientMain, which builds a brand new
        // ServerConnection for the fresh login screen. Every panel in this window (Inventory,
        // Reports, Chat, ...) registered its own listeners on this connection when it was built;
        // without a clean break, logging in again on the same connection would pile a second
        // full set of listeners on top of the first, and every future push from the server would
        // fire both sets.
        logoutButton.setOnAction(e -> {
            connection.send(MessageType.LOGOUT, null);
            connection.close();
            onLogout.run();
        });

        // A spacer that grows to fill the space between the identity label and the button,
        // pushing Log Out to the right edge of the header instead of sitting right next to the name.
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox header = new HBox(12, identityLabel, spacer, logoutButton);
        header.setId("header-bar");
        header.setAlignment(Pos.CENTER_LEFT);
        header.setMaxWidth(Double.MAX_VALUE);

        TabPane tabs = new TabPane();
        // Inventory is always scoped to one branch (see ClientHandler.requireLoginAndBranch).
        // An employee with no branchId — currently only ADMIN — has no branch inventory to show.
        // Without this check, that employee's Inventory tab would immediately fail its startup
        // snapshot request, and show a "not assigned to a branch" error dialog right on login.
        if (employee.getBranchId() != null) {
            tabs.getTabs().add(tab("📦 Inventory", new InventoryPanel(connection, employee).build()));
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
