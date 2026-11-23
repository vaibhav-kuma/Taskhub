package com.taskhub.desktop.ui;

import com.taskhub.desktop.api.ApiException;
import com.taskhub.desktop.config.AppConfig;
import com.taskhub.desktop.config.AppContext;
import com.taskhub.desktop.util.Async;
import com.taskhub.desktop.util.UiUtils;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;

public class LoginController {

    private final AppNavigator navigator;

    @FXML private TextField serverField;
    @FXML private TextField emailField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;
    @FXML private Label statusLabel;
    @FXML private Button loginButton;
    @FXML private ProgressIndicator progress;

    public LoginController(AppNavigator navigator) {
        this.navigator = navigator;
    }

    @FXML
    private void initialize() {
        serverField.setText(AppConfig.resolveBaseUrl(new String[0]));
        String savedEmail = AppConfig.settings().getProperty("auth.lastEmail", "");
        if (!savedEmail.isBlank()) {
            emailField.setText(savedEmail);
        }
        javafx.application.Platform.runLater(passwordField::requestFocus);
    }

    @FXML
    private void onLogin() {
        hideError();
        String server = serverField.getText();
        String email = emailField.getText();
        String password = passwordField.getText();

        if (server == null || server.isBlank()) {
            showError("Enter the API server URL.");
            return;
        }
        if (email == null || email.isBlank()) {
            showError("Enter your email address.");
            return;
        }
        if (password == null || password.isEmpty()) {
            showError("Enter your password.");
            return;
        }

        AppContext context;
        try {
            context = navigator.context(server);
        } catch (IllegalArgumentException ex) {
            showError("That server URL is not valid. Use something like https://api.example.com");
            return;
        }

        setBusy(true, "Signing in...");
        Async.run(() -> context.session().login(email.trim(), password),
                user -> {
                    AppConfig.saveServerUrl(context.baseUrl());
                    AppConfig.saveSetting("auth.lastEmail", email.trim());
                    navigator.openWorkspace();
                },
                error -> {
                    setBusy(false, null);
                    showError(friendly(error));
                });
    }

    @FXML
    private void onRegister() {
        hideError();
        String server = serverField.getText();
        AppContext context;
        try {
            context = navigator.context(server);
        } catch (IllegalArgumentException ex) {
            showError("Enter a valid API server URL before creating an account.");
            return;
        }

        RegisterDialog dialog = new RegisterDialog(navigator.stage());
        dialog.showAndWait().ifPresent(form -> {
            setBusy(true, "Creating your account...");
            Async.run(() -> context.session().register(form.email(), form.password(), form.displayName()),
                    user -> {
                        AppConfig.saveServerUrl(context.baseUrl());
                        AppConfig.saveSetting("auth.lastEmail", form.email());
                        navigator.openWorkspace();
                    },
                    error -> {
                        setBusy(false, null);
                        showError(friendly(error));
                    });
        });
    }

    private String friendly(Throwable error) {
        if (error instanceof ApiException api) {
            if (api.isNetworkError()) {
                return "Cannot reach " + serverField.getText().trim()
                        + ". Check the URL, that the API is running, and your network.";
            }
            if ("INVALID_CREDENTIALS".equals(api.code())) {
                return "Email or password is incorrect.";
            }
            if (api.status() == 429) {
                return "Too many attempts. Please wait a few minutes and try again.";
            }
            return UiUtils.errorMessage(error);
        }
        return UiUtils.errorMessage(error);
    }

    private void setBusy(boolean busy, String message) {
        loginButton.setDisable(busy);
        progress.setVisible(busy);
        progress.setManaged(busy);
        if (message != null) {
            statusLabel.setText(message);
        } else {
            statusLabel.setText("Credentials are sent to the API over HTTPS and never stored on this machine.");
        }
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setVisible(true);
        errorLabel.setManaged(true);
    }

    private void hideError() {
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
    }
}
