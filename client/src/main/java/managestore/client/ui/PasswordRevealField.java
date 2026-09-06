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
        // Bidirectional binding keeps both fields' text in sync at all times, whichever
        // one the user is actually typing into, so swapping which one is visible never
        // loses or duplicates a keystroke.
        plainField.textProperty().bindBidirectional(passwordField.textProperty());
        // Start with the plain-text field hidden and excluded from layout — the masked
        // PasswordField is what's shown by default.
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
        // Clicking the eye icon toggles between showing and hiding the plaintext,
        // based on whichever state we're currently in.
        toggleButton.setOnAction(e -> {
            if (plainField.isVisible()) {
                hide();
            } else {
                reveal();
            }
        });
        // When the auto-hide timer (started in reveal()) elapses on its own, hide the
        // password automatically — this is what stops it from staying visible forever.
        hideAfterDelay.setOnFinished(e -> hide());

        root = new HBox(4, fieldStack, toggleButton);
        root.setMaxWidth(Double.MAX_VALUE);
    }

    private void reveal() {
        // Swap which field is shown: hide the masked field, show the plain-text one.
        passwordField.setVisible(false);
        passwordField.setManaged(false);
        plainField.setVisible(true);
        plainField.setManaged(true);
        // Move keyboard focus to the now-visible field and put the caret at the end,
        // so the user can keep typing right where they left off.
        plainField.requestFocus();
        plainField.positionCaret(plainField.getText().length());
        toggleButton.setText("🙈"); // "see-no-evil" — click to hide again
        // (Re)start the countdown to auto-hide; playFromStart() resets it if the user
        // clicked reveal again while a previous timer was still running.
        hideAfterDelay.playFromStart();
    }

    private void hide() {
        // Cancel any pending auto-hide timer — we're already hiding, no need for it to
        // fire again later (e.g. if hide() was triggered manually, not by the timer).
        hideAfterDelay.stop();
        // Swap back: hide the plain-text field, show the masked one again.
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
