package com.taskhub.desktop.ui;

import com.taskhub.desktop.api.Dtos;
import com.taskhub.desktop.config.AppContext;
import com.taskhub.desktop.util.Async;
import com.taskhub.desktop.util.UiUtils;
import java.util.Optional;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

/**
 * Project management: full CRUD over the same REST API, with the edit/delete affordances gated on
 * what the server will actually allow (owner/admin).
 */
public class ProjectDialog extends Dialog<Void> {

    private final Window owner;
    private final AppContext context;
    private final Runnable onChanged;

    private final TableView<Dtos.Project> table = new TableView<>();
    private final Label statusLabel = new Label();
    private final Button editButton = new Button("Edit");
    private final Button deleteButton = new Button("Delete");

    public ProjectDialog(Window owner, AppContext context, Runnable onChanged) {
        this.owner = owner;
        this.context = context;
        this.onChanged = onChanged;

        initOwner(owner);
        setTitle("Projects");
        setHeaderText("Projects group tasks and control who can see them.");
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().getStyleClass().add("taskhub-dialog");
        if (owner != null && owner.getScene() != null) {
            getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
        }

        Button createButton = new Button("+  New project");
        createButton.getStyleClass().add("primary-button");
        createButton.setOnAction(e -> createProject());

        editButton.getStyleClass().add("ghost-button");
        editButton.setOnAction(e -> editProject());
        deleteButton.getStyleClass().addAll("ghost-button", "danger-button");
        deleteButton.setOnAction(e -> deleteProject());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, createButton, editButton, deleteButton, spacer);
        toolbar.setAlignment(Pos.CENTER_LEFT);

        configureTable();

        statusLabel.getStyleClass().add("muted");
        VBox content = new VBox(12, toolbar, table, statusLabel);
        content.setPadding(new Insets(12, 4, 4, 4));
        content.setPrefSize(760, 460);
        VBox.setVgrow(table, Priority.ALWAYS);
        getDialogPane().setContent(content);

