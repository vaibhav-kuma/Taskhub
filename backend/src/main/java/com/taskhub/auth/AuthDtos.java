package com.taskhub.auth;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Authentication payloads. Kept as records so validation rules are declared declaratively. */
public final class AuthDtos {

    private AuthDtos() {
    }

    @Schema(name = "RegisterRequest")
    public record RegisterRequest(
            @NotBlank(message = "email is required")
            @Email(message = "must be a valid email address")
            @Size(max = 255, message = "must be at most 255 characters")
            String email,

            @NotBlank(message = "password is required")
            @Size(min = 12, max = 128, message = "must be between 12 and 128 characters")
            @Pattern(regexp = ".*[A-Za-z].*", message = "must contain at least one letter")
            @Pattern(regexp = ".*\\d.*", message = "must contain at least one digit")
            String password,

            @NotBlank(message = "displayName is required")
            @Size(min = 2, max = 120, message = "must be between 2 and 120 characters")
            String displayName) {
    }

    @Schema(name = "LoginRequest")
    public record LoginRequest(
            @NotBlank(message = "email is required")
            @Email(message = "must be a valid email address")
            String email,

            @NotBlank(message = "password is required")
            String password) {
    }

    @Schema(name = "RefreshRequest")
    public record RefreshRequest(
            @NotBlank(message = "refreshToken is required") String refreshToken) {
    }

    @Schema(name = "LogoutRequest")
    public record LogoutRequest(String refreshToken) {
    }

    @Schema(name = "TokenResponse", description = "Issued credential pair plus the authenticated profile")
    public record TokenResponse(
            String accessToken,
            String tokenType,
            long expiresIn,
            String refreshToken,
            long refreshExpiresIn,
            com.taskhub.user.UserResponse user) {
    }
}
