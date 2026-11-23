package com.taskhub.project;

import com.taskhub.common.PageableFactory;
import com.taskhub.security.AppUserPrincipal;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.util.StringUtils;

/**
 * Composable filters. Specifications are used instead of a single JPQL string with nullable
 * parameters because Criteria predicates are type-safe and avoid PostgreSQL "could not determine
 * data type of parameter" surprises.
 */
public final class ProjectSpecifications {

    private ProjectSpecifications() {
    }

    /** Admin sees everything; everyone else sees owned + member projects. */
    public static Specification<Project> visibleTo(AppUserPrincipal principal) {
        return (root, query, cb) -> {
            if (principal.isAdmin()) {
                return cb.conjunction();
            }
            var owned = cb.equal(root.get("owner").get("id"), principal.id());
            var memberSubquery = query.subquery(UUID.class);
            var memberRoot = memberSubquery.from(ProjectMember.class);
            memberSubquery.select(memberRoot.get("project").get("id"))
                    .where(cb.equal(memberRoot.get("user").get("id"), principal.id()));
            return cb.or(owned, root.get("id").in(memberSubquery));
        };
    }

    public static Specification<Project> matches(String search) {
        String pattern = PageableFactory.likePattern(search, true);
        if (!StringUtils.hasText(pattern)) {
            return null;
        }
        return (root, query, cb) -> {
            List<Predicate> alternatives = new ArrayList<>();
            alternatives.add(cb.like(cb.lower(root.get("name")), pattern, '\\'));
            alternatives.add(cb.like(cb.lower(root.get("code")), pattern, '\\'));
            alternatives.add(cb.like(cb.lower(root.get("description")), pattern, '\\'));
            return cb.or(alternatives.toArray(Predicate[]::new));
        };
    }

    public static Specification<Project> hasStatus(ProjectStatus status) {
        return status == null ? null : (root, query, cb) -> cb.equal(root.get("status"), status);
    }
}
