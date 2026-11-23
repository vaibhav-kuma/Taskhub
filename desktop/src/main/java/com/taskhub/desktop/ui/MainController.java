package com.taskhub.desktop.ui;

import com.taskhub.desktop.api.ApiException;
import com.taskhub.desktop.api.Dtos;
import com.taskhub.desktop.api.Requests;
import com.taskhub.desktop.config.AppContext;
import com.taskhub.desktop.util.Async;
import com.taskhub.desktop.util.UiUtils;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import javafx.util.StringConverter;

/**
 * Workspace controller: view state only. Every server interaction is delegated to the API layer
 * and executed off the FX thread through {@link Async}.
 */
public class MainController {

    private final AppNavigator navigator;
    private final AppContext context;

    private final List<Dtos.Project> projects = new ArrayList<>();

    // Filter state
    private int pageIndex;
    private int pageSize = 20;
    private int totalPages;
    private long totalElements;
    private String sortField = "updatedAt";
    private String sortDirection = "desc";
    private boolean bootstrapped;
    private int requestSequence;

    private final PauseTransition searchDebounce = new PauseTransition(Duration.millis(320));

    @FXML private StackPane root;
    @FXML private VBox toastLayer;
    @FXML private Label connectionLabel;
    @FXML private Label resultLabel;
    @FXML private Label pageLabel;
    @FXML private TextField searchField;
    @FXML private ComboBox<Labeled<UUID>> projectFilter;
    @FXML private ComboBox<Labeled<Dtos.TaskStatus>> statusFilter;
    @FXML private ComboBox<Labeled<Dtos.TaskPriority>> priorityFilter;
    @FXML private ComboBox<Labeled<Integer>> pageSizeBox;
    @FXML private CheckBox mineCheck;
    @FXML private CheckBox overdueCheck;
    @FXML private Button prevButton;
    @FXML private Button nextButton;
    @FXML private Button refreshButton;
    @FXML private Button themeButton;
    @FXML private Button newTaskButton;
    @FXML private Button projectsButton;
    @FXML private Button clearFiltersButton;
    @FXML private MenuButton userMenu;
    @FXML private ProgressIndicator busyIndicator;
    @FXML private TableView<Dtos.Task> taskTable;
    @FXML private TableColumn<Dtos.Task, String> titleColumn;
    @FXML private TableColumn<Dtos.Task, String> projectColumn;
    @FXML private TableColumn<Dtos.Task, Dtos.TaskStatus> statusColumn;
    @FXML private TableColumn<Dtos.Task, Dtos.TaskPriority> priorityColumn;
    @FXML private TableColumn<Dtos.Task, String> dueColumn;
    @FXML private TableColumn<Dtos.Task, String> assigneeColumn;
    @FXML private TableColumn<Dtos.Task, String> updatedColumn;

    public MainController(AppNavigator navigator, AppContext context) {
        this.navigator = navigator;
        this.context = context;
    }

