package managestore.client.ui;

import javafx.scene.control.Label;

/** Small shared helpers so every panel's status messages look consistent (green for success, red for failure). */
final class UiUtil {

    private UiUtil() {
    }

    static void setStatus(Label label, boolean success, String text) {
        // Prefix with a check mark or warning icon depending on outcome.
        label.setText((success ? "✅ " : "⚠️ ") + text);
        // Remove any previous success/error styling before applying the new one, so
        // repeated calls don't stack both CSS classes on the same label.
        label.getStyleClass().removeAll("success", "error");
        label.getStyleClass().add(success ? "success" : "error");
    }
}