        table.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            editButton.setDisable(selected == null);
            deleteButton.setDisable(selected == null);
        });
        editButton.setDisable(true);
        deleteButton.setDisable(true);
        table.setRowFactory(view -> {
            var row = new javafx.scene.control.TableRow<Dtos.Project>();
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    editProject();
                }
            });
            return row;
        });

        load();
    }

    private void configureTable() {
        table.getSelectionModel().setSelectionMode(SelectionMode.SINGLE);
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);

        TableColumn<Dtos.Project, String> code = new TableColumn<>("CODE");
        code.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().code()));
        code.setPrefWidth(120);

        TableColumn<Dtos.Project, String> name = new TableColumn<>("NAME");
        name.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(d.getValue().name()));
        name.setPrefWidth(240);

        TableColumn<Dtos.Project, String> status = new TableColumn<>("STATUS");
        status.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(UiUtils.humanize(d.getValue().status())));
        status.setPrefWidth(120);

        TableColumn<Dtos.Project, String> members = new TableColumn<>("MEMBERS");
        members.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(String.valueOf(d.getValue().memberCount())));
        members.setPrefWidth(90);

        TableColumn<Dtos.Project, String> owner = new TableColumn<>("OWNER");
        owner.setCellValueFactory(d -> new ReadOnlyObjectWrapper<>(
                d.getValue().owner() == null ? "-" : d.getValue().owner().displayName()));
        owner.setPrefWidth(160);

        table.getColumns().setAll(java.util.List.of(code, name, status, members, owner));
    }

    private void load() {
        statusLabel.setText("Loading projects...");
        Async.run(() -> context.projects().list(0, 100, "name", "asc", null, null),
                page -> {
                    table.setItems(FXCollections.observableArrayList(page.content()));
                    statusLabel.setText(page.content().size() + " project(s) visible to you");
                },
                error -> statusLabel.setText("Could not load projects: " + UiUtils.errorMessage(error)));
    }

    private void createProject() {
        new ProjectFormDialog(owner, null).showAndWait().ifPresent(form ->
                Async.run(() -> context.projects().create(form.code(), form.name(), form.description(), form.status()),
                        created -> {
                            UiUtils.info(owner, "Project created",
                                    "Project " + created.code() + " created.");
                            load();
                            onChanged.run();
                        },
                        error -> UiUtils.error(owner, "Could not create project", UiUtils.errorMessage(error))));
    }

    private void editProject() {
        Dtos.Project selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        new ProjectFormDialog(owner, selected).showAndWait().ifPresent(form ->
                Async.run(() -> context.projects().update(selected.id(), form.code(), form.name(),
                                form.description(), form.status(), selected.version()),
                        updated -> {
                            load();
                            onChanged.run();
                        },
                        error -> UiUtils.error(owner, "Could not update project", UiUtils.errorMessage(error))));
    }

    private void deleteProject() {
        Dtos.Project selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Optional<ButtonType> answer = UiUtils.confirm(owner, "Delete project",
                "Delete " + selected.code() + " and all of its tasks? This cannot be undone.");
        if (answer.isEmpty() || answer.get() != ButtonType.OK) {
            return;
        }
        Async.runVoid(() -> context.projects().delete(selected.id()),
                () -> {
                    load();
                    onChanged.run();
                },
                error -> UiUtils.error(owner, "Could not delete project", UiUtils.errorMessage(error)));
    }

    /** Small create/edit form for a project. */
    public static class ProjectFormDialog extends Dialog<ProjectFormDialog.Form> {

        public record Form(String code, String name, String description, Dtos.ProjectStatus status) {
        }

        private final javafx.scene.control.TextField codeField = new javafx.scene.control.TextField();
        private final javafx.scene.control.TextField nameField = new javafx.scene.control.TextField();
        private final javafx.scene.control.TextArea descriptionField = new javafx.scene.control.TextArea();
        private final javafx.scene.control.ComboBox<Dtos.ProjectStatus> statusBox = new javafx.scene.control.ComboBox<>();
        private final Label errorLabel = new Label();

        public ProjectFormDialog(Window owner, Dtos.Project existing) {
            initOwner(owner);
            setTitle(existing == null ? "New project" : "Edit project");
            setHeaderText(existing == null
                    ? "Codes are unique and upper-case, e.g. APOLLO."
                    : "Changes are visible to every project member.");

            ButtonType save = new ButtonType(existing == null ? "Create" : "Save", ButtonBar.ButtonData.OK_DONE);
            getDialogPane().getButtonTypes().addAll(save, ButtonType.CANCEL);
            getDialogPane().getStyleClass().add("taskhub-dialog");
            if (owner != null && owner.getScene() != null) {
                getDialogPane().getStylesheets().addAll(owner.getScene().getStylesheets());
            }

            codeField.setPromptText("APOLLO");
            nameField.setPromptText("Apollo Platform");
            descriptionField.setPrefRowCount(3);
            descriptionField.setWrapText(true);
            statusBox.getItems().setAll(Dtos.ProjectStatus.values());
            statusBox.getSelectionModel().select(Dtos.ProjectStatus.PLANNED);

            if (existing != null) {
                codeField.setText(existing.code());
                nameField.setText(existing.name());
                descriptionField.setText(existing.description());
                statusBox.getSelectionModel().select(existing.status());
            }

            var grid = new javafx.scene.layout.GridPane();
            grid.setHgap(12);
            grid.setVgap(10);
            grid.setPadding(new Insets(16, 4, 4, 4));
            grid.addRow(0, new Label("Code"), codeField);
            grid.addRow(1, new Label("Name"), nameField);
            grid.addRow(2, new Label("Description"), descriptionField);
            grid.addRow(3, new Label("Status"), statusBox);

            errorLabel.getStyleClass().add("error-text");
            errorLabel.setWrapText(true);
            errorLabel.setManaged(false);
            errorLabel.setVisible(false);
            grid.add(errorLabel, 0, 4, 2, 1);

            codeField.textProperty().addListener((obs, old, value) -> {
                if (value != null) {
                    String upper = value.toUpperCase(java.util.Locale.ROOT);
                    if (!upper.equals(value)) {
                        codeField.setText(upper);
                    }
                }
            });

            getDialogPane().setContent(grid);
            getDialogPane().setPrefWidth(520);

            var saveButton = getDialogPane().lookupButton(save);
            saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                String problem = validate();
                if (problem != null) {
                    errorLabel.setText(problem);
                    errorLabel.setManaged(true);
                    errorLabel.setVisible(true);
                    event.consume();
                }
            });

            setResultConverter(button -> button == save
                    ? new Form(codeField.getText().trim(), nameField.getText().trim(),
                    descriptionField.getText() == null || descriptionField.getText().isBlank()
                            ? null : descriptionField.getText().trim(),
                    statusBox.getValue())
                    : null);

            javafx.application.Platform.runLater(nameField::requestFocus);
        }

        private String validate() {
            if (!codeField.getText().matches("^[A-Z][A-Z0-9-]{1,15}$")) {
                return "Code must be 2-16 characters: upper-case letters, digits or dashes, starting with a letter.";
            }
            String name = nameField.getText() == null ? "" : nameField.getText().trim();
            if (name.length() < 2 || name.length() > 160) {
                return "Name must be between 2 and 160 characters.";
            }
            return null;
        }
    }
}
