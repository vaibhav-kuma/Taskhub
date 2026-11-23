package com.taskhub.security;

import com.taskhub.common.exception.ApiException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Convenience access to the authenticated principal. */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static AppUserPrincipal currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AppUserPrincipal principal)) {
            throw ApiException.unauthorized("UNAUTHENTICATED", "Authentication is required.");
        }
        return principal;
    }
}
