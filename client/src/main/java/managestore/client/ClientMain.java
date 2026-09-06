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
        // This is the one ServerConnection instance the whole client shares.
        // It gets passed down into every screen that needs to talk to the server.
        ServerConnection connection = new ServerConnection();
        // We register this listener once, globally, instead of in each screen.
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
        // Start the app on the login screen. The connection is not opened yet.
        // It opens once the user submits a host and port on that screen.
        new LoginScreen(connection, NetworkDefaults.DEFAULT_PORT).show(primaryStage);
    }

    public static void main(String[] args) {
        // Hands control to the JavaFX runtime, which will create the Application
        // instance and call start(Stage) above once the toolkit is ready.
        launch(args);
    }
}
