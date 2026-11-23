package com.taskhub.desktop.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Request payloads. Null fields are omitted by the client so PATCH stays partial. */
public final class Requests {

    private Requests() {
    }

    public record Login(String email, String password) {
    }

    public record Register(String email, String password, String displayName) {
    }

    public record Refresh(String refreshToken) {
    }

    public record CreateProject(String code, String name, String description, String status) {
    }

    public record UpdateProject(String code, String name, String description, String status, Long version) {
    }

    public record AddMember(UUID userId, String role) {
    }

    public record CreateTask(UUID projectId, String title, String description, String status, String priority,
                             LocalDate dueDate, UUID assigneeId, BigDecimal estimatedHours) {
    }

    /**
     * Partial update. {@code clearDueDate}/{@code clearAssignee} explicitly remove a value, which
     * null-omission alone cannot express. {@code version} enables optimistic concurrency: the
     * server returns 409 STALE_VERSION when someone else saved in the meantime.
     */
    public record UpdateTask(String title, String description, String status, String priority,
                             LocalDate dueDate, UUID assigneeId, BigDecimal estimatedHours,
                             Boolean clearDueDate, Boolean clearAssignee, Long version) {
    }
}
