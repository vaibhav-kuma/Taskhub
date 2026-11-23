package com.taskhub.desktop.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.taskhub.desktop.api.Dtos;
import com.taskhub.desktop.config.AppConfig;
import com.taskhub.desktop.config.AppContext;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runtime UI verification. Starts a real JavaFX toolkit, loads the FXML views with their real
 * controllers, applies the stylesheet, talks to a live API and captures screenshots.
 *
 * <p>Opt-in via {@code TASKHUB_UI_TEST=1} (needs a display or Xvfb) so the normal build stays
 * headless-friendly. Run locally with:
 * {@code xvfb-run -a mvn test -DTASKHUB_UI_TEST=1 -Dtest=UiSmokeTest}.
 */
class UiSmokeTest {

    private static final String API = System.getenv().getOrDefault("TASKHUB_UI_URL", "http://localhost:8080");
    private static final String EMAIL = System.getenv().getOrDefault("TASKHUB_UI_EMAIL", "admin@taskhub.local");
    private static final String PASSWORD = System.getenv().getOrDefault("TASKHUB_UI_PASSWORD", "Admin@12345");

    @BeforeAll
    static void startToolkit() {
        Assumptions.assumeTrue("1".equals(System.getenv("TASKHUB_UI_TEST")),
                "Set TASKHUB_UI_TEST=1 (with a display or xvfb-run) to run the UI smoke test");
        CountDownLatch ready = new CountDownLatch(1);
        try {
            Platform.startup(ready::countDown);
        } catch (IllegalStateException alreadyStarted) {
            ready.countDown();
        }
        try {
            Assumptions.assumeTrue(ready.await(30, TimeUnit.SECONDS), "JavaFX toolkit did not start");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            Assumptions.abort("interrupted");
        }
    }

    @Test
    void loginViewLoadsWithItsRealController() throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Parent root = onFx(() -> {
            FXMLLoader loader = new FXMLLoader(UiSmokeTest.class.getResource("/fxml/login.fxml"));
            loader.setControllerFactory(type -> new LoginController(new StubNavigator()));
            Parent parent = loader.load();
            Scene scene = new Scene(parent, 1100, 680);
            scene.getStylesheets().add(UiSmokeTest.class.getResource("/css/app.css").toExternalForm());
            Theme.apply(scene, "light");
            parent.applyCss();
            parent.layout();
            snapshot(scene, "login.png");
            return parent;
        }, failure);

