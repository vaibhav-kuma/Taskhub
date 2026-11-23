package com.taskhub.user;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

/** Minimal user projection used inside other resources (owner, assignee, member). */
@Schema(name = "UserSummary")
public record UserSummary(UUID id, String email, String displayName) {

    public static UserSummary from(User user) {
        return new UserSummary(user.getId(), user.getEmail(), user.getDisplayName());
    }
}
