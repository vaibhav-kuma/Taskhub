package com.taskhub.desktop.ui;

import com.taskhub.desktop.config.AppContext;
import javafx.stage.Stage;

/** Navigation + context boundary handed to controllers, avoiding static singletons. */
public interface AppNavigator {

    /**
     * Returns a context for the given server URL, rebuilding the object graph when the URL
     * changes (e.g. the user signs in against another deployment).
     */
    AppContext context(String baseUrl);

    void openWorkspace();

    void openLogin();

    Stage stage();

    String theme();

    void setTheme(String theme);
}
