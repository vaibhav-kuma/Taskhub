package com.taskhub.task;

import com.taskhub.common.PageableFactory;
import com.taskhub.project.ProjectMember;
import com.taskhub.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

public final class TaskSpecifications {

    private TaskSpecifications() {
    }

    /** Tasks are visible when their project is visible (admin, owner or member). */
    public static Specification<Task> visibleTo(AppUserPrincipal principal) {
        return (root, query, cb) -> {
            if (principal.isAdmin()) {
                return cb.conjunction();
            }
            var project = root.get("project");
            var owned = cb.equal(project.get("owner").get("id"), principal.id());
            var memberSubquery = query.subquery(UUID.class);
            var memberRoot = memberSubquery.from(ProjectMember.class);
            memberSubquery.select(memberRoot.get("project").get("id"))
                    .where(cb.equal(memberRoot.get("user").get("id"), principal.id()));
            return cb.or(owned, project.get("id").in(memberSubquery));
        };
    }

    public static Specification<Task> matches(String search) {
        String pattern = PageableFactory.likePattern(search, true);
        if (!StringUtils.hasText(pattern)) {
            return null;
        }
        return (root, query, cb) -> {
            List<Predicate> alternatives = new ArrayList<>();
            alternatives.add(cb.like(cb.lower(root.get("title")), pattern, '\\'));
            alternatives.add(cb.like(cb.lower(root.get("description")), pattern, '\\'));
            return cb.or(alternatives.toArray(Predicate[]::new));
        };
    }

    public static Specification<Task> hasStatus(TaskStatus status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Task> hasPriority(TaskPriority priority) {
        return priority == null ? null : (root, query, cb) -> cb.equal(root.get("priority"), priority);
    }

    public static Specification<Task> inProject(UUID projectId) {
        return projectId == null ? null
                : (root, query, cb) -> cb.equal(root.get("project").get("id"), projectId);
    }

    public static Specification<Task> assignedTo(UUID assigneeId) {
        return assigneeId == null ? null
                : (root, query, cb) -> cb.equal(root.get("assignee").get("id"), assigneeId);
    }

    /** Overdue = due date in the past and not DONE. {@code overdue=false} returns the complement. */
    public static Specification<Task> overdue(Boolean overdue) {
        if (overdue == null) {
            return null;
        }
        return (root, query, cb) -> {
            var pastDue = cb.lessThan(root.get("dueDate"), LocalDate.now());
            var notDone = cb.notEqual(root.get("status"), TaskStatus.DONE);
            var isOverdue = cb.and(cb.isNotNull(root.get("dueDate")), pastDue, notDone);
            return overdue ? isOverdue : cb.not(isOverdue);
        };
    }
}
