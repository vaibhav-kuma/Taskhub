package com.taskhub.desktop.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Mirror of the server's JSON contract. Records, immutable, and tolerant of unknown fields so a
 * server-side additive change never breaks an already-deployed desktop client.
 */
public final class Dtos {

    private Dtos() {
    }

    // ------------------------------------------------------------------ enums

    public enum Role { ADMIN, MANAGER, MEMBER }

    public enum ProjectStatus { PLANNED, ACTIVE, ON_HOLD, COMPLETED, ARCHIVED }

    public enum ProjectRole { OWNER, CONTRIBUTOR, VIEWER }

    public enum TaskStatus { TODO, IN_PROGRESS, BLOCKED, DONE }

    public enum TaskPriority { LOW, MEDIUM, HIGH, CRITICAL }

    // --------------------------------------------------------------- payloads

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record User(UUID id, String email, String displayName, Role role, boolean enabled, Instant createdAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserSummary(UUID id, String email, String displayName) {

        @Override
        public String toString() {
            return displayName == null ? email : displayName;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Project(UUID id, String code, String name, String description, ProjectStatus status,
                          UserSummary owner, long memberCount, long version, Instant createdAt, Instant updatedAt) {

        @Override
        public String toString() {
            return code + " - " + name;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProjectSummary(UUID id, String code, String name) {

        @Override
        public String toString() {
            return code + " - " + name;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Task(UUID id, ProjectSummary project, String title, String description, TaskStatus status,
                       TaskPriority priority, LocalDate dueDate, UserSummary assignee, BigDecimal estimatedHours,
                       boolean overdue, long version, Instant createdAt, Instant updatedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(UUID id, UserSummary user, ProjectRole role, Instant addedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages,
                          boolean first, boolean last, String sort, String direction) {

        public boolean isEmpty() {
            return content == null || content.isEmpty();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Auth(String accessToken, String tokenType, long expiresIn, String refreshToken,
                       long refreshExpiresIn, User user) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ErrorBody(Instant timestamp, int status, String code, String message, String path,
                            String requestId, List<FieldErrorBody> fieldErrors) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldErrorBody(String field, String message) {
    }
}
