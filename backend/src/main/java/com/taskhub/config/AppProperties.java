package com.taskhub.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Typed, validated view of every {@code taskhub.*} property.
 *
 * <p>Binding is validated at startup, so a missing or malformed security setting fails the
 * deploy instead of silently degrading security at runtime.
 */
@Validated
@ConfigurationProperties(prefix = "taskhub")
public record AppProperties(
        @Valid Jwt jwt,
        @Valid Cors cors,
        @Valid RateLimit rateLimit,
        @Valid Pagination pagination) {

    public record Jwt(
            @NotBlank String secret,
            @NotBlank String issuer,
            @NotBlank String audience,
            Duration accessTokenTtl,
            Duration refreshTokenTtl) {

        public Jwt {
            if (accessTokenTtl == null) {
                accessTokenTtl = Duration.ofMinutes(15);
            }
            if (refreshTokenTtl == null) {
                refreshTokenTtl = Duration.ofDays(7);
            }
        }
    }

    public record Cors(@NotEmpty List<String> allowedOrigins) {
    }

    public record RateLimit(boolean enabled, @Positive int authAttempts, Duration authWindow) {
        public RateLimit {
            if (authWindow == null) {
                authWindow = Duration.ofMinutes(5);
            }
        }
    }

    public record Pagination(@Positive int defaultSize, @Positive int maxSize) {
        public Pagination {
            if (defaultSize <= 0) {
                defaultSize = 20;
            }
            if (maxSize <= 0) {
                maxSize = 100;
            }
        }
    }
}
