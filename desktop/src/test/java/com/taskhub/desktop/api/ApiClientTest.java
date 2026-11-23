package com.taskhub.desktop.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Transport-level tests: real HTTP against a local stub server, no JavaFX, no display needed. */
class ApiClientTest {

    private HttpServer server;
    private ApiClient client;
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
    private final AtomicReference<String> lastRequestId = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        client = new ApiClient("http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastRequestId.set(exchange.getRequestHeaders().getFirst("X-Request-Id"));
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastBody.set(body);

        if (path.equals("/api/v1/tasks") && exchange.getRequestMethod().equals("GET")) {
            String token = lastAuthorization.get();
            if (token == null || token.equals("Bearer expired")) {
                respond(exchange, 401, """
                        {"status":401,"code":"TOKEN_EXPIRED","message":"The access token has expired.",
                         "requestId":"req-1","fieldErrors":[]}""");
                return;
            }
            respond(exchange, 200, """
                    {"content":[{"id":"11111111-1111-1111-1111-111111111111","title":"Alpha",
                      "status":"TODO","priority":"HIGH","overdue":false,"version":0,
                      "project":{"id":"22222222-2222-2222-2222-222222222222","code":"APOLLO","name":"Apollo"}}],
                     "page":0,"size":20,"totalElements":1,"totalPages":1,"first":true,"last":true,
                     "sort":"updatedAt","direction":"desc"}""");
            return;
        }
        if (path.equals("/api/v1/tasks") && exchange.getRequestMethod().equals("POST")) {
            respond(exchange, 400, """
                    {"status":400,"code":"VALIDATION_FAILED","message":"Request validation failed",
                     "path":"/api/v1/tasks","requestId":"req-2",
                     "fieldErrors":[{"field":"title","message":"must not be blank"}]}""");
            return;
        }
        if (path.equals("/api/v1/tasks/33333333-3333-3333-3333-333333333333")) {
            respond(exchange, 204, "");
            return;
        }
        respond(exchange, 404, "{\"status\":404,\"code\":\"NOT_FOUND\",\"message\":\"Task not found.\"}");
    }

