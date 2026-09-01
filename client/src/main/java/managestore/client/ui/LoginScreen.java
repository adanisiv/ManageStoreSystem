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
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
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
        usernameField.setPromptText("e.g. seller1");
        PasswordField passwordField = new PasswordField();
        Label statusLabel = new Label();
        statusLabel.getStyleClass().addAll("status-label", "error");
        Button loginButton = new Button("Log In");
        loginButton.setDefaultButton(true);

        GridPane grid = new GridPane();
        grid.setAlignment(Pos.CENTER);
        grid.setHgap(12);
        grid.setVgap(12);

        grid.add(new Label("Server host:"), 0, 0);
        grid.add(hostField, 1, 0);
        grid.add(new Label("Port:"), 0, 1);
        grid.add(portField, 1, 1);
        grid.add(new Label("Username:"), 0, 2);
        grid.add(usernameField, 1, 2);
        grid.add(new Label("Password:"), 0, 3);
        grid.add(passwordField, 1, 3);

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

        Label title = new Label("ManageStoreSystem");
        title.setId("login-title");
        Label subtitle = new Label("Store chain management — sign in");
        subtitle.setId("login-subtitle");

        VBox card = new VBox(16, title, subtitle, grid, loginButton, statusLabel);
        card.setId("login-card");
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(10));
        VBox.setMargin(loginButton, new Insets(6, 0, 0, 0));

        StackPane backdrop = new StackPane(card);
        backdrop.setId("login-backdrop");

        Scene scene = new Scene(backdrop, 460, 520);
        scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
        stage.setTitle("ManageStoreSystem — Login");
        stage.setScene(scene);
        stage.show();
    }

    static void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message);
        alert.setTitle(title);
        alert.showAndWait();
    }
}
