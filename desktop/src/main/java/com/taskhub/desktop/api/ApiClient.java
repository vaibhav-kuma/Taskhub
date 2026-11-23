package com.taskhub.desktop.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Blocking, typed HTTP client for the TaskHub API.
 *
 * <p>Design notes:
 * <ul>
 *   <li>Blocking on purpose — callers run it on a background executor ({@code Async.run}).</li>
 *   <li>One automatic retry on 401 through {@link TokenProvider#refresh()}, so an access token
 *       that expires while the app is open is invisible to the user.</li>
 *   <li>Every failure becomes an {@link ApiException} carrying the server's stable error code.</li>
 *   <li>Requests carry a client-generated {@code X-Request-Id} for support correlation.</li>
 * </ul>
 */
public class ApiClient {

    private static final String JSON = "application/json";
    private static final String USER_AGENT =
            "TaskHub-Desktop/1.0.0 (Java " + System.getProperty("java.version") + ")";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final String baseUrl;
    private final Duration requestTimeout;
    private volatile String accessToken;
    private volatile TokenProvider tokenProvider;

    public ApiClient(String baseUrl) {
        this(baseUrl, Duration.ofSeconds(20));
    }

    public ApiClient(String baseUrl, Duration requestTimeout) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.requestTimeout = requestTimeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        this.mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_AS_NULL)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .serializationInclusion(JsonInclude.Include.NON_NULL)
                .build();
    }

    public static String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("API base URL must not be blank");
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            trimmed = "https://" + trimmed;
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String accessToken() {
        return accessToken;
    }

    public void setTokenProvider(TokenProvider tokenProvider) {
        this.tokenProvider = tokenProvider;
    }

    // ------------------------------------------------------------------ verbs

    public <T> T get(String path, Class<T> type) {
        return decode(send("GET", path, null), type, path);
    }

    public <T> T get(String path, TypeReference<T> type) {
        return decode(send("GET", path, null), type, path);
    }

    public <T> T post(String path, Object payload, Class<T> type) {
        return decode(send("POST", path, payload), type, path);
    }

    public <T> T post(String path, Object payload, TypeReference<T> type) {
        return decode(send("POST", path, payload), type, path);
    }

    public void postNoContent(String path, Object payload) {
        send("POST", path, payload);
    }

    public <T> T patch(String path, Object payload, Class<T> type) {
        return decode(send("PATCH", path, payload), type, path);
    }

    public void delete(String path) {
        send("DELETE", path, null);
    }

    // --------------------------------------------------------------- internals

    /** Executes the request and returns the raw body, retrying once after a successful refresh. */
    private String send(String method, String path, Object payload) {
        boolean retried = false;
        while (true) {
            HttpResponse<String> response = execute(buildRequest(method, path, payload));

            if (response.statusCode() == 401 && !retried && tokenProvider != null && tokenProvider.refresh()) {
                retried = true;
                continue;
            }
            if (response.statusCode() >= 400) {
                throw toException(response);
            }
            return response.body();
        }
    }

    private HttpResponse<String> execute(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException ex) {
            throw new ApiException(0, "TIMEOUT",
                    "The server did not respond within " + requestTimeout.toSeconds() + " seconds.");
        } catch (ConnectException ex) {
            throw new ApiException(0, "NETWORK_ERROR",
                    "Cannot reach " + baseUrl + ". Check the server URL and your connection.");
        } catch (IOException ex) {
            throw new ApiException(0, "NETWORK_ERROR", "Network error: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(0, "INTERRUPTED", "The request was cancelled.");
        }
    }

    private HttpRequest buildRequest(String method, String path, Object payload) {
        URI uri;
        try {
            uri = URI.create(baseUrl + (path.startsWith("/") ? path : "/" + path));
        } catch (IllegalArgumentException ex) {
            throw new ApiException(0, "INVALID_URL", "Invalid request URL: " + path);
        }

        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", JSON)
                .header("User-Agent", USER_AGENT)
                .header("X-Request-Id", UUID.randomUUID().toString());

        String token = accessToken;
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }

        if (payload == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            try {
                builder.header("Content-Type", JSON)
                        .method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)));
            } catch (IOException ex) {
                throw new ApiException(0, "SERIALIZATION_ERROR", "Cannot encode request: " + ex.getMessage());
            }
        }
        return builder.build();
    }

    private <T> T decode(String body, Class<T> type, String path) {
        return decode(body, mapper.getTypeFactory().constructType(type), path);
    }

    private <T> T decode(String body, TypeReference<T> type, String path) {
        return decode(body, mapper.getTypeFactory().constructType(type), path);
    }

    private <T> T decode(String body, JavaType type, String path) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(body, type);
        } catch (IOException ex) {
            throw new ApiException(0, "UNREADABLE_RESPONSE",
                    "Unexpected response from " + path + ": " + ex.getMessage());
        }
    }

    private ApiException toException(HttpResponse<String> response) {
        String body = response.body();
        if (body != null && !body.isBlank()) {
            try {
                Dtos.ErrorBody error = mapper.readValue(body, Dtos.ErrorBody.class);
                List<ApiException.FieldError> fields = error.fieldErrors() == null ? List.of()
                        : error.fieldErrors().stream()
                        .map(f -> new ApiException.FieldError(f.field(), f.message()))
                        .toList();
                return new ApiException(error.status(), error.code(), error.message(),
                        error.requestId(), fields);
            } catch (IOException ignored) {
                // Non-JSON error body (proxy/timeout page) — fall through to a generic message.
            }
        }
        String message = switch (response.statusCode()) {
            case 401 -> "Your session has expired. Please sign in again.";
            case 403 -> "You do not have permission to perform this action.";
            case 404 -> "The requested resource was not found.";
            case 429 -> "Too many requests. Please wait a moment.";
            default -> "Request failed with HTTP " + response.statusCode() + ".";
        };
        return new ApiException(response.statusCode(), "HTTP_" + response.statusCode(), message, null, List.of());
    }
}
