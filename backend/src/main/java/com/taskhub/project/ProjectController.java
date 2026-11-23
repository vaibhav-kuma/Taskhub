package com.taskhub.project;

import com.taskhub.common.PageResponse;
import com.taskhub.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/projects")
@Tag(name = "Projects", description = "Projects and their membership")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    @Operation(summary = "List projects visible to the caller",
            description = "Admins see all projects; other users see projects they own or belong to.")
    public PageResponse<ProjectDtos.ProjectResponse> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) ProjectStatus status) {
        return projectService.list(page, size, sort, direction, search, status, SecurityUtils.currentUser());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one project")
    public ProjectDtos.ProjectResponse get(@PathVariable UUID id) {
        return projectService.get(id, SecurityUtils.currentUser());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a project", description = "The caller becomes owner and an OWNER member.")
    public ProjectDtos.ProjectResponse create(@Valid @RequestBody ProjectDtos.CreateProjectRequest request) {
        return projectService.create(request, SecurityUtils.currentUser());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Partially update a project", description = "Only the owner or an admin may update.")
    public ProjectDtos.ProjectResponse update(@PathVariable UUID id,
                                              @Valid @RequestBody ProjectDtos.UpdateProjectRequest request) {
        return projectService.update(id, request, SecurityUtils.currentUser());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a project", description = "Cascades to its tasks and memberships.")
    public void delete(@PathVariable UUID id) {
        projectService.delete(id, SecurityUtils.currentUser());
    }

    @GetMapping("/{id}/members")
    @Operation(summary = "List project members")
    public List<ProjectDtos.MemberResponse> members(@PathVariable UUID id) {
        return projectService.members(id, SecurityUtils.currentUser());
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Add a member to a project")
    public ProjectDtos.MemberResponse addMember(@PathVariable UUID id,
                                                @Valid @RequestBody ProjectDtos.AddMemberRequest request) {
        return projectService.addMember(id, request, SecurityUtils.currentUser());
    }

    @PatchMapping("/{id}/members/{userId}")
    @Operation(summary = "Change a member's project role")
    public ProjectDtos.MemberResponse changeMemberRole(@PathVariable UUID id,
                                                       @PathVariable UUID userId,
                                                       @Valid @RequestBody ProjectDtos.UpdateMemberRoleRequest request) {
        return projectService.changeMemberRole(id, userId, request.role(), SecurityUtils.currentUser());
    }

    @DeleteMapping("/{id}/members/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a member from a project")
    public void removeMember(@PathVariable UUID id, @PathVariable UUID userId) {
        projectService.removeMember(id, userId, SecurityUtils.currentUser());
    }
}
