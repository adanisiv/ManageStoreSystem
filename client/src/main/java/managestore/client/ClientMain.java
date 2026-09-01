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
        ServerConnection connection = new ServerConnection();
        // Registered once, globally: every screen sends requests but none of them listen
        // for MessageType.ERROR individually, so without this a rejected request (e.g.
        // "insufficient stock", "only an admin can do that") would fail on the server and
        // the user would just see nothing happen, with no explanation.
        connection.on(MessageType.ERROR, message -> {
            ErrorMessage error = message.readPayload(connection.getGson(), ErrorMessage.class);
            Alert alert = new Alert(Alert.AlertType.ERROR, error.getMessage());
            alert.setTitle("Request failed");
            alert.setHeaderText(null);
            alert.showAndWait();
        });
        new LoginScreen(connection, NetworkDefaults.DEFAULT_PORT).show(primaryStage);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
