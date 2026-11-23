package com.taskhub.desktop.ui;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

/** Collects registration fields; performs only client-side validation. */
public class RegisterDialog extends Dialog<RegisterDialog.Form> {

    public record Form(String email, String password, String displayName) {
    }

    private final TextField emailField = new TextField();
    private final TextField nameField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final PasswordField confirmField = new PasswordField();
    private final Label errorLabel = new Label();

    public RegisterDialog(Window owner) {
        initOwner(owner);
        setTitle("Create your TaskHub account");
        setHeaderText("A workspace for you and your team, in under a minute.");

        ButtonType create = new ButtonType("Create account", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(create, ButtonType.CANCEL);

        emailField.setPromptText("you@example.com");
        nameField.setPromptText("Ada Lovelace");
        passwordField.setPromptText("At least 12 characters");
        confirmField.setPromptText("Repeat your password");

        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(8);
        grid.setPadding(new Insets(16, 4, 4, 4));

        grid.addRow(0, new Label("Email"), emailField);
        grid.addRow(1, new Label("Display name"), nameField);
        grid.addRow(2, new Label("Password"), passwordField);
        grid.addRow(3, new Label("Confirm"), confirmField);

        errorLabel.getStyleClass().add("error-text");
        errorLabel.setWrapText(true);
        errorLabel.setMaxWidth(320);
        errorLabel.setManaged(false);
        errorLabel.setVisible(false);
        grid.add(errorLabel, 0, 4, 2, 1);

        getDialogPane().setContent(grid);
        getDialogPane().getStylesheets().addAll(owner == null || owner.getScene() == null
                ? java.util.List.of()
                : owner.getScene().getStylesheets());
        getDialogPane().getStyleClass().add("taskhub-dialog");

        var createButton = getDialogPane().lookupButton(create);
        createButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
            String problem = validate();
            if (problem != null) {
                showError(problem);
                event.consume();
            }
        });

        setResultConverter(button -> {
            if (button != create) {
                return null;
            }
            return new Form(emailField.getText().trim(), passwordField.getText(), nameField.getText().trim());
        });

        javafx.application.Platform.runLater(emailField::requestFocus);
    }

    private String validate() {
        String email = emailField.getText() == null ? "" : emailField.getText().trim();
        String password = passwordField.getText() == null ? "" : passwordField.getText();
        String name = nameField.getText() == null ? "" : nameField.getText().trim();

        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            return "Enter a valid email address.";
        }
        if (name.length() < 2) {
            return "Display name must be at least 2 characters.";
        }
        if (password.length() < 12) {
            return "Password must be at least 12 characters.";
        }
        if (!password.matches(".*[A-Za-z].*") || !password.matches(".*\\d.*")) {
            return "Password must contain at least one letter and one digit.";
        }
        if (!password.equals(confirmField.getText())) {
            return "Passwords do not match.";
        }
        return null;
    }

    private void showError(String message) {
        errorLabel.setText(message);
        errorLabel.setManaged(true);
        errorLabel.setVisible(true);
    }
}