    private void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
        exchange.close();
    }

    @Test
    void sendsBearerTokenAndRequestIdAndParsesPage() {
        client.setAccessToken("good-token");

        Dtos.Page<Dtos.Task> page = client.get("/api/v1/tasks",
                new TypeReference<Dtos.Page<Dtos.Task>>() {
                });

        assertThat(lastAuthorization.get()).isEqualTo("Bearer good-token");
        assertThat(lastRequestId.get()).isNotBlank();
        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(page.content()).hasSize(1);
        assertThat(page.content().get(0).title()).isEqualTo("Alpha");
        assertThat(page.content().get(0).project().code()).isEqualTo("APOLLO");
    }

    @Test
    void mapsValidationErrorsIntoApiException() {
        client.setAccessToken("good-token");

        assertThatThrownBy(() -> client.post("/api/v1/tasks", Map.of("title", ""), Dtos.Task.class))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException api = (ApiException) error;
                    assertThat(api.status()).isEqualTo(400);
                    assertThat(api.code()).isEqualTo("VALIDATION_FAILED");
                    assertThat(api.requestId()).isEqualTo("req-2");
                    assertThat(api.fieldErrors()).hasSize(1);
                    assertThat(api.fieldErrors().get(0).field()).isEqualTo("title");
                    assertThat(api.displayMessage()).contains("title: must not be blank");
                });
    }

    @Test
    void refreshesOnceOnUnauthorizedAndRetries() {
        client.setAccessToken("expired");
        AtomicInteger refreshes = new AtomicInteger();
        client.setTokenProvider(new TokenProvider() {
            @Override
            public String accessToken() {
                return client.accessToken();
            }

            @Override
            public boolean refresh() {
                refreshes.incrementAndGet();
                client.setAccessToken("good-token");
                return true;
            }
        });

        Dtos.Page<Dtos.Task> page = client.get("/api/v1/tasks",
                new TypeReference<Dtos.Page<Dtos.Task>>() {
                });

        assertThat(page.totalElements()).isEqualTo(1);
        assertThat(refreshes.get()).isEqualTo(1);
        assertThat(hits.get()).isEqualTo(2);
    }

    @Test
    void givesUpWhenRefreshFails() {
        client.setAccessToken("expired");
        client.setTokenProvider(new TokenProvider() {
            @Override
            public String accessToken() {
                return client.accessToken();
            }

            @Override
            public boolean refresh() {
                return false;
            }
        });

        assertThatThrownBy(() -> client.get("/api/v1/tasks",
                new TypeReference<Dtos.Page<Dtos.Task>>() {
                }))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException) error).isUnauthorized()).isTrue());
    }

    @Test
    void omitsNullFieldsFromPatchPayloads() {
        client.setAccessToken("good-token");
        client.patch("/api/v1/tasks/33333333-3333-3333-3333-333333333333",
                new Requests.UpdateTask("New title", null, null, null, null, null, null, null, null, 3L),
                Dtos.Task.class);

        assertThat(lastBody.get()).contains("New title");
        assertThat(lastBody.get()).doesNotContain("priority");
        assertThat(lastBody.get()).doesNotContain("null");
    }

    @Test
    void reportsNetworkErrorsWithoutStatus() {
        ApiClient unreachable = new ApiClient("http://127.0.0.1:1", Duration.ofMillis(800));

        assertThatThrownBy(() -> unreachable.get("/api/v1/tasks", Dtos.Task.class))
                .isInstanceOf(ApiException.class)
                .satisfies(error -> {
                    ApiException api = (ApiException) error;
                    assertThat(api.status()).isZero();
                    assertThat(api.isNetworkError()).isTrue();
                    assertThat(api.code()).isIn("NETWORK_ERROR", "TIMEOUT");
                });
    }

    @Test
    void normalizeBaseUrlHandlesCommonInputs() {
        assertThat(ApiClient.normalizeBaseUrl("example.com/")).isEqualTo("https://example.com");
        assertThat(ApiClient.normalizeBaseUrl("http://localhost:8080///")).isEqualTo("http://localhost:8080");
        assertThat(ApiClient.normalizeBaseUrl(" https://api.test.dev ")).isEqualTo("https://api.test.dev");
        assertThatThrownBy(() -> ApiClient.normalizeBaseUrl("  ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryEncodesAndSkipsEmptyValues() {
        String query = Query.create()
                .add("search", "a b&c")
                .add("status", null)
                .add("blank", " ")
                .page(2, 50, "title", "asc")
                .toString();

        assertThat(query).startsWith("?");
        assertThat(query).contains("search=a+b%26c");
        assertThat(query).doesNotContain("status").doesNotContain("blank");
        assertThat(query).contains("page=2").contains("size=50").contains("sort=title").contains("direction=asc");
    }

    @Test
    void deserializesTaskWithOptionalFieldsAndUnknownProperties() {
        ApiClient decoder = new ApiClient("http://localhost:1");
        try {
            Dtos.Task task = decoder.mapper().readValue("""
                    {"id":"11111111-1111-1111-1111-111111111111","title":"Solo","status":"IN_PROGRESS",
                     "priority":"CRITICAL","assignee":null,"dueDate":null,"estimatedHours":2.50,
                     "overdue":true,"version":3,"brandNewServerField":"ignored"}
                    """, Dtos.Task.class);
            assertThat(task.status()).isEqualTo(Dtos.TaskStatus.IN_PROGRESS);
            assertThat(task.assignee()).isNull();
            assertThat(task.estimatedHours()).isEqualByComparingTo("2.50");
            assertThat(task.overdue()).isTrue();
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
        assertThat(UUID.randomUUID()).isNotNull();
        assertThat(List.of()).isEmpty();
    }
}
