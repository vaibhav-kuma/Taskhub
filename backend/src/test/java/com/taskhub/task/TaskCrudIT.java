package com.taskhub.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.taskhub.ApiIntegrationTest;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class TaskCrudIT extends ApiIntegrationTest {

    private String ownerToken;
    private String projectId;

    @BeforeEach
    void setUp() throws Exception {
        Tokens owner = register(uniqueEmail("owner"), "Project Owner");
        ownerToken = owner.accessToken();
        projectId = createProject(ownerToken, "TASKCRUD" + System.nanoTime() % 100000);
    }

    @Test
    void createsReadsUpdatesAndDeletesTask() throws Exception {
        MvcResult created = mockMvc.perform(authorized(post("/api/v1/tasks"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "projectId", projectId,
                                "title", "Ship the release",
                                "description", "Cut the 1.0 tag",
                                "priority", "HIGH",
                                "dueDate", LocalDate.now().plusDays(3).toString()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.status").value("TODO"))
                .andExpect(jsonPath("$.priority").value("HIGH"))
                .andExpect(jsonPath("$.project.id").value(projectId))
                .andExpect(jsonPath("$.overdue").value(false))
                .andReturn();

        String taskId = body(created).get("id").asText();
        long version = body(created).get("version").asLong();

        mockMvc.perform(authorized(get("/api/v1/tasks/" + taskId), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Ship the release"));

        mockMvc.perform(authorized(patch("/api/v1/tasks/" + taskId), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "DONE", "title", "Ship the release v1.0"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DONE"))
                .andExpect(jsonPath("$.title").value("Ship the release v1.0"))
                .andExpect(jsonPath("$.version", greaterThan((int) version)));

        mockMvc.perform(authorized(delete("/api/v1/tasks/" + taskId), ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(authorized(get("/api/v1/tasks/" + taskId), ownerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void rejectsStaleVersionFromConcurrentEdit() throws Exception {
        MvcResult created = mockMvc.perform(authorized(post("/api/v1/tasks"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("projectId", projectId, "title", "Contended task"))))
                .andExpect(status().isCreated())
                .andReturn();
        String taskId = body(created).get("id").asText();
        long version = body(created).get("version").asLong();

        mockMvc.perform(authorized(patch("/api/v1/tasks/" + taskId), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "DONE", "version", version))))
                .andExpect(status().isOk());

        // Second writer still holds the old version: reject instead of overwriting silently.
        mockMvc.perform(authorized(patch("/api/v1/tasks/" + taskId), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "TODO", "version", version))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));
    }

    @Test
    void rejectsInvalidPayloadWithFieldErrors() throws Exception {
        mockMvc.perform(authorized(post("/api/v1/tasks"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("projectId", projectId, "title", " ", "estimatedHours", -5))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[*].field", hasItem("title")))
                .andExpect(jsonPath("$.fieldErrors[*].message", everyItem(not(is("")))));
    }

    @Test
    void paginatesSearchesFiltersAndSorts() throws Exception {
        String[] titles = {"Alpha integration", "Beta review", "Gamma rollout", "Delta retro", "Epsilon audit"};
        for (String title : titles) {
            createTask(title, "TODO", "MEDIUM");
        }
        createTask("Zeta blocker", "BLOCKED", "CRITICAL");

        mockMvc.perform(authorized(get("/api/v1/tasks").param("projectId", projectId).param("size", "2"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(6))
                .andExpect(jsonPath("$.totalPages").value(3))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));

        mockMvc.perform(authorized(get("/api/v1/tasks")
                        .param("projectId", projectId).param("search", "gamma").param("sort", "title"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Gamma rollout"));

        mockMvc.perform(authorized(get("/api/v1/tasks")
                        .param("projectId", projectId).param("status", "BLOCKED"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].priority").value("CRITICAL"));

        mockMvc.perform(authorized(get("/api/v1/tasks")
                        .param("projectId", projectId).param("sort", "title").param("direction", "asc")
                        .param("size", "3"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].title").value("Alpha integration"))
                .andExpect(jsonPath("$.sort").value("title"))
                .andExpect(jsonPath("$.direction").value("asc"));

        // Size is clamped to the configured maximum rather than trusting the client.
        mockMvc.perform(authorized(get("/api/v1/tasks").param("size", "1000"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(100));
    }

    @Test
    void rejectsUnknownSortFieldAndNegativePage() throws Exception {
        mockMvc.perform(authorized(get("/api/v1/tasks").param("sort", "passwordHash; DROP TABLE tasks--"), ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT"));

        mockMvc.perform(authorized(get("/api/v1/tasks").param("page", "-1"), ownerToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_PAGE"));
    }

    @Test
    void treatsSearchWildcardsLiterally() throws Exception {
        createTask("100% coverage", "TODO", "LOW");
        createTask("Plain task", "TODO", "LOW");

        mockMvc.perform(authorized(get("/api/v1/tasks")
                        .param("projectId", projectId).param("search", "100%"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("100% coverage"));
    }

    @Test
    void enforcesRowLevelAccessForOutsiders() throws Exception {
        String taskId = createTask("Secret task", "TODO", "HIGH");
        Tokens outsider = register(uniqueEmail("outsider"), "Outsider");

        mockMvc.perform(authorized(get("/api/v1/tasks/" + taskId), outsider.accessToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(authorized(patch("/api/v1/tasks/" + taskId), outsider.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "DONE"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(authorized(delete("/api/v1/tasks/" + taskId), outsider.accessToken()))
                .andExpect(status().isForbidden());

        mockMvc.perform(authorized(get("/api/v1/tasks"), outsider.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mockMvc.perform(authorized(post("/api/v1/tasks"), outsider.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("projectId", projectId, "title", "Injected task"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsAssigneeOutsideTheProject() throws Exception {
        Tokens outsider = register(uniqueEmail("notamember"), "Not A Member");
        MvcResult meResult = mockMvc.perform(authorized(get("/api/v1/auth/me"), outsider.accessToken()))
                .andExpect(status().isOk())
                .andReturn();
        String outsiderId = body(meResult).get("id").asText();

        mockMvc.perform(authorized(post("/api/v1/tasks"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("projectId", projectId, "title", "Bad assignee",
                                "assigneeId", outsiderId))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("ASSIGNEE_NOT_PROJECT_MEMBER"));
    }

    @Test
    void flagsOverdueTasksAndFiltersThem() throws Exception {
        createTask("Late task", "TODO", "HIGH", LocalDate.now().minusDays(3));
        createTask("Done late task", "DONE", "HIGH", LocalDate.now().minusDays(3));
        createTask("Future task", "TODO", "LOW", LocalDate.now().plusDays(10));

        mockMvc.perform(authorized(get("/api/v1/tasks")
                        .param("projectId", projectId).param("overdue", "true"), ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].title").value("Late task"))
                .andExpect(jsonPath("$.content[0].overdue").value(true));
    }

    @Test
    void deletingProjectCascadesToTasks() throws Exception {
        String taskId = createTask("Cascade me", "TODO", "LOW");

        mockMvc.perform(authorized(delete("/api/v1/projects/" + projectId), ownerToken))
                .andExpect(status().isNoContent());

        mockMvc.perform(authorized(get("/api/v1/tasks/" + taskId), ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void requiresAuthenticationForEveryTaskEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/tasks")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/tasks").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------- helpers

    private String createProject(String token, String code) throws Exception {
        MvcResult result = mockMvc.perform(authorized(post("/api/v1/projects"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", code, "name", "Project " + code,
                                "description", "Integration test project", "status", "ACTIVE"))))
                .andExpect(status().isCreated())
                .andReturn();
        return body(result).get("id").asText();
    }

    private String createTask(String title, String status, String priority) throws Exception {
        return createTask(title, status, priority, null);
    }

    private String createTask(String title, String status, String priority, LocalDate dueDate) throws Exception {
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", projectId);
        payload.put("title", title);
        payload.put("status", status);
        payload.put("priority", priority);
        if (dueDate != null) {
            payload.put("dueDate", dueDate.toString());
        }
        MvcResult result = mockMvc.perform(authorized(post("/api/v1/tasks"), ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(payload)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = body(result);
        assertThat(node.get("id").asText()).isNotBlank();
        return node.get("id").asText();
    }
}
