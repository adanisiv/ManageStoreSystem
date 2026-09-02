package managestore.client.ui;

import javafx.scene.control.Label;

/** Small shared helpers so every panel's status messages look consistent (green for success, red for failure). */
final class UiUtil {

    private UiUtil() {
    }

    static void setStatus(Label label, boolean success, String text) {
        label.setText((success ? "✅ " : "⚠️ ") + text);
        label.getStyleClass().removeAll("success", "error");
        label.getStyleClass().add(success ? "success" : "error");
    }
}
