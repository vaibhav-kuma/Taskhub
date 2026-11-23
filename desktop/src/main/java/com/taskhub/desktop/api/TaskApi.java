package com.taskhub.desktop.api;

import com.fasterxml.jackson.core.type.TypeReference;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class TaskApi {

    private final ApiClient client;

    public TaskApi(ApiClient client) {
        this.client = client;
    }

    public Dtos.Page<Dtos.Task> list(int page, int size, String sort, String direction, String search,
                                     Dtos.TaskStatus status, Dtos.TaskPriority priority,
                                     UUID projectId, UUID assigneeId, Boolean overdue) {
        String query = Query.create()
                .page(page, size, sort, direction)
                .add("search", search)
                .add("status", status)
                .add("priority", priority)
                .add("projectId", projectId)
                .add("assigneeId", assigneeId)
                .add("overdue", overdue)
                .toString();
        return client.get("/api/v1/tasks" + query, new TypeReference<Dtos.Page<Dtos.Task>>() {
        });
    }

    public Dtos.Task get(UUID id) {
        return client.get("/api/v1/tasks/" + id, Dtos.Task.class);
    }

    public Dtos.Task create(UUID projectId, String title, String description, Dtos.TaskStatus status,
                            Dtos.TaskPriority priority, LocalDate dueDate, UUID assigneeId, BigDecimal hours) {
        return client.post("/api/v1/tasks",
                new Requests.CreateTask(projectId, title, description,
                        status == null ? null : status.name(),
                        priority == null ? null : priority.name(),
                        dueDate, assigneeId, hours),
                Dtos.Task.class);
    }

    public Dtos.Task update(UUID id, Requests.UpdateTask request) {
        return client.patch("/api/v1/tasks/" + id, request, Dtos.Task.class);
    }

    public void delete(UUID id) {
        client.delete("/api/v1/tasks/" + id);
    }

    public Dtos.Page<Dtos.User> assignableUsers(String search) {
        String query = Query.create().add("search", search).add("size", 50).toString();
        return client.get("/api/v1/users" + query, new TypeReference<Dtos.Page<Dtos.User>>() {
        });
    }

    public List<Dtos.Member> projectMembers(UUID projectId) {
        return client.get("/api/v1/projects/" + projectId + "/members",
                new TypeReference<List<Dtos.Member>>() {
                });
    }
}
