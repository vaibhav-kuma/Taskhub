package com.taskhub.desktop.api;

import com.fasterxml.jackson.core.type.TypeReference;
import java.util.List;
import java.util.UUID;

public class ProjectApi {

    private final ApiClient client;

    public ProjectApi(ApiClient client) {
        this.client = client;
    }

    public Dtos.Page<Dtos.Project> list(int page, int size, String sort, String direction,
                                        String search, Dtos.ProjectStatus status) {
        String query = Query.create()
                .page(page, size, sort, direction)
                .add("search", search)
                .add("status", status)
                .toString();
        return client.get("/api/v1/projects" + query, new TypeReference<Dtos.Page<Dtos.Project>>() {
        });
    }

    public Dtos.Project get(UUID id) {
        return client.get("/api/v1/projects/" + id, Dtos.Project.class);
    }

    public Dtos.Project create(String code, String name, String description, Dtos.ProjectStatus status) {
        return client.post("/api/v1/projects",
                new Requests.CreateProject(code, name, description, status == null ? null : status.name()),
                Dtos.Project.class);
    }

    public Dtos.Project update(UUID id, String code, String name, String description,
                               Dtos.ProjectStatus status, Long version) {
        return client.patch("/api/v1/projects/" + id,
                new Requests.UpdateProject(code, name, description,
                        status == null ? null : status.name(), version),
                Dtos.Project.class);
    }

    public void delete(UUID id) {
        client.delete("/api/v1/projects/" + id);
    }

    public List<Dtos.Member> members(UUID id) {
        return client.get("/api/v1/projects/" + id + "/members", new TypeReference<List<Dtos.Member>>() {
        });
    }

    public Dtos.Member addMember(UUID projectId, UUID userId, Dtos.ProjectRole role) {
        return client.post("/api/v1/projects/" + projectId + "/members",
                new Requests.AddMember(userId, role == null ? null : role.name()), Dtos.Member.class);
    }

    public void removeMember(UUID projectId, UUID userId) {
        client.delete("/api/v1/projects/" + projectId + "/members/" + userId);
    }
}
