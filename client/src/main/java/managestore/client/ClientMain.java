package managestore.client;

import javafx.application.Application;
import javafx.stage.Stage;
import managestore.client.net.ServerConnection;
import managestore.client.ui.LoginScreen;
import managestore.common.protocol.NetworkDefaults;

public class ClientMain extends Application {

    @Override
    public void start(Stage primaryStage) {
        ServerConnection connection = new ServerConnection();
        new LoginScreen(connection, NetworkDefaults.DEFAULT_PORT).show(primaryStage);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
