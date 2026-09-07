package managestore.client;

import javafx.application.Application;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import managestore.client.net.ServerConnection;
import managestore.client.ui.LoginScreen;
import managestore.common.protocol.ErrorMessage;
import managestore.common.protocol.MessageType;
import managestore.common.protocol.NetworkDefaults;

public class ClientMain extends Application {

    @Override
    public void start(Stage primaryStage) {
        showLoginScreen(primaryStage);
    }

    /**
     * Shows a fresh login screen, backed by a brand-new {@link ServerConnection}. Called once
     * on startup, and again every time the user logs out.
     *
     * <p>Every screen that follows a successful login (MainWindow and every tab inside it)
     * registers its own listeners on whatever connection it's given, and nothing in this
     * client ever un-registers a listener. Reusing one connection across a logout would mean
     * the next login's screens pile a second full set of listeners on top of the first, and
     * every later push from the server fires both sets. Starting over with a new connection
     * — with an empty listener registry — avoids that entirely, at the small cost of a real
     * reconnect on the next login instead of reusing the still-open socket.
     */
    private void showLoginScreen(Stage stage) {
        ServerConnection connection = new ServerConnection();
        // We register this listener once per connection, instead of in each screen.
        // No screen listens for MessageType.ERROR on its own. Without this listener,
        // a rejected request (for example "insufficient stock" or "only an admin can
        // do that") would fail on the server, and the user would see nothing happen,
        // with no explanation of why.
        connection.on(MessageType.ERROR, message -> {
            // Deserialize the error payload the server sent back into an ErrorMessage object.
            ErrorMessage error = message.readPayload(connection.getGson(), ErrorMessage.class);
            // Show it in a blocking error dialog so the user always sees why their action failed.
            Alert alert = new Alert(Alert.AlertType.ERROR, error.getMessage());
            alert.setTitle("Request failed");
            alert.setHeaderText(null);
            alert.showAndWait();
        });
        // The connection is not opened yet. It opens once the user submits a host and port on
        // this screen. onLogout is how a later MainWindow gets back here, on a fresh connection.
        new LoginScreen(connection, NetworkDefaults.DEFAULT_PORT, () -> showLoginScreen(stage)).show(stage);
    }

    public static void main(String[] args) {
        // Hands control to the JavaFX runtime, which will create the Application
        // instance and call start(Stage) above once the toolkit is ready.
        launch(args);
    }
}
