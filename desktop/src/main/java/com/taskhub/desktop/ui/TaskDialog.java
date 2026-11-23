package com.taskhub.desktop.ui;

import com.taskhub.desktop.api.Dtos;
import com.taskhub.desktop.config.AppContext;
import com.taskhub.desktop.util.Async;
import com.taskhub.desktop.util.UiUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.stage.Window;
import javafx.util.StringConverter;

/** Create/edit form for a task. Client-side validation mirrors the server rules. */
public class TaskDialog extends Dialog<TaskDialog.Form> {

    public record Form(UUID projectId, String title, String description, Dtos.TaskStatus status,
                       Dtos.TaskPriority priority, LocalDate dueDate, UUID assigneeId, BigDecimal hours) {
    }

    private final AppContext context;
    private final Dtos.Task existing;

    private final ComboBox<Dtos.Project> projectBox = new ComboBox<>();
    private final TextField titleField = new TextField();
    private final TextArea descriptionArea = new TextArea();
    private final ComboBox<Dtos.TaskStatus> statusBox = new ComboBox<>();
    private final ComboBox<Dtos.TaskPriority> priorityBox = new ComboBox<>();
    private final DatePicker duePicker = new DatePicker();
    private final ComboBox<Dtos.UserSummary> assigneeBox = new ComboBox<>();
    private final TextField hoursField = new TextField();
    private final Label errorLabel = new Label();
    private final Label assigneeHint = new Label("Loading project members...");

