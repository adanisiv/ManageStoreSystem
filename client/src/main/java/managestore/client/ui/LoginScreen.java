package managestore.client.ui;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import managestore.client.net.ServerConnection;
import managestore.common.protocol.LoginRequest;
import managestore.common.protocol.LoginResponse;
import managestore.common.protocol.MessageType;

import java.io.IOException;

/** The login screen: authenticates against the server before any other screen is shown. */
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
        PasswordRevealField passwordField = new PasswordRevealField();
        Label statusLabel = new Label();
        statusLabel.getStyleClass().addAll("status-label", "error");
        statusLabel.setWrapText(true);
        Button loginButton = new Button("Log In");
        loginButton.setId("login-button");
        loginButton.setMaxWidth(Double.MAX_VALUE);
        loginButton.setDefaultButton(true);

        GridPane grid = new GridPane();
        grid.setAlignment(Pos.CENTER);
        grid.setHgap(12);
        grid.setVgap(12);
        // Without explicit column constraints, GridPane is free to shrink the label column below
        // its preferred width whenever the row column is under space pressure (e.g. the password
        // row, once it holds the wider eye-toggle control) — the labels then render as "..." with
        // no visible text at all, rather than just being a bit cramped.
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(Region.USE_PREF_SIZE);
        labelColumn.setHalignment(HPos.RIGHT);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        fieldColumn.setFillWidth(true);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);

        grid.add(new Label("Server host:"), 0, 0);
        grid.add(hostField, 1, 0);
        grid.add(new Label("Port:"), 0, 1);
        grid.add(portField, 1, 1);
        grid.add(new Label("Username:"), 0, 2);
        grid.add(usernameField, 1, 2);
        grid.add(new Label("Password:"), 0, 3);
        grid.add(passwordField.getNode(), 1, 3);

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

            if (usernameField.getText().trim().isEmpty() || passwordField.getText().isEmpty()) {
                statusLabel.setText("Enter both a username and a password.");
                return;
            }
            int port;
            try {
                port = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ex) {
                statusLabel.setText("Port must be a number, e.g. " + defaultPort + ".");
                return;
            }
            if (hostField.getText().trim().isEmpty()) {
                statusLabel.setText("Enter a server host, e.g. localhost.");
                return;
            }

            loginButton.setDisable(true);
            // A retry after a failed attempt used to call connect() again without closing the
            // previous socket/reader thread first — connect() always opens a brand-new one, so a
            // failed-then-retried login left a stale connection and reader thread running, both
            // now racing the new one on the same shared `channel` field for who reads the next
            // response.
            connection.close();
            try {
                connection.connect(hostField.getText().trim(), port);
                connection.send(MessageType.LOGIN_REQUEST,
                        new LoginRequest(usernameField.getText().trim(), passwordField.getText()));
            } catch (IOException ex) {
                loginButton.setDisable(false);
                statusLabel.setText("Could not connect: " + ex.getMessage());
            }
        });

        Label logo = new Label("🏬");
        logo.setId("login-logo");
        Label title = new Label("ManageStoreSystem");
        title.setId("login-title");
        Label subtitle = new Label("Store chain management — sign in");
        subtitle.setId("login-subtitle");

        VBox card = new VBox(14, logo, title, subtitle, grid, loginButton, statusLabel);
        card.setId("login-card");
        card.setAlignment(Pos.CENTER);
        card.setPadding(new Insets(10));
        VBox.setMargin(loginButton, new Insets(6, 0, 0, 0));

        StackPane backdrop = new StackPane(card);
        backdrop.setId("login-backdrop");

        Scene scene = new Scene(backdrop, 500, 520);
        scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());
        stage.setTitle("ManageStoreSystem — Login");
        stage.setScene(scene);
        stage.show();
    }
}
