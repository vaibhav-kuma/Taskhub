package com.taskhub.desktop.util;

import com.taskhub.desktop.api.ApiException;
import com.taskhub.desktop.api.Dtos;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

/** Presentation helpers shared by the controllers and dialogs. */
public final class UiUtils {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private UiUtils() {
    }

    public static String formatDate(LocalDate date) {
        return date == null ? "-" : DATE.format(date);
    }

    public static String formatInstant(Instant instant) {
        if (instant == null) {
            return "-";
        }
        return DATE_TIME.format(instant.atZone(ZoneId.systemDefault()));
    }

    public static String formatDue(Dtos.Task task) {
        if (task.dueDate() == null) {
            return "-";
        }
        long days = ChronoUnit.DAYS.between(LocalDate.now(), task.dueDate());
        if (task.status() == Dtos.TaskStatus.DONE) {
            return formatDate(task.dueDate());
        }
        if (days < 0) {
            return formatDate(task.dueDate()) + " (" + Math.abs(days) + "d late)";
        }
        if (days == 0) {
            return formatDate(task.dueDate()) + " (today)";
        }
        if (days <= 7) {
            return formatDate(task.dueDate()) + " (in " + days + "d)";
        }
        return formatDate(task.dueDate());
    }

    public static String humanize(Enum<?> value) {
        if (value == null) {
            return "-";
        }
        String lower = value.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public static String statusStyleClass(Dtos.TaskStatus status) {
        return switch (status) {
            case TODO -> "badge-todo";
            case IN_PROGRESS -> "badge-progress";
            case BLOCKED -> "badge-blocked";
            case DONE -> "badge-done";
        };
    }

    public static String priorityStyleClass(Dtos.TaskPriority priority) {
        return switch (priority) {
            case LOW -> "badge-low";
            case MEDIUM -> "badge-medium";
            case HIGH -> "badge-high";
            case CRITICAL -> "badge-critical";
        };
    }

    public static String errorMessage(Throwable error) {
        if (error instanceof ApiException api) {
            return api.displayMessage();
        }
        String message = error.getMessage();
        return (message == null || message.isBlank()) ? "Something went wrong. Please try again." : message;
    }

    public static void info(Window owner, String title, String message) {
        show(owner, Alert.AlertType.INFORMATION, title, message);
    }

    public static void error(Window owner, String title, String message) {
        show(owner, Alert.AlertType.ERROR, title, message);
    }

    private static void show(Window owner, Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.getDialogPane().setMaxWidth(560);
        alert.showAndWait();
    }

    public static Optional<ButtonType> confirm(Window owner, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.CANCEL, ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle(title);
        alert.setHeaderText(null);
        return alert.showAndWait();
    }
}
