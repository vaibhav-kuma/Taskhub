package com.taskhub.project;

import com.taskhub.common.PageResponse;
import com.taskhub.common.PageableFactory;
import com.taskhub.common.exception.ApiException;
import com.taskhub.config.AppProperties;
import com.taskhub.security.AppUserPrincipal;
import com.taskhub.user.User;
import com.taskhub.user.UserRepository;
import com.taskhub.user.UserSummary;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ProjectService {

    public static final Set<String> SORTABLE = Set.of("name", "code", "status", "createdAt", "updatedAt");

    private final ProjectRepository projectRepository;
    private final ProjectMemberRepository memberRepository;
    private final UserRepository userRepository;
    private final ProjectAccess access;
    private final AppProperties properties;

    public ProjectService(ProjectRepository projectRepository,
                          ProjectMemberRepository memberRepository,
                          UserRepository userRepository,
                          ProjectAccess access,
                          AppProperties properties) {
        this.projectRepository = projectRepository;
        this.memberRepository = memberRepository;
        this.userRepository = userRepository;
        this.access = access;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public PageResponse<ProjectDtos.ProjectResponse> list(Integer page, Integer size, String sort,
                                                          String direction, String search,
                                                          ProjectStatus status,
                                                          AppUserPrincipal principal) {
        Pageable pageable = PageableFactory.create(page, size, sort, direction, SORTABLE,
                properties.pagination().defaultSize(), properties.pagination().maxSize(), "createdAt");

        Specification<Project> spec = ProjectSpecifications.visibleTo(principal);
        Specification<Project> searchSpec = ProjectSpecifications.matches(search);
        if (searchSpec != null) {
            spec = spec.and(searchSpec);
        }
        Specification<Project> statusSpec = ProjectSpecifications.hasStatus(status);
        if (statusSpec != null) {
            spec = spec.and(statusSpec);
        }

        Page<Project> result = projectRepository.findAll(spec, pageable);
        Map<UUID, Long> memberCounts = memberCounts(result.getContent());
        return PageResponse.from(result, project -> toResponse(project, memberCounts), sort == null ? "createdAt" : sort,
                PageableFactory.normalizeDirection(direction));
    }

    @Transactional(readOnly = true)
    public ProjectDtos.ProjectResponse get(UUID id, AppUserPrincipal principal) {
        Project project = requireProject(id);
        access.requireRead(project, principal);
        return toResponse(project, Map.of(project.getId(), memberRepository.countByProjectId(project.getId())));
    }

    @Transactional
    public ProjectDtos.ProjectResponse create(ProjectDtos.CreateProjectRequest request,
                                              AppUserPrincipal principal) {
        String code = request.code().trim().toUpperCase();
        if (projectRepository.existsByCodeIgnoreCase(code)) {
            throw ApiException.conflict("PROJECT_CODE_TAKEN", "A project with code '" + code + "' already exists.");
        }
        User owner = userRepository.findById(principal.id())
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Account no longer exists."));

        Project project = new Project(code, request.name().trim(), trimToNull(request.description()),
                request.status(), owner);
        project.addMember(new ProjectMember(project, owner, ProjectRole.OWNER));
        projectRepository.saveAndFlush(project);
        return toResponse(project, Map.of(project.getId(), 1L));
    }

    @Transactional
    public ProjectDtos.ProjectResponse update(UUID id, ProjectDtos.UpdateProjectRequest request,
                                              AppUserPrincipal principal) {
        Project project = requireProject(id);
        access.requireManage(project, principal);
        if (request.version() != null && request.version() != project.getVersion()) {
            throw ApiException.conflict("STALE_VERSION",
                    "This project changed since you loaded it. Refresh and apply your edit again.");
        }

        if (request.code() != null) {
            String code = request.code().trim().toUpperCase();
            if (projectRepository.existsByCodeIgnoreCaseAndIdNot(code, id)) {
                throw ApiException.conflict("PROJECT_CODE_TAKEN",
                        "A project with code '" + code + "' already exists.");
            }
            project.setCode(code);
        }
        if (request.name() != null) {
            if (!StringUtils.hasText(request.name())) {
                throw ApiException.badRequest("INVALID_NAME", "name must not be blank");
            }
            project.setName(request.name().trim());
        }
        if (request.description() != null) {
            project.setDescription(trimToNull(request.description()));
        }
        if (request.status() != null) {
            project.setStatus(request.status());
        }
        projectRepository.saveAndFlush(project);
        return toResponse(project, Map.of(project.getId(), memberRepository.countByProjectId(id)));
    }

    @Transactional
    public void delete(UUID id, AppUserPrincipal principal) {
        Project project = requireProject(id);
        access.requireManage(project, principal);
        projectRepository.delete(project);
    }

    @Transactional(readOnly = true)
    public List<ProjectDtos.MemberResponse> members(UUID id, AppUserPrincipal principal) {
        Project project = requireProject(id);
        access.requireRead(project, principal);
        return memberRepository.findAllByProjectIdOrderByAddedAtAsc(id).stream()
                .map(ProjectDtos.MemberResponse::from)
                .toList();
    }

    @Transactional
    public ProjectDtos.MemberResponse addMember(UUID id, ProjectDtos.AddMemberRequest request,
                                                AppUserPrincipal principal) {
        Project project = requireProject(id);
        access.requireManage(project, principal);

        User user = userRepository.findById(request.userId())
                .orElseThrow(() -> ApiException.notFound("User not found."));
        if (memberRepository.existsByProjectIdAndUserId(id, user.getId())) {
            throw ApiException.conflict("ALREADY_A_MEMBER", "This user is already a member of the project.");
        }
        ProjectMember member = new ProjectMember(project, user, request.role() == null
                ? ProjectRole.CONTRIBUTOR : request.role());
        memberRepository.save(member);
        return ProjectDtos.MemberResponse.from(member);
    }

    @Transactional
    public ProjectDtos.MemberResponse changeMemberRole(UUID id, UUID userId, ProjectRole role,
                                                       AppUserPrincipal principal) {
        Project project = requireProject(id);
        access.requireManage(project, principal);
        ProjectMember member = memberRepository.findByProjectIdAndUserId(id, userId)
                .orElseThrow(() -> ApiException.notFound("Membership not found."));
        if (member.getUser().getId().equals(project.getOwner().getId()) && role != ProjectRole.OWNER) {
            throw ApiException.conflict("OWNER_ROLE_LOCKED", "The project owner must keep the OWNER role.");
        }
        member.setRole(role);
        memberRepository.save(member);
        return ProjectDtos.MemberResponse.from(member);
    }

    @Transactional
    public void removeMember(UUID id, UUID userId, AppUserPrincipal principal) {
        Project project = requireProject(id);
        access.requireManage(project, principal);
        if (project.getOwner().getId().equals(userId)) {
            throw ApiException.conflict("OWNER_CANNOT_BE_REMOVED", "The project owner cannot be removed.");
        }
        int removed = memberRepository.deleteMembership(id, userId);
        if (removed == 0) {
            throw ApiException.notFound("Membership not found.");
        }
    }

    /** Used by TaskService to resolve a project with the correct 404 semantics. */
    @Transactional(readOnly = true)
    public Project requireProject(UUID id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("Project not found."));
    }

    private Map<UUID, Long> memberCounts(List<Project> projects) {
        if (projects.isEmpty()) {
            return Map.of();
        }
        List<UUID> ids = projects.stream().map(Project::getId).toList();
        Map<UUID, Long> counts = new HashMap<>();
        for (Object[] row : projectRepository.countMembersByProjectIds(ids)) {
            counts.put((UUID) row[0], ((Number) row[1]).longValue());
        }
        return counts;
    }

    private ProjectDtos.ProjectResponse toResponse(Project project, Map<UUID, Long> memberCounts) {
        return new ProjectDtos.ProjectResponse(
                project.getId(),
                project.getCode(),
                project.getName(),
                project.getDescription(),
                project.getStatus(),
                UserSummary.from(project.getOwner()),
                memberCounts.getOrDefault(project.getId(), 0L),
                project.getVersion(),
                project.getCreatedAt(),
                project.getUpdatedAt());
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