    public TaskDialog(Window owner, AppContext context, List<Dtos.Project> projects,
                      Dtos.Task existing, UUID defaultProjectId) {
        this.context = context;
        this.existing = existing;

        initOwner(owner);
        setTitle(existing == null ? "New task" : "Edit task");
        setHeaderText(existing == null
                ? "Add a task to one of your projects."
                : "Update the task and save.");

        ButtonType save = new ButtonType(existing == null ? "Create task" : "Save changes",
                ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
        getDialogPane().getStyleClass().add("taskhub-dialog");
        if (owner != null && owner.getScene() != null) {
            getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        }

        projectBox.getItems().setAll(projects);
        projectBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Dtos.Project project) {
                return project == null ? "" : project.toString();
            }

            @Override
            public Dtos.Project fromString(String string) {
                return null;
            }
        });
        projectBox.setMaxWidth(Double.MAX_VALUE);

        statusBox.getItems().setAll(Dtos.TaskStatus.values());
        statusBox.setConverter(enumConverter(UiUtils::humanize));
        priorityBox.getItems().setAll(Dtos.TaskPriority.values());
        priorityBox.setConverter(enumConverter(UiUtils::humanize));

        titleField.setPromptText("What needs to be done?");
        descriptionArea.setPromptText("Optional details, acceptance criteria, links...");
        descriptionArea.setPrefRowCount(4);
        descriptionArea.setWrapText(true);
        hoursField.setPromptText("e.g. 4.5");
        duePicker.setEditable(false);
        duePicker.setPromptText("No due date");

        assigneeBox.setMaxWidth(Double.MAX_VALUE);
        assigneeBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(Dtos.UserSummary user) {
                return user == null ? "Unassigned" : user.displayName() + " (" + user.email() + ")";
            }

            @Override
            public Dtos.UserSummary fromString(String string) {
                return null;
            }
        });
        assigneeHint.getStyleClass().add("hint");

        GridPane grid = new GridPane();
        grid.setHgap(14);
        grid.setVgap(10);
        grid.setPadding(new Insets(18, 4, 4, 4));
        ColumnConstraints labels = new ColumnConstraints();
        labels.setMinWidth(110);
        ColumnConstraints fields = new ColumnConstraints();
        fields.setHgrow(Priority.ALWAYS);
        fields.setMinWidth(320);
        grid.getColumnConstraints().addAll(labels, fields);

        int row = 0;
        grid.addRow(row++, new Label("Project"), projectBox);
        grid.addRow(row++, new Label("Title"), titleField);
        grid.addRow(row++, new Label("Description"), descriptionArea);
        grid.addRow(row++, new Label("Status"), statusBox);
        grid.addRow(row++, new Label("Priority"), priorityBox);
        grid.addRow(row++, new Label("Due date"), duePicker);
        grid.addRow(row++, new Label("Assignee"), assigneeBox);
        grid.add(assigneeHint, 1, row++);
        grid.addRow(row++, new Label("Estimate (h)"), hoursField);

        errorLabel.getStyleClass().add("error-text");
        errorLabel.setWrapText(true);
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);
        grid.add(errorLabel, 0, row, 2, 1);

        getDialogPane().setContent(grid);
        getDialogPane().setPrefWidth(560);

        // ---- initial values
        if (existing == null) {
            Dtos.Project preset = projects.stream()
                    .filter(p -> p.id().equals(defaultProjectId))
                    .findFirst()
                    .orElse(projects.isEmpty() ? null : projects.get(0));
            projectBox.getSelectionModel().select(preset);
            statusBox.getSelectionModel().select(Dtos.TaskStatus.TODO);
            priorityBox.getSelectionModel().select(Dtos.TaskPriority.MEDIUM);
            assigneeBox.getItems().add(null);
            assigneeBox.getSelectionModel().selectFirst();
        } else {
            projects.stream().filter(p -> existing.project() != null && p.id().equals(existing.project().id()))
                    .findFirst().ifPresent(p -> projectBox.getSelectionModel().select(p));
            projectBox.setDisable(true);
            titleField.setText(existing.title());
            descriptionArea.setText(existing.description());
            statusBox.getSelectionModel().select(existing.status());
            priorityBox.getSelectionModel().select(existing.priority());
            duePicker.setValue(existing.dueDate());
            hoursField.setText(existing.estimatedHours() == null ? "" : existing.estimatedHours().toPlainString());
        }

        projectBox.valueProperty().addListener((obs, old, project) -> {
            if (project != null) {
                loadMembers(project.id());
            }
        });
        if (projectBox.getValue() != null) {
            loadMembers(projectBox.getValue().id());
        } else {
            assigneeHint.setText("Select a project to choose an assignee.");
        }

        titleField.textProperty().addListener((obs, old, value) -> refreshValidity());
        hoursField.textProperty().addListener((obs, old, value) -> refreshValidity());

        var saveButton = getDialogPane().lookupButton(save);
        saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String problem = validate();
            if (problem != null) {
                showError(problem);
                event.consume();
            }
        });
        saveButton.disableProperty().bind(
                titleField.textProperty().isEmpty()
                        .or(javafx.beans.binding.Bindings.createBooleanBinding(
                                () -> !hoursField.getText().isBlank() && parseHours(hoursField.getText()) == null,
                                hoursField.textProperty())));

        setResultConverter(button -> {
            if (button != save || validate() != null) {
                return null;
            }
            Dtos.Project project = projectBox.getValue();
            Dtos.UserSummary assignee = assigneeBox.getValue();
            return new Form(
                    project == null ? null : project.id(),
                    titleField.getText().trim(),
                    descriptionArea.getText() == null || descriptionArea.getText().isBlank()
                            ? null : descriptionArea.getText().trim(),
                    statusBox.getValue(),
                    priorityBox.getValue(),
                    duePicker.getValue(),
                    assignee == null ? null : assignee.id(),
                    parseHours(hoursField.getText()));
        });

        Platform.runLater(titleField::requestFocus);
    }

    private void loadMembers(UUID projectId) {
        assigneeBox.setDisable(true);
        assigneeHint.setText("Loading project members...");
        UUID currentAssignee = assigneeBox.getValue() == null ? null : assigneeBox.getValue().id();
        Async.run(() -> context.tasks().projectMembers(projectId),
                members -> {
                    assigneeBox.getItems().clear();
                    assigneeBox.getItems().add(null);
                    for (Dtos.Member member : members) {
                        assigneeBox.getItems().add(member.user());
                    }
                    assigneeBox.getItems().stream()
                            .filter(user -> user != null && user.id().equals(currentAssignee))
                            .findFirst()
                            .ifPresentOrElse(user -> assigneeBox.getSelectionModel().select(user),
                                    () -> assigneeBox.getSelectionModel().selectFirst());
                    assigneeBox.setDisable(false);
                    assigneeHint.setText(members.size() <= 1
                            ? "Add members to this project to assign tasks to other people."
                            : members.size() + " project members available");
                },
                error -> {
                    assigneeBox.setDisable(false);
                    assigneeBox.getItems().clear();
                    assigneeBox.getItems().add(null);
                    assigneeBox.getSelectionModel().selectFirst();
                    assigneeHint.setText("Could not load members: " + UiUtils.errorMessage(error));
                });
    }

    private String validate() {
        if (existing == null && projectBox.getValue() == null) {
            return "Choose a project for this task.";
        }
        String title = titleField.getText() == null ? "" : titleField.getText().trim();
        if (title.length() < 2) {
            return "Title must be at least 2 characters.";
        }
        if (title.length() > 200) {
            return "Title must be at most 200 characters.";
        }
        String hours = hoursField.getText();
        if (hours != null && !hours.isBlank() && parseHours(hours) == null) {
            return "Estimate must be a number between 0 and 9999 with at most 2 decimals.";
        }
        return null;
    }

    private void refreshValidity() {
        String problem = validate();
        if (problem == null) {
            errorLabel.setVisible(false);
            errorLabel.setManaged(false);
        }
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setManaged(true);
        errorLabel.setVisible(true);
    }

    private static BigDecimal parseHours(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            BigDecimal value = new BigDecimal(raw.trim().replace(',', '.'));
            if (value.signum() < 0 || value.compareTo(new BigDecimal("9999.99")) > 0) {
                return null;
            }
            if (value.scale() > 2) {
                return null;
            }
            return value;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static <T> StringConverter<T> enumConverter(java.util.function.Function<T, String> label) {
        return new StringConverter<>() {
            @Override
            public String toString(T value) {
                return value == null ? "" : label.apply(value);
            }

            @Override
            public T fromString(String string) {
                return null;
            }
        };
    }
}
