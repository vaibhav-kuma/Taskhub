package com.taskhub.desktop;

import com.taskhub.desktop.config.AppConfig;
import com.taskhub.desktop.config.AppContext;
import com.taskhub.desktop.ui.AppNavigator;
import com.taskhub.desktop.ui.LoginController;
import com.taskhub.desktop.ui.MainController;
import com.taskhub.desktop.ui.Theme;
import java.io.IOException;
import java.util.Objects;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

/**
 * JavaFX entry point.
 *
 * <p>The client is a thin shell over the REST API: it holds a base URL, an HTTP client and a
 * session — no database driver, no credentials, no server-side technology. The same jar runs on
 * Windows, Linux and macOS against any reachable deployment.
 */
public class TaskHubClient extends Application implements AppNavigator {

    private Stage stage;
    private AppContext context;
    private String theme;

    @Override
    public void start(Stage primaryStage) {
        this.stage = primaryStage;
        this.theme = AppConfig.savedTheme();
        this.context = new AppContext(AppConfig.resolveBaseUrl(getParameters().getRaw().toArray(String[]::new)));

        stage.setTitle("TaskHub");
        stage.setMinWidth(960);
        stage.setMinHeight(620);
        openLogin();
        stage.show();
    }

    @Override
    public AppContext context(String baseUrl) {
        String normalized = com.taskhub.desktop.api.ApiClient.normalizeBaseUrl(baseUrl);
        if (context == null || !context.baseUrl().equals(normalized)) {
            context = new AppContext(normalized);
        }
        return context;
    }

    @Override
    public void openLogin() {
        LoginController controller = new LoginController(this);
        setScene("/fxml/login.fxml", controller);
        stage.setTitle("TaskHub - Sign in");
    }

    @Override
    public void openWorkspace() {
        MainController controller = new MainController(this, context);
        setScene("/fxml/main.fxml", controller);
        stage.setTitle("TaskHub - " + (context.session().user() == null
                ? "Workspace" : context.session().user().displayName()));
    }

    private void setScene(String resource, Object controller) {
        try {
            FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(
                    TaskHubClient.class.getResource(resource), "Missing FXML: " + resource));
            loader.setControllerFactory(type -> type.isInstance(controller) ? controller : instantiate(type));
            Parent root = loader.load();

            Scene scene = new Scene(root, 1180, 760);
            scene.getStylesheets().add(Objects.requireNonNull(
                    TaskHubClient.class.getResource("/css/app.css")).toExternalForm());
            Theme.apply(scene, theme);
            stage.setScene(scene);
            if (stage.isShowing()) {
                root.applyCss();
                root.layout();
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Cannot load view " + resource, ex);
        }
    }

    private static Object instantiate(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Cannot instantiate controller " + type.getName(), ex);
        }
    }

    @Override
    public Stage stage() {
        return stage;
    }

    @Override
    public String theme() {
        return theme;
    }

    @Override
    public void setTheme(String newTheme) {
        this.theme = newTheme;
        AppConfig.saveTheme(newTheme);
        Theme.apply(stage.getScene(), newTheme);
    }

    public static void main(String[] args) {
        launch(args);
    }
}
