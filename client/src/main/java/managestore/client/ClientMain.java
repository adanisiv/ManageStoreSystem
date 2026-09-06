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
        // This is the one ServerConnection instance the whole client shares;
        // it gets passed down into every screen that needs to talk to the server.
        ServerConnection connection = new ServerConnection();
        // Registered once, globally: every screen sends requests but none of them listen
        // for MessageType.ERROR individually, so without this a rejected request (e.g.
        // "insufficient stock", "only an admin can do that") would fail on the server and
        // the user would just see nothing happen, with no explanation.
        connection.on(MessageType.ERROR, message -> {
            // Deserialize the error payload the server sent back into an ErrorMessage object.
            ErrorMessage error = message.readPayload(connection.getGson(), ErrorMessage.class);
            // Show it in a blocking error dialog so the user always sees why their action failed.
            Alert alert = new Alert(Alert.AlertType.ERROR, error.getMessage());
            alert.setTitle("Request failed");
            alert.setHeaderText(null);
            alert.showAndWait();
        });
        // Kick off the app on the login screen; the connection is not opened yet —
        // that happens once the user submits a host/port on that screen.
        new LoginScreen(connection, NetworkDefaults.DEFAULT_PORT).show(primaryStage);
    }

    public static void main(String[] args) {
        // Hands control to the JavaFX runtime, which will create the Application
        // instance and call start(Stage) above once the toolkit is ready.
        launch(args);
    }
}
