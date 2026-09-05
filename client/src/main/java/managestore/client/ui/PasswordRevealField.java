package managestore.client.ui;

import javafx.animation.PauseTransition;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * A password entry field with an eye toggle that reveals the plaintext briefly, then hides it
 * again on its own — a persistent show/hide toggle would risk leaving a password sitting in
 * plain view on screen indefinitely if the user forgets to switch it back.
 *
 * <p>Implemented as a {@link PasswordField} and a plain {@link TextField} stacked in the same
 * spot with their text bound together, only one ever visible at a time — JavaFX has no built-in
 * "reveal" mode on PasswordField itself.
 */
class PasswordRevealField {

    private static final Duration REVEAL_DURATION = Duration.seconds(3);

    private final PasswordField passwordField = new PasswordField();
    private final TextField plainField = new TextField();
    private final Button toggleButton = new Button("👁"); // eye emoji
    private final HBox root;
    private final PauseTransition hideAfterDelay = new PauseTransition(REVEAL_DURATION);

    PasswordRevealField() {
        plainField.textProperty().bindBidirectional(passwordField.textProperty());
        plainField.setManaged(false);
        plainField.setVisible(false);
        // Both need an explicit unbounded max width, or the StackPane (and everything else here)
        // only ever sizes to the fields' own preferred width instead of filling whatever room the
        // surrounding layout actually gives it — the same "fillWidth" behavior a plain TextField
        // gets for free when placed directly in a GridPane/HBox cell.
        passwordField.setMaxWidth(Double.MAX_VALUE);
        plainField.setMaxWidth(Double.MAX_VALUE);

        StackPane fieldStack = new StackPane(passwordField, plainField);
        fieldStack.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(fieldStack, Priority.ALWAYS);

        toggleButton.getStyleClass().add("icon-toggle");
        toggleButton.setFocusTraversable(false);
        toggleButton.setOnAction(e -> {
            if (plainField.isVisible()) {
                hide();
            } else {
                reveal();
            }
        });
        hideAfterDelay.setOnFinished(e -> hide());

        root = new HBox(4, fieldStack, toggleButton);
        root.setMaxWidth(Double.MAX_VALUE);
    }

    private void reveal() {
        passwordField.setVisible(false);
        passwordField.setManaged(false);
        plainField.setVisible(true);
        plainField.setManaged(true);
        plainField.requestFocus();
        plainField.positionCaret(plainField.getText().length());
        toggleButton.setText("🙈"); // "see-no-evil" — click to hide again
        hideAfterDelay.playFromStart();
    }

    private void hide() {
        hideAfterDelay.stop();
        plainField.setVisible(false);
        plainField.setManaged(false);
        passwordField.setVisible(true);
        passwordField.setManaged(true);
        toggleButton.setText("👁");
    }

    Node getNode() {
        return root;
    }

    String getText() {
        return passwordField.getText();
    }

    void setPromptText(String text) {
        passwordField.setPromptText(text);
        plainField.setPromptText(text);
    }

    void clear() {
        passwordField.clear();
    }
}