        assertThat(failure.get()).isNull();
        TextField serverField = (TextField) root.lookup("#serverField");
        assertThat(serverField).isNotNull();
        assertThat(serverField.getText()).isNotBlank();
        assertThat(root.lookup("#loginButton")).isNotNull();
    }

    @Test
    void workspaceLoadsRealDataFromTheApi() throws Exception {
        AppContext context = new AppContext(API);
        Dtos.User user = runOffFx(() -> context.session().login(EMAIL, PASSWORD), 20_000);
        assertThat(user).isNotNull();
        assertThat(user.email()).isEqualToIgnoringCase(EMAIL);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Parent root = onFx(() -> {
            FXMLLoader loader = new FXMLLoader(UiSmokeTest.class.getResource("/fxml/main.fxml"));
            loader.setControllerFactory(type -> type == MainController.class
                    ? new MainController(new StubNavigator(), context) : instantiate(type));
            Parent parent = loader.load();
            Scene scene = new Scene(parent, 1320, 820);
            scene.getStylesheets().add(UiSmokeTest.class.getResource("/css/app.css").toExternalForm());
            Theme.apply(scene, "dark");
            parent.applyCss();
            parent.layout();
            return parent;
        }, failure);
        assertThat(failure.get()).isNull();

        // Wait for the asynchronous first page to arrive.
        // A fresh production database is legitimately empty, so wait for the load to *complete*
        // (label populated) rather than assuming at least one row exists.
        boolean loaded = waitUntil(() -> {
            Label result = (Label) root.lookup("#resultLabel");
            return result != null && result.getText() != null && !result.getText().isBlank();
        }, 20_000);
        assertThat(loaded).as("task list finished loading from the live API").isTrue();

        TableView<?> table = (TableView<?>) root.lookup("#taskTable");
        assertThat(table).isNotNull();
        assertThat(table.getColumns()).hasSize(7);

        Label summary = (Label) root.lookup("#resultLabel");
        assertThat(summary.getText()).matches("(?s)(Showing .* tasks|No tasks match the current filters)");
        if (summary.getText().startsWith("Showing")) {
            assertThat(table.getItems()).isNotEmpty();
        } else {
            assertThat(table.getItems()).isEmpty();
        }

        onFx(() -> {
            snapshot(root.getScene(), "workspace-dark.png");
            return null;
        }, new AtomicReference<>());
    }

    @Test
    void workspaceRendersInLightTheme() throws Exception {
        AppContext context = new AppContext(API);
        runOffFx(() -> context.session().login(EMAIL, PASSWORD), 20_000);

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Parent root = onFx(() -> {
            FXMLLoader loader = new FXMLLoader(UiSmokeTest.class.getResource("/fxml/main.fxml"));
            loader.setControllerFactory(type -> type == MainController.class
                    ? new MainController(new StubNavigator(), context) : instantiate(type));
            Parent parent = loader.load();
            Scene scene = new Scene(parent, 1320, 820);
            scene.getStylesheets().add(UiSmokeTest.class.getResource("/css/app.css").toExternalForm());
            Theme.apply(scene, "light");
            parent.applyCss();
            parent.layout();
            return parent;
        }, failure);
        assertThat(failure.get()).isNull();

        assertThat(waitUntil(() -> {
            Label result = (Label) root.lookup("#resultLabel");
            return result != null && result.getText() != null && !result.getText().isBlank();
        }, 20_000)).isTrue();

        onFx(() -> {
            snapshot(root.getScene(), "workspace-light.png");
            return null;
        }, new AtomicReference<>());
    }

    // ------------------------------------------------------------------ helpers

    private static <T> T onFx(Callable<T> work, AtomicReference<Throwable> failure) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.countDown();
            }
        });
        assertThat(done.await(30, TimeUnit.SECONDS)).as("FX task completed").isTrue();
        if (failure.get() != null) {
            throw new AssertionError("FX task failed", failure.get());
        }
        return result.get();
    }

    private static <T> T runOffFx(Callable<T> work, long timeoutMillis) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                result.set(work.call());
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "ui-test-login");
        thread.setDaemon(true);
        thread.start();
        thread.join(timeoutMillis);
        if (failure.get() != null) {
            throw new AssertionError("Background call failed", failure.get());
        }
        return result.get();
    }

    private static boolean waitUntil(BooleanSupplier condition, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            AtomicReference<Boolean> value = new AtomicReference<>(false);
            onFx(() -> {
                value.set(condition.getAsBoolean());
                return null;
            }, new AtomicReference<>());
            if (value.get()) {
                return true;
            }
            Thread.sleep(150);
        }
        return false;
    }

    private static void snapshot(Scene scene, String name) throws Exception {
        try {
            scene.getRoot().applyCss();
            scene.getRoot().layout();
            WritableImage image = scene.snapshot(null);
            Path output = Path.of("target", "ui-screenshots");
            Files.createDirectories(output);
            File file = output.resolve(name).toFile();
            ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
        } catch (Exception ex) {
            // Screenshots are evidence, not an assertion: never fail the suite on them.
            System.err.println("Could not capture " + name + ": " + ex);
        }
    }

    private static Object instantiate(Class<?> type) {
        try {
            return type.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Minimal navigator: the test drives views directly, so navigation is a no-op. */
    private static final class StubNavigator implements AppNavigator {
        private String theme = "light";

        @Override
        public AppContext context(String baseUrl) {
            return new AppContext(baseUrl);
        }

        @Override
        public void openWorkspace() {
        }

        @Override
        public void openLogin() {
        }

        @Override
        public Stage stage() {
            return null;
        }

        @Override
        public String theme() {
            return theme;
        }

        @Override
        public void setTheme(String theme) {
            this.theme = theme;
        }
    }

    static {
        // Keep the test hermetic: never write the developer's real settings file.
        System.setProperty("user.home", Path.of("target", "ui-home").toAbsolutePath().toString());
        assertThat(AppConfig.savedTheme()).isIn("light", "dark");
    }
}
