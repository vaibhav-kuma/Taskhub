package com.taskhub.project;

import com.taskhub.user.UserSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class ProjectDtos {

    private ProjectDtos() {
    }

    @Schema(name = "CreateProjectRequest")
    public record CreateProjectRequest(
            @NotBlank(message = "code is required")
            @Pattern(regexp = "^[A-Z][A-Z0-9-]{1,15}$",
                    message = "must start with a letter and contain 2-16 upper-case letters, digits or dashes")
            String code,

            @NotBlank(message = "name is required")
            @Size(min = 2, max = 160, message = "must be between 2 and 160 characters")
            String name,

            @Size(max = 4000, message = "must be at most 4000 characters")
            String description,

            ProjectStatus status,

            Long version) {
    }

    @Schema(name = "UpdateProjectRequest", description = "Partial update: null fields are left unchanged")
    public record UpdateProjectRequest(
            @Pattern(regexp = "^[A-Z][A-Z0-9-]{1,15}$",
                    message = "must start with a letter and contain 2-16 upper-case letters, digits or dashes")
            String code,

            @Size(min = 2, max = 160, message = "must be between 2 and 160 characters")
            String name,

            @Size(max = 4000, message = "must be at most 4000 characters")
            String description,

            ProjectStatus status,

            Long version) {
    }

    @Schema(name = "Project")
    public record ProjectResponse(
            UUID id,
            String code,
            String name,
            String description,
            ProjectStatus status,
            UserSummary owner,
            long memberCount,
            long version,
            Instant createdAt,
            Instant updatedAt) {
    }

    @Schema(name = "AddMemberRequest")
    public record AddMemberRequest(
            @NotNull(message = "userId is required") UUID userId,
            ProjectRole role) {
    }

    @Schema(name = "UpdateMemberRoleRequest")
    public record UpdateMemberRoleRequest(
            @NotNull(message = "role is required") ProjectRole role) {
    }

    @Schema(name = "ProjectMember")
    public record MemberResponse(
            UUID id,
            UserSummary user,
            ProjectRole role,
            Instant addedAt) {

        public static MemberResponse from(ProjectMember member) {
            return new MemberResponse(member.getId(), UserSummary.from(member.getUser()),
                    member.getRole(), member.getAddedAt());
        }
    }
}
