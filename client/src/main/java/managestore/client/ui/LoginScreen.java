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
        // Without explicit column constraints, GridPane is free to shrink the label column
        // below its preferred width whenever space is tight. This happens once the password
        // row holds the wider eye-toggle control. When that happens, the labels render as
        // "..." with no visible text at all, instead of just looking a bit cramped.
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

        // This is the reply to the LOGIN_REQUEST we send below. On success, we hand off
        // to the main window with the now-authenticated employee. On failure, we re-enable
        // the button and show why the login failed (wrong password, unknown user, and so on),
        // so the user can try again.
        connection.on(MessageType.LOGIN_RESPONSE, message -> {
            LoginResponse response = message.readPayload(connection.getGson(), LoginResponse.class);
            if (response.isSuccess()) {
                new MainWindow(connection, response.getEmployee()).show(stage);
            } else {
                loginButton.setDisable(false);
                statusLabel.setText(response.getErrorMessage());
            }
        });

        // When "Log In" is clicked, we first validate the form locally, so obviously-bad
        // input never even reaches the network. Only then do we open the connection and
        // send the login request.
        loginButton.setOnAction(e -> {
            statusLabel.setText("");

            // Both username and password must be non-empty before attempting anything.
            if (usernameField.getText().trim().isEmpty() || passwordField.getText().isEmpty()) {
                statusLabel.setText("Enter both a username and a password.");
                return;
            }
            int port;
            try {
                // The port must parse as a number. We catch the exception here so the
                // raw NumberFormatException becomes a friendly message instead.
                port = Integer.parseInt(portField.getText().trim());
            } catch (NumberFormatException ex) {
                statusLabel.setText("Port must be a number, e.g. " + defaultPort + ".");
                return;
            }
            if (hostField.getText().trim().isEmpty()) {
                statusLabel.setText("Enter a server host, e.g. localhost.");
                return;
            }

            // Disable the button immediately so a slow/hanging connection attempt can't be
            // triggered twice by an impatient double-click.
            loginButton.setDisable(true);
            // connect() always opens a brand-new socket and reader thread. So before retrying,
            // we must close any previous connection first. Otherwise, after a failed login and
            // a retry, the old socket and reader thread would keep running alongside the new
            // ones, and both would race to read the next response on the same shared
            // `channel` field.
            connection.close();
            try {
                // Open the socket and immediately send the login request. The response
                // is handled by the listener registered just above.
                connection.connect(hostField.getText().trim(), port);
                connection.send(MessageType.LOGIN_REQUEST,
                        new LoginRequest(usernameField.getText().trim(), passwordField.getText()));
            } catch (IOException ex) {
                // The connection itself failed (bad host or port, server not running, and so
                // on). Re-enable the button so the user can fix the fields and try again.
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
