package com.taskhub;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.junit.jupiter.api.AfterEach;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared fixture for HTTP-level integration tests.
 *
 * <p>Runs against the real Spring context, the real security filter chain, the real Flyway
 * migrations (H2 in PostgreSQL mode) and real JPA mappings.
 *
 * <p>Deliberately NOT wrapped in a test transaction: every HTTP request must commit like it does
 * in production. Wrapping requests in one shared transaction hides bugs (stale persistence
 * contexts after bulk updates, rollback of revocation side-effects) and makes cascade deletes
 * look like they work when they do not. Isolation comes from truncating tables after each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ApiIntegrationTest {

    protected static final String DEFAULT_PASSWORD = "Str0ngPassw0rd!";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Child tables first; keeps the shared in-memory database clean between tests. */
    @AfterEach
    void resetDatabase() {
        jdbcTemplate.execute("DELETE FROM tasks");
        jdbcTemplate.execute("DELETE FROM project_members");
        jdbcTemplate.execute("DELETE FROM projects");
        jdbcTemplate.execute("DELETE FROM refresh_tokens");
        jdbcTemplate.execute("DELETE FROM users");
    }

    protected record Tokens(String accessToken, String refreshToken) {
    }

    protected Tokens register(String email, String displayName) throws Exception {
        return register(email, DEFAULT_PASSWORD, displayName);
    }

    protected Tokens register(String email, String password, String displayName) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password, "displayName", displayName))))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        if (result.getResponse().getStatus() != 201) {
            throw new IllegalStateException("Registration failed: " + body);
        }
        return new Tokens(body.get("accessToken").asText(), body.get("refreshToken").asText());
    }

    protected Tokens login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("email", email, "password", password))))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        if (result.getResponse().getStatus() != 200) {
            throw new IllegalStateException("Login failed: " + body);
        }
        return new Tokens(body.get("accessToken").asText(), body.get("refreshToken").asText());
    }

    protected String bearer(String token) {
        return "Bearer " + token;
    }

    protected MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder builder, String token) {
        return builder.header("Authorization", bearer(token));
    }

    protected String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    protected JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    /** Unique-ish email so tests never collide inside the shared H2 database. */
    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + java.util.UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }
}