    /** Wrapper so combo boxes can show a friendly label for a null ("all") option. */
    public record Labeled<T>(T value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    @FXML
    private void initialize() {
        Dtos.User user = context.session().user();
        userMenu.setText(user == null ? "Account" : user.displayName());

        connectionLabel.setText(shortHost(context.baseUrl()));
        connectionLabel.setTooltip(new Tooltip("Connected to " + context.baseUrl()));
        themeButton.setText("dark".equals(navigator.theme()) ? "Light" : "Dark");

        configureTable();
        configureFilters();
        configureUserMenu();
        configureKeyboardShortcuts();

        taskTable.setPlaceholder(emptyPlaceholder());

        loadProjects();
        loadPage(0);
    }

    // ------------------------------------------------------------------ setup

    private void configureTable() {
        taskTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        taskTable.setFixedCellSize(46);

        titleColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().title()));
        titleColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                Dtos.Task task = getTableRow() == null ? null : getTableRow().getItem();
                Label title = new Label(value);
                title.getStyleClass().add("cell-title");
                Label subtitle = new Label(task == null || task.description() == null
                        ? "" : shorten(task.description(), 70));
                subtitle.getStyleClass().add("cell-subtitle");
                VBox box = new VBox(2, title, subtitle);
                box.setAlignment(Pos.CENTER_LEFT);
                setGraphic(box);
                setText(null);
            }
        });

        projectColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(
                data.getValue().project() == null ? "-" : data.getValue().project().name()));

        statusColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().status()));
        statusColumn.setCellFactory(column -> badgeCell(
                value -> value == null ? "" : UiUtils.humanize(value),
                value -> value == null ? "badge" : "badge " + UiUtils.statusStyleClass(value)));

        priorityColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(data.getValue().priority()));
        priorityColumn.setCellFactory(column -> badgeCell(
                value -> value == null ? "" : UiUtils.humanize(value),
                value -> value == null ? "badge" : "badge " + UiUtils.priorityStyleClass(value)));

        dueColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(UiUtils.formatDue(data.getValue())));
        dueColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(String value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty ? null : value);
                Dtos.Task task = getTableRow() == null ? null : getTableRow().getItem();
                getStyleClass().remove("overdue-text");
                if (!empty && task != null && task.overdue()) {
                    getStyleClass().add("overdue-text");
                }
            }
        });

        assigneeColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(
                data.getValue().assignee() == null ? "Unassigned" : data.getValue().assignee().displayName()));

        updatedColumn.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(
                UiUtils.formatInstant(data.getValue().updatedAt())));

        wireSorting(titleColumn, "title");
        wireSorting(projectColumn, "project");
        wireSorting(statusColumn, "status");
        wireSorting(priorityColumn, "priority");
        wireSorting(dueColumn, "dueDate");
        wireSorting(updatedColumn, "updatedAt");
        taskTable.setSortPolicy(table -> {
            if (!bootstrapped) {
                return true;
            }
            if (table.getSortOrder().isEmpty()) {
                return true;
            }
            TableColumn<Dtos.Task, ?> column = table.getSortOrder().get(0);
            Object field = column.getUserData();
            if (field != null) {
                sortField = field.toString();
            }
            sortDirection = column.getSortType() == TableColumn.SortType.ASCENDING ? "asc" : "desc";
            Platform.runLater(() -> loadPage(0));
            return true; // server-side ordering only
        });

        taskTable.setRowFactory(table -> {
            TableRow<Dtos.Task> row = new TableRow<>();
            MenuItem edit = new MenuItem("Edit task");
            edit.setOnAction(e -> editTask(row.getItem()));
            MenuItem delete = new MenuItem("Delete task");
            delete.setOnAction(e -> deleteTask(row.getItem()));
            ContextMenu menu = new ContextMenu(edit, delete);
            row.contextMenuProperty().bind(
                    javafx.beans.binding.Bindings.when(row.emptyProperty()).then((ContextMenu) null).otherwise(menu));
            row.setOnMouseClicked(event -> {
                if (event.getClickCount() == 2 && !row.isEmpty()) {
                    editTask(row.getItem());
                }
            });
            return row;
        });
    }

    private void configureFilters() {
        projectFilter.getItems().add(new Labeled<>(null, "All projects"));
        projectFilter.getSelectionModel().selectFirst();
        projectFilter.setOnAction(e -> loadPage(0));

        statusFilter.getItems().add(new Labeled<>(null, "All statuses"));
        for (Dtos.TaskStatus status : Dtos.TaskStatus.values()) {
            statusFilter.getItems().add(new Labeled<>(status, UiUtils.humanize(status)));
        }
        statusFilter.getSelectionModel().selectFirst();
        statusFilter.setOnAction(e -> loadPage(0));

        priorityFilter.getItems().add(new Labeled<>(null, "All priorities"));
        for (Dtos.TaskPriority priority : Dtos.TaskPriority.values()) {
            priorityFilter.getItems().add(new Labeled<>(priority, UiUtils.humanize(priority)));
        }
        priorityFilter.getSelectionModel().selectFirst();
        priorityFilter.setOnAction(e -> loadPage(0));

        for (int size : new int[]{10, 20, 50, 100}) {
            pageSizeBox.getItems().add(new Labeled<>(size, String.valueOf(size)));
        }
        pageSizeBox.getSelectionModel().select(1);
        pageSizeBox.setOnAction(e -> {
            pageSize = pageSizeBox.getValue() == null ? 20 : pageSizeBox.getValue().value();
            loadPage(0);
        });

        mineCheck.setOnAction(e -> loadPage(0));
        overdueCheck.setOnAction(e -> loadPage(0));

        searchField.textProperty().addListener((obs, old, value) -> {
            searchDebounce.setOnFinished(e -> loadPage(0));
            searchDebounce.playFromStart();
        });
    }

    private void configureUserMenu() {
        Dtos.User user = context.session().user();
        if (user != null) {
            MenuItem identity = new MenuItem(user.email() + "  ·  " + UiUtils.humanize(user.role()));
            identity.setDisable(true);
            MenuItem manage = new MenuItem("Manage projects");
            manage.setOnAction(e -> onOpenProjects());
            userMenu.getItems().addAll(0, List.of(identity, new SeparatorMenuItem(), manage));
        }
    }

    private void configureKeyboardShortcuts() {
        Platform.runLater(() -> {
            if (root.getScene() == null) {
                return;
            }
            var accelerators = root.getScene().getAccelerators();
            accelerators.put(new KeyCodeCombination(KeyCode.N, KeyCombination.CONTROL_DOWN), this::onNewTask);
            accelerators.put(new KeyCodeCombination(KeyCode.F, KeyCombination.CONTROL_DOWN),
                    () -> searchField.requestFocus());
            accelerators.put(new KeyCodeCombination(KeyCode.F5), this::onRefresh);
            accelerators.put(new KeyCodeCombination(KeyCode.ESCAPE), this::onClearFilters);
        });
    }

    private void wireSorting(TableColumn<Dtos.Task, ?> column, String field) {
        column.setUserData(field);
        if ("updatedAt".equals(field)) {
            column.setSortType(TableColumn.SortType.DESCENDING);
        }
    }

    // ------------------------------------------------------------------ data

    private void loadProjects() {
        UUID selected = projectFilter.getValue() == null ? null : projectFilter.getValue().value();
        Async.run(() -> context.projects().list(0, 100, "name", "asc", null, null),
                page -> {
                    projects.clear();
                    projects.addAll(page.content());
                    projectFilter.getItems().setAll(new Labeled<>(null, "All projects"));
                    for (Dtos.Project project : projects) {
                        projectFilter.getItems().add(new Labeled<>(project.id(), project.toString()));
                    }
                    if (selected == null) {
                        projectFilter.getSelectionModel().selectFirst();
                    } else {
                        projectFilter.getItems().stream()
                                .filter(item -> selected.equals(item.value()))
                                .findFirst()
                                .ifPresentOrElse(item -> projectFilter.getSelectionModel().select(item),
                                        () -> projectFilter.getSelectionModel().selectFirst());
                    }
                },
                error -> handleError("Could not load projects", error));
    }

    private void loadPage(int target) {
        if (target < 0) {
            return;
        }
        pageIndex = target;
        setBusy(true);
        int sequence = ++requestSequence;

        String search = searchField.getText();
        Dtos.TaskStatus status = statusFilter.getValue() == null ? null : statusFilter.getValue().value();
        Dtos.TaskPriority priority = priorityFilter.getValue() == null ? null : priorityFilter.getValue().value();
        UUID projectId = projectFilter.getValue() == null ? null : projectFilter.getValue().value();
        UUID assigneeId = mineCheck.isSelected() && context.session().user() != null
                ? context.session().user().id() : null;
        Boolean overdue = overdueCheck.isSelected() ? Boolean.TRUE : null;

        Async.run(() -> context.tasks().list(pageIndex, pageSize, sortField, sortDirection,
                        search, status, priority, projectId, assigneeId, overdue),
                page -> {
                    if (sequence != requestSequence) {
                        return; // a newer request superseded this response
                    }
                    render(page);
                    setBusy(false);
                },
                error -> {
                    if (sequence != requestSequence) {
                        return;
                    }
                    setBusy(false);
                    handleError("Could not load tasks", error);
                });
    }

    private void render(Dtos.Page<Dtos.Task> page) {
        taskTable.setItems(FXCollections.observableArrayList(page.content()));
        totalPages = page.totalPages();
        totalElements = page.totalElements();
        pageIndex = page.page();

        if (totalElements == 0) {
            resultLabel.setText("No tasks match the current filters");
        } else {
            long from = (long) pageIndex * pageSize + 1;
            long to = Math.min(from + page.content().size() - 1, totalElements);
            resultLabel.setText("Showing " + from + "-" + to + " of " + totalElements + " tasks");
        }
        pageLabel.setText("Page " + (totalPages == 0 ? 0 : pageIndex + 1) + " of " + totalPages);
        prevButton.setDisable(pageIndex <= 0);
        nextButton.setDisable(totalPages == 0 || pageIndex >= totalPages - 1);
    }

    private void setBusy(boolean busy) {
        // Only the spinner and the refresh button reflect loading. Disabling the TableView would
        // make JavaFX dim it to 40% opacity, which reads as "broken" on an empty workspace.
        busyIndicator.setVisible(busy);
        busyIndicator.setManaged(busy);
        refreshButton.setDisable(busy);
    }

    private VBox emptyPlaceholder() {
        Label title = new Label("Nothing here yet");
        title.getStyleClass().add("empty-title");
        Label hint = new Label("Create a task, or relax the filters to see more.");
        hint.getStyleClass().add("muted");
        Button action = new Button("+  New task");
        action.getStyleClass().add("primary-button");
        action.setOnAction(e -> onNewTask());
        VBox box = new VBox(10, title, hint, action);
        box.setAlignment(Pos.CENTER);
        box.setPadding(new Insets(48));
        return box;
    }

    // ----------------------------------------------------------------- actions

    @FXML
    private void onNewTask() {
        if (projects.isEmpty()) {
            Toasts.error(toastLayer, "Create a project first - tasks always belong to a project.");
            onOpenProjects();
            return;
        }
        TaskDialog dialog = new TaskDialog(navigator.stage(), context, projects, null,
                projectFilter.getValue() == null ? projects.get(0).id() : projectFilter.getValue().value());
        dialog.showAndWait().ifPresent(form -> Async.run(
                () -> context.tasks().create(form.projectId(), form.title(), form.description(),
                        form.status(), form.priority(), form.dueDate(), form.assigneeId(), form.hours()),
                created -> {
                    Toasts.success(toastLayer, "Task created");
                    loadPage(0);
                },
                error -> handleError("Could not create the task", error)));
    }

    private void editTask(Dtos.Task task) {
        if (task == null) {
            return;
        }
        UUID projectId = task.project() == null ? null : task.project().id();
        TaskDialog dialog = new TaskDialog(navigator.stage(), context, projects, task, projectId);
        dialog.showAndWait().ifPresent(form -> {
            boolean dueDateCleared = task.dueDate() != null && form.dueDate() == null;
            boolean assigneeCleared = task.assignee() != null && form.assigneeId() == null;
            Requests.UpdateTask update = new Requests.UpdateTask(
                    form.title(),
                    form.description() == null ? "" : form.description(),
                    form.status() == null ? null : form.status().name(),
                    form.priority() == null ? null : form.priority().name(),
                    form.dueDate(),
                    form.assigneeId(),
                    form.hours(),
                    dueDateCleared ? Boolean.TRUE : null,
                    assigneeCleared ? Boolean.TRUE : null,
                    task.version());
            Async.run(() -> context.tasks().update(task.id(), update),
                    updated -> {
                        Toasts.success(toastLayer, "Task updated");
                        loadPage(pageIndex);
                    },
                    error -> handleError("Could not update the task", error));
        });
    }

    private void deleteTask(Dtos.Task task) {
        if (task == null) {
            return;
        }
        Optional<javafx.scene.control.ButtonType> answer = UiUtils.confirm(navigator.stage(),
                "Delete task", "Delete \"" + task.title() + "\"? This cannot be undone.");
        if (answer.isEmpty() || answer.get() != javafx.scene.control.ButtonType.OK) {
            return;
        }
        Async.runVoid(() -> context.tasks().delete(task.id()),
                () -> {
                    Toasts.success(toastLayer, "Task deleted");
                    loadPage(pageIndex);
                },
                error -> handleError("Could not delete the task", error));
    }

    @FXML
    private void onOpenProjects() {
        ProjectDialog dialog = new ProjectDialog(navigator.stage(), context, () -> {
            loadProjects();
            loadPage(0);
        });
        dialog.showAndWait();
    }

    @FXML
    private void onRefresh() {
        loadProjects();
        loadPage(pageIndex);
    }

    @FXML
    private void onToggleTheme() {
        String next = Theme.toggle(navigator.theme());
        navigator.setTheme(next);
        themeButton.setText("dark".equals(next) ? "Light" : "Dark");
    }

    @FXML
    private void onPreviousPage() {
        loadPage(Math.max(0, pageIndex - 1));
    }

    @FXML
    private void onNextPage() {
        if (pageIndex + 1 < totalPages) {
            loadPage(pageIndex + 1);
        }
    }

    @FXML
    private void onClearFilters() {
        searchField.clear();
        projectFilter.getSelectionModel().selectFirst();
        statusFilter.getSelectionModel().selectFirst();
        priorityFilter.getSelectionModel().selectFirst();
        mineCheck.setSelected(false);
        overdueCheck.setSelected(false);
        loadPage(0);
    }

    @FXML
    private void onSignOut() {
        Async.runVoid(context.session()::logout,
                () -> navigator.openLogin(),
                error -> navigator.openLogin());
    }

    // ----------------------------------------------------------------- helpers

    private void handleError(String prefix, Throwable error) {
        if (error instanceof ApiException api && api.isUnauthorized()) {
            Toasts.error(toastLayer, "Your session expired. Please sign in again.");
            navigator.openLogin();
            return;
        }
        Toasts.error(toastLayer, prefix + ": " + UiUtils.errorMessage(error));
    }

    private static String shortHost(String url) {
        try {
            java.net.URI uri = java.net.URI.create(url);
            String host = uri.getHost() == null ? url : uri.getHost();
            return uri.getPort() > 0 ? host + ":" + uri.getPort() : host;
        } catch (RuntimeException ex) {
            return url;
        }
    }

    private static String shorten(String value, int max) {
        String flat = value.replace('\n', ' ').replace('\r', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "…";
    }

    private static <T> TableCell<Dtos.Task, T> badgeCell(
            java.util.function.Function<T, String> text,
            java.util.function.Function<T, String> styleClass) {
        return new TableCell<>() {
            private final Label badge = new Label();
            private final HBox box = new HBox(badge);

            {
                box.setAlignment(Pos.CENTER_LEFT);
            }

            @Override
            protected void updateItem(T value, boolean empty) {
                super.updateItem(value, empty);
                if (empty || value == null) {
                    setGraphic(null);
                    return;
                }
                badge.setText(text.apply(value));
                badge.setStyle(null);
                badge.getStyleClass().setAll(styleClass.apply(value).split(" "));
                setGraphic(box);
            }
        };
    }
}
