package com.taskhub.user;

import com.taskhub.common.PageResponse;
import com.taskhub.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@Tag(name = "Users", description = "Directory for assignee pickers, plus administrative operations")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    @Operation(summary = "List users", description = "Used by the desktop client to populate assignee pickers.")
    public PageResponse<UserResponse> list(@RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) String search,
                                           @RequestParam(required = false) Boolean enabled) {
        return userService.list(page, size, search, enabled, SecurityUtils.currentUser());
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Get a user (admin only)")
    public UserResponse get(@PathVariable UUID id) {
        return userService.get(id);
    }

    @PatchMapping("/{id}/role")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Change a user's global role (admin only)")
    public UserResponse updateRole(@PathVariable UUID id, @Valid @RequestBody RoleUpdateRequest request) {
        return userService.updateRole(id, request.role(), SecurityUtils.currentUser());
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Enable or disable an account (admin only)")
    public UserResponse setEnabled(@PathVariable UUID id, @Valid @RequestBody StatusUpdateRequest request) {
        return userService.setEnabled(id, request.enabled(), SecurityUtils.currentUser());
    }

    public record RoleUpdateRequest(@NotNull(message = "role is required") Role role) {
    }

    public record StatusUpdateRequest(@NotNull(message = "enabled is required") Boolean enabled) {
    }
}
