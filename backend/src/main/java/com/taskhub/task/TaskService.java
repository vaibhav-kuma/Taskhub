package com.taskhub.task;

import com.taskhub.common.PageResponse;
import com.taskhub.common.PageableFactory;
import com.taskhub.common.exception.ApiException;
import com.taskhub.config.AppProperties;
import com.taskhub.project.Project;
import com.taskhub.project.ProjectAccess;
import com.taskhub.project.ProjectMemberRepository;
import com.taskhub.project.ProjectService;
import com.taskhub.security.AppUserPrincipal;
import com.taskhub.user.User;
import com.taskhub.user.UserRepository;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class TaskService {

    public static final java.util.Set<String> SORTABLE =
            java.util.Set.of("title", "status", "priority", "dueDate", "createdAt", "updatedAt");

    private final TaskRepository taskRepository;
    private final ProjectService projectService;
    private final ProjectMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final ProjectAccess access;
    private final AppProperties properties;

    public TaskService(TaskRepository taskRepository,
                       ProjectService projectService,
                       ProjectMemberRepository memberRepository,
                       UserRepository userRepository,
                       ProjectAccess access,
                       AppProperties properties) {
        this.taskRepository = taskRepository;
        this.projectService = projectService;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.access = access;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskDtos.TaskResponse> list(Integer page, Integer size, String sort, String direction,
                                                    String search, TaskStatus status, TaskPriority priority,
                                                    UUID projectId, UUID assigneeId, Boolean overdue,
                                                    AppUserPrincipal principal) {
        Pageable pageable = PageableFactory.create(page, size, sort, direction, SORTABLE,
                properties.pagination().defaultSize(), properties.pagination().maxSize(), "createdAt");

        Specification<Task> spec = TaskSpecifications.visibleTo(principal);
        spec = andIfPresent(spec, TaskSpecifications.matches(search));
        spec = andIfPresent(spec, TaskSpecifications.hasStatus(status));
        spec = andIfPresent(spec, TaskSpecifications.hasPriority(priority));
        spec = andIfPresent(spec, TaskSpecifications.inProject(projectId));
        spec = andIfPresent(spec, TaskSpecifications.assignedTo(assigneeId));
        spec = andIfPresent(spec, TaskSpecifications.overdue(overdue));

        Page<Task> result = taskRepository.findAll(spec, pageable);
        return PageResponse.from(result, TaskDtos.TaskResponse::from,
                sort == null ? "createdAt" : sort, PageableFactory.normalizeDirection(direction));
    }

    @Transactional(readOnly = true)
    public TaskDtos.TaskResponse get(UUID id, AppUserPrincipal principal) {
        Task task = requireTask(id);
        access.requireRead(task.getProject(), principal);
        return TaskDtos.TaskResponse.from(task);
    }

    @Transactional
    public TaskDtos.TaskResponse create(TaskDtos.CreateTaskRequest request, AppUserPrincipal principal) {
        Project project = projectService.requireProject(request.projectId());
        access.requireContribute(project, principal);

        Task task = new Task(project, request.title().trim(), trimToNull(request.description()));
        if (request.status() != null) {
            task.setStatus(request.status());
        }
        if (request.priority() != null) {
            task.setPriority(request.priority());
        }
        task.setDueDate(request.dueDate());
        task.setEstimatedHours(request.estimatedHours());
        task.setAssignee(resolveAssignee(project, request.assigneeId()));

        taskRepository.saveAndFlush(task);
        return TaskDtos.TaskResponse.from(task);
    }

    @Transactional
    public TaskDtos.TaskResponse update(UUID id, TaskDtos.UpdateTaskRequest request, AppUserPrincipal principal) {
        Task task = requireTask(id);
        access.requireContribute(task.getProject(), principal);
        requireFreshVersion(request.version(), task.getVersion());

        if (request.projectId() != null && !request.projectId().equals(task.getProject().getId())) {
            Project target = projectService.requireProject(request.projectId());
            access.requireContribute(target, principal);
            task.setProject(target);
        }
        if (request.title() != null) {
            if (!StringUtils.hasText(request.title())) {
                throw ApiException.badRequest("INVALID_TITLE", "title must not be blank");
            }
            task.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            task.setDescription(trimToNull(request.description()));
        }
        if (request.status() != null) {
            task.setStatus(request.status());
        }
        if (request.priority() != null) {
            task.setPriority(request.priority());
        }
        if (Boolean.TRUE.equals(request.clearDueDate())) {
            task.setDueDate(null);
        } else if (request.dueDate() != null) {
            task.setDueDate(request.dueDate());
        }
        if (Boolean.TRUE.equals(request.clearAssignee())) {
            task.setAssignee(null);
        } else if (request.assigneeId() != null) {
            task.setAssignee(resolveAssignee(task.getProject(), request.assigneeId()));
        }
        if (request.estimatedHours() != null) {
            task.setEstimatedHours(request.estimatedHours());
        }

        taskRepository.saveAndFlush(task);
        return TaskDtos.TaskResponse.from(task);
    }

    @Transactional
    public void delete(UUID id, AppUserPrincipal principal) {
        Task task = requireTask(id);
        access.requireContribute(task.getProject(), principal);
        taskRepository.delete(task);
    }

    /**
     * Optimistic concurrency: when the client echoes the version it loaded, a stale write is
     * rejected instead of silently overwriting another user's change.
     */
    private static void requireFreshVersion(Long requested, long current) {
        if (requested != null && requested != current) {
            throw ApiException.conflict("STALE_VERSION",
                    "This task changed since you loaded it. Refresh and apply your edit again.");
        }
    }

    private Task requireTask(UUID id) {
        return taskRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Task not found."));
    }

    /**
     * An assignee must be able to see the project; otherwise the task would leak project
     * metadata to a user with no access, and they could never open it.
     */
    private User resolveAssignee(Project project, UUID assigneeId) {
        if (assigneeId == null) {
            return null;
        }
        User assignee = userRepository.findById(assigneeId)
                .orElseThrow(() -> ApiException.badRequest("ASSIGNEE_NOT_FOUND", "Assignee does not exist."));
        boolean isOwner = project.getOwner().getId().equals(assignee.getId());
        boolean isMember = memberRepository.existsByProjectIdAndUserId(project.getId(), assignee.getId());
        if (!isOwner && !isMember && assignee.getRole() != com.taskhub.user.Role.ADMIN) {
            throw ApiException.badRequest("ASSIGNEE_NOT_PROJECT_MEMBER",
                    "The assignee must be a member of the project.");
        }
        return assignee;
    }

    private static Specification<Task> andIfPresent(Specification<Task> base, Specification<Task> extra) {
        return extra == null ? base : base.and(extra);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
