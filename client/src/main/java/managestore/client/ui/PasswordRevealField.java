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
 * A password entry field with an eye icon that briefly reveals the plain text, then hides it
 * again by itself. We do this instead of a normal show/hide toggle because a toggle could be
 * left in the "show" state by accident, leaving the password visible on screen indefinitely.
 *
 * <p>JavaFX has no built-in "reveal" mode on {@link PasswordField}, so we build one ourselves:
 * a {@link PasswordField} and a plain {@link TextField} sit stacked in the same spot, with their
 * text bound together, and only one of the two is ever visible at a time.
 */
class PasswordRevealField {

    private static final Duration REVEAL_DURATION = Duration.seconds(3);

    private final PasswordField passwordField = new PasswordField();
    private final TextField plainField = new TextField();
    private final Button toggleButton = new Button("👁"); // eye emoji
    private final HBox root;
    private final PauseTransition hideAfterDelay = new PauseTransition(REVEAL_DURATION);

    PasswordRevealField() {
        // This two-way binding keeps both fields' text in sync at all times, no matter
        // which one the user is actually typing into. So switching which one is visible
        // never loses or duplicates a keystroke.
        plainField.textProperty().bindBidirectional(passwordField.textProperty());
        // Start with the plain-text field hidden and skipped during layout. The masked
        // PasswordField is the one shown by default.
        plainField.setManaged(false);
        plainField.setVisible(false);
        // Both fields need an explicit unbounded max width. Without it, the StackPane
        // (and everything else here) only sizes itself to the fields' own preferred
        // width, instead of filling whatever room the surrounding layout gives it.
        // A plain TextField gets this "fill the available width" behavior for free
        // when placed directly in a GridPane or HBox cell, but here we set it manually.
        passwordField.setMaxWidth(Double.MAX_VALUE);
        plainField.setMaxWidth(Double.MAX_VALUE);

        StackPane fieldStack = new StackPane(passwordField, plainField);
        fieldStack.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(fieldStack, Priority.ALWAYS);

        toggleButton.getStyleClass().add("icon-toggle");
        toggleButton.setFocusTraversable(false);
        // Clicking the eye icon flips between showing and hiding the plain text,
        // depending on which state we are currently in.
        toggleButton.setOnAction(e -> {
            if (plainField.isVisible()) {
                hide();
            } else {
                reveal();
            }
        });
        // reveal() starts a timer below. When that timer runs out on its own, we hide
        // the password automatically. This is what stops it from staying visible forever.
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
        // Move keyboard focus to the now-visible field, and put the caret at the end
        // of the text, so the user can keep typing right where they left off.
        plainField.requestFocus();
        plainField.positionCaret(plainField.getText().length());
        toggleButton.setText("🙈"); // "see-no-evil" — click to hide again
        // Start (or restart) the countdown to auto-hide. playFromStart() resets the
        // timer if the user clicked reveal again while a previous timer was still running.
        hideAfterDelay.playFromStart();
    }

    private void hide() {
        // Cancel any pending auto-hide timer. We are already hiding the password, so
        // there's no need for the timer to fire again later — this matters when hide()
        // was triggered manually (by the click), not by the timer itself.
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
