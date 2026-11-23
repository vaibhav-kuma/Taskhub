package com.taskhub.task;

import com.taskhub.common.PageResponse;
import com.taskhub.security.SecurityUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/tasks")
@Tag(name = "Tasks", description = "Paged, searchable, filterable task CRUD")
public class TaskController {

    private final TaskService taskService;

    public TaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping
    @Operation(summary = "List tasks visible to the caller",
            description = """
                    Supports case-insensitive search over title/description, filtering by status,
                    priority, project, assignee and overdue state, plus allow-listed sorting and
                    pagination (page is 0-based, size is clamped to the configured maximum).
                    """)
    public PageResponse<TaskDtos.TaskResponse> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @Parameter(description = "Case-insensitive prefix search (minimum 2 characters)")
            @RequestParam(required = false) String search,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) TaskPriority priority,
            @RequestParam(required = false) UUID projectId,
            @RequestParam(required = false) UUID assigneeId,
            @RequestParam(required = false) Boolean overdue) {
        return taskService.list(page, size, sort, direction, search, status, priority, projectId,
                assigneeId, overdue, SecurityUtils.currentUser());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one task")
    public TaskDtos.TaskResponse get(@PathVariable UUID id) {
        return taskService.get(id, SecurityUtils.currentUser());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a task", description = "Requires contributor access to the project.")
    public TaskDtos.TaskResponse create(@Valid @RequestBody TaskDtos.CreateTaskRequest request) {
        return taskService.create(request, SecurityUtils.currentUser());
    }

    @PatchMapping("/{id}")
    @Operation(summary = "Partially update a task",
            description = "Null fields are unchanged; use clearDueDate/clearAssignee to remove values.")
    public TaskDtos.TaskResponse update(@PathVariable UUID id,
                                        @Valid @RequestBody TaskDtos.UpdateTaskRequest request) {
        return taskService.update(id, request, SecurityUtils.currentUser());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a task")
    public void delete(@PathVariable UUID id) {
        taskService.delete(id, SecurityUtils.currentUser());
    }
}
