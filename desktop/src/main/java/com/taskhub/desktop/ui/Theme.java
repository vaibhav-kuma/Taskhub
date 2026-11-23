package com.taskhub.desktop.ui;

import javafx.scene.Scene;

/** Light/dark theme switching via a style class on the scene root. */
public final class Theme {

    public static final String DARK = "dark";
    public static final String LIGHT = "light";

    private Theme() {
    }

    public static void apply(Scene scene, String theme) {
        if (scene == null || scene.getRoot() == null) {
            return;
        }
        var classes = scene.getRoot().getStyleClass();
        classes.removeAll(DARK, LIGHT);
        classes.add(DARK.equals(theme) ? DARK : LIGHT);
    }

    public static String toggle(String current) {
        return DARK.equals(current) ? LIGHT : DARK;
    }
}
