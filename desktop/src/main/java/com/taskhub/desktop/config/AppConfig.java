package com.taskhub.desktop.config;

import com.taskhub.desktop.api.ApiClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves where the API lives and remembers user preferences between launches.
 *
 * <p>Resolution order (first wins):
 * <ol>
 *   <li>{@code --api=https://...} command-line argument</li>
 *   <li>{@code TASKHUB_API_URL} environment variable</li>
 *   <li>{@code taskhub.api.url} system property</li>
 *   <li>the saved server URL from the previous successful login</li>
 *   <li>{@code desktop.properties} on the classpath</li>
 *   <li>{@code http://localhost:8080}</li>
 * </ol>
 */
public final class AppConfig {

    private static final Logger log = Logger.getLogger(AppConfig.class.getName());
    private static final String DEFAULT_URL = "http://localhost:8080";
    private static final Path SETTINGS_FILE =
            Path.of(System.getProperty("user.home"), ".taskhub", "desktop.properties");

    private AppConfig() {
    }

    public static String resolveBaseUrl(String[] args) {
        for (String arg : args) {
            if (arg != null && arg.startsWith("--api=")) {
                return ApiClient.normalizeBaseUrl(arg.substring("--api=".length()));
            }
        }
        String env = System.getenv("TASKHUB_API_URL");
        if (env != null && !env.isBlank()) {
            return ApiClient.normalizeBaseUrl(env);
        }
        String property = System.getProperty("taskhub.api.url");
        if (property != null && !property.isBlank()) {
            return ApiClient.normalizeBaseUrl(property);
        }
        String saved = settings().getProperty("api.baseUrl");
        if (saved != null && !saved.isBlank()) {
            return ApiClient.normalizeBaseUrl(saved);
        }
        String bundled = settings().getProperty("api.defaultUrl", DEFAULT_URL);
        return ApiClient.normalizeBaseUrl(bundled);
    }

    public static Properties bundledProperties() {
        Properties properties = new Properties();
        try (InputStream in = AppConfig.class.getResourceAsStream("/desktop.properties")) {
            if (in != null) {
                properties.load(in);
            }
        } catch (IOException ex) {
            log.log(Level.FINE, "Could not read desktop.properties", ex);
        }
        return properties;
    }

    public static Properties settings() {
        Properties properties = new Properties();
        try {
            if (Files.exists(SETTINGS_FILE)) {
                try (InputStream in = Files.newInputStream(SETTINGS_FILE)) {
                    properties.load(in);
                }
            }
        } catch (IOException ex) {
            log.log(Level.FINE, "Could not read user settings", ex);
        }
        return properties;
    }

    public static void saveSetting(String key, String value) {
        Properties properties = settings();
        properties.setProperty(key, value);
        try {
            Files.createDirectories(SETTINGS_FILE.getParent());
            try (var out = Files.newOutputStream(SETTINGS_FILE)) {
                properties.store(out, "TaskHub desktop settings");
            }
        } catch (IOException ex) {
            log.log(Level.FINE, "Could not persist setting " + key, ex);
        }
    }

    public static String savedTheme() {
        return settings().getProperty("ui.theme", "light");
    }

    public static void saveTheme(String theme) {
        saveSetting("ui.theme", theme);
    }

    public static void saveServerUrl(String url) {
        saveSetting("api.baseUrl", ApiClient.normalizeBaseUrl(url));
    }
}
