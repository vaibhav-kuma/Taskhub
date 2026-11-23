package com.taskhub.project;

import com.taskhub.common.exception.ApiException;
import com.taskhub.security.AppUserPrincipal;
import org.springframework.stereotype.Component;

/**
 * Single place where project row-level authorization is decided.
 *
 * <p>Every service entry point calls one of the {@code require*} methods, so a new endpoint
 * cannot accidentally ship without an authorization check.
 *
 * <ul>
 *   <li>read — ADMIN, the owner, or any member (including VIEWER)</li>
 *   <li>contribute — ADMIN, the owner, or a CONTRIBUTOR (task mutations)</li>
 *   <li>manage — ADMIN or the owner (project fields, membership, deletion)</li>
 * </ul>
 */
@Component
public class ProjectAccess {

    private final ProjectMemberRepository memberRepository;

    public ProjectAccess(ProjectMemberRepository memberRepository) {
        this.memberRepository = memberRepository;
    }

    public boolean canRead(Project project, AppUserPrincipal principal) {
        return principal.isAdmin()
                || isOwner(project, principal)
                || memberRepository.existsByProjectIdAndUserId(project.getId(), principal.id());
    }

    public boolean canContribute(Project project, AppUserPrincipal principal) {
        if (principal.isAdmin() || isOwner(project, principal)) {
            return true;
        }
        return memberRepository.findByProjectIdAndUserId(project.getId(), principal.id())
                .map(member -> member.getRole() != ProjectRole.VIEWER)
                .orElse(false);
    }

    public boolean canManage(Project project, AppUserPrincipal principal) {
        return principal.isAdmin() || isOwner(project, principal);
    }

    public void requireRead(Project project, AppUserPrincipal principal) {
        if (!canRead(project, principal)) {
            throw ApiException.forbidden("You do not have access to this project.");
        }
    }

    public void requireContribute(Project project, AppUserPrincipal principal) {
        if (!canContribute(project, principal)) {
            throw ApiException.forbidden("You need contributor access to this project.");
        }
    }

    public void requireManage(Project project, AppUserPrincipal principal) {
        if (!canManage(project, principal)) {
            throw ApiException.forbidden("Only the project owner can perform this action.");
        }
    }

    private static boolean isOwner(Project project, AppUserPrincipal principal) {
        return project.getOwner() != null && project.getOwner().getId().equals(principal.id());
    }
}
