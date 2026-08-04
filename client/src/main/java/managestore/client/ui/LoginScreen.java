package managestore.client.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;
import managestore.client.net.ServerConnection;
import managestore.common.protocol.LoginRequest;
import managestore.common.protocol.LoginResponse;
import managestore.common.protocol.MessageType;

import java.io.IOException;

/** The brief's "login screen with a user authentication interface". */
public class LoginScreen {

    private final ServerConnection connection;
    private final int defaultPort;

    public LoginScreen(ServerConnection connection, int defaultPort) {
        this.connection = connection;
        this.defaultPort = defaultPort;
    }

    public void show(Stage stage) {
        TextField hostField = new TextField("localhost");
        TextField portField = new TextField(String.valueOf(defaultPort));
        TextField usernameField = new TextField();
        PasswordField passwordField = new PasswordField();
        Label statusLabel = new Label();
        statusLabel.setStyle("-fx-text-fill: crimson;");
        Button loginButton = new Button("Log In");

        GridPane grid = new GridPane();
        grid.setAlignment(Pos.CENTER);
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(25));

        grid.add(new Label("Server host:"), 0, 0);
        grid.add(hostField, 1, 0);
        grid.add(new Label("Port:"), 0, 1);
        grid.add(portField, 1, 1);
        grid.add(new Label("Username:"), 0, 2);
        grid.add(usernameField, 1, 2);
        grid.add(new Label("Password:"), 0, 3);
        grid.add(passwordField, 1, 3);
        grid.add(loginButton, 1, 4);
        grid.add(statusLabel, 0, 5, 2, 1);

        connection.on(MessageType.LOGIN_RESPONSE, message -> {
            LoginResponse response = message.readPayload(connection.getGson(), LoginResponse.class);
            if (response.isSuccess()) {
                new MainWindow(connection, response.getEmployee()).show(stage);
            } else {
                loginButton.setDisable(false);
                statusLabel.setText(response.getErrorMessage());
            }
        });

        loginButton.setOnAction(e -> {
            statusLabel.setText("");
            loginButton.setDisable(true);
            try {
                connection.connect(hostField.getText().trim(), Integer.parseInt(portField.getText().trim()));
                connection.send(MessageType.LOGIN_REQUEST,
                        new LoginRequest(usernameField.getText().trim(), passwordField.getText()));
            } catch (IOException | NumberFormatException ex) {
                loginButton.setDisable(false);
                statusLabel.setText("Could not connect: " + ex.getMessage());
            }
        });

        stage.setTitle("ManageStoreSystem — Login");
        stage.setScene(new Scene(grid, 380, 260));
        stage.show();
    }

    static void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setTitle(title);
        alert.showAndWait();
    }
}
