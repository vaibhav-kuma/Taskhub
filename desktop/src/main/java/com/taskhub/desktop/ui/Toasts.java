package com.taskhub.desktop.ui;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

/** Non-blocking, auto-dismissing notifications layered above the workspace. */
public final class Toasts {

    private Toasts() {
    }

    public static void success(VBox layer, String message) {
        show(layer, message, false);
    }

    public static void error(VBox layer, String message) {
        show(layer, message, true);
    }

    private static void show(VBox layer, String message, boolean error) {
        if (layer == null) {
            return;
        }
        Label toast = new Label(message);
        toast.setWrapText(true);
        toast.setMaxWidth(420);
        toast.getStyleClass().addAll("toast", error ? "toast-error" : "toast-success");
        VBox.setMargin(toast, new javafx.geometry.Insets(0, 0, 8, 0));

        layer.setAlignment(Pos.BOTTOM_RIGHT);
        layer.getChildren().add(toast);

        FadeTransition in = new FadeTransition(Duration.millis(160), toast);
        in.setFromValue(0);
        in.setToValue(1);
        PauseTransition wait = new PauseTransition(Duration.seconds(error ? 5 : 3));
        FadeTransition out = new FadeTransition(Duration.millis(260), toast);
        out.setFromValue(1);
        out.setToValue(0);
        out.setOnFinished(e -> layer.getChildren().remove(toast));

        new SequentialTransition(in, wait, out).play();
    }
}
