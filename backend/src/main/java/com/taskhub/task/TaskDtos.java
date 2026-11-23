package com.taskhub.task;

import com.taskhub.project.ProjectSummary;
import com.taskhub.user.UserSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class TaskDtos {

    private TaskDtos() {
    }

    @Schema(name = "CreateTaskRequest")
    public record CreateTaskRequest(
            @NotNull(message = "projectId is required") UUID projectId,

            @NotBlank(message = "title is required")
            @Size(min = 2, max = 200, message = "must be between 2 and 200 characters")
            String title,

            @Size(max = 8000, message = "must be at most 8000 characters")
            String description,

            TaskStatus status,
            TaskPriority priority,
            LocalDate dueDate,
            UUID assigneeId,

            @DecimalMin(value = "0.0", message = "must be zero or greater")
            @Digits(integer = 4, fraction = 2, message = "must have at most 4 integer and 2 decimal digits")
            BigDecimal estimatedHours) {
    }

    /**
     * Partial update. Null means "leave unchanged"; use the explicit {@code clear*} flags to
     * remove an optional association or date, which distinguishes "absent" from "set to null".
     */
    @Schema(name = "UpdateTaskRequest")
    public record UpdateTaskRequest(
            UUID projectId,
            @Size(min = 2, max = 200, message = "must be between 2 and 200 characters") String title,
            @Size(max = 8000, message = "must be at most 8000 characters") String description,
            TaskStatus status,
            TaskPriority priority,
            LocalDate dueDate,
            UUID assigneeId,
            @DecimalMin(value = "0.0", message = "must be zero or greater")
            @Digits(integer = 4, fraction = 2, message = "must have at most 4 integer and 2 decimal digits")
            BigDecimal estimatedHours,
            Boolean clearDueDate,
            Boolean clearAssignee,
            Long version) {
    }

    @Schema(name = "Task")
    public record TaskResponse(
            UUID id,
            ProjectSummary project,
            String title,
            String description,
            TaskStatus status,
            TaskPriority priority,
            LocalDate dueDate,
            UserSummary assignee,
            BigDecimal estimatedHours,
            boolean overdue,
            long version,
            Instant createdAt,
            Instant updatedAt) {

        public static TaskResponse from(Task task) {
            return new TaskResponse(
                    task.getId(),
                    ProjectSummary.from(task.getProject()),
                    task.getTitle(),
                    task.getDescription(),
                    task.getStatus(),
                    task.getPriority(),
                    task.getDueDate(),
                    task.getAssignee() == null ? null : UserSummary.from(task.getAssignee()),
                    task.getEstimatedHours(),
                    task.isOverdue(),
                    task.getVersion(),
                    task.getCreatedAt(),
                    task.getUpdatedAt());
        }
    }
}
