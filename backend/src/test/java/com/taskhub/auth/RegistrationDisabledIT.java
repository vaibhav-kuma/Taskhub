package com.taskhub.auth;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Self-service signup is a policy switch, not a redeploy: {@code TASKHUB_REGISTRATION_ENABLED=false}
 * must close the public endpoint while leaving login untouched.
 */
@SpringBootTest(properties = "taskhub.registration.enabled=false")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RegistrationDisabledIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void publicSignupIsRefusedWhenDisabled() throws Exception {
        String payload = objectMapper.writeValueAsString(Map.of(
                "email", "blocked@example.com",
                "password", "Str0ngPassw0rd!",
                "displayName", "Blocked User"));

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("REGISTRATION_DISABLED"))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }
}
