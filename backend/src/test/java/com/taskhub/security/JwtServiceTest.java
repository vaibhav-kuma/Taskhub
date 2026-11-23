package com.taskhub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.taskhub.config.AppProperties;
import com.taskhub.user.Role;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {

    private static final String SECRET = "Fz6xFaB60vuvjQ+QpamwKgojDWHUhqvNHlukJOcbpVWRuO9QTWqVYtuzO3QhCAc/";

    private AppProperties props(String secret, Duration accessTtl) {
        return new AppProperties(
                new AppProperties.Jwt(secret, "taskhub-api", "taskhub-client", accessTtl, Duration.ofDays(7)),
                new AppProperties.Cors(List.of("http://localhost")),
                new AppProperties.RateLimit(true, 10, Duration.ofMinutes(5)),
                new AppProperties.Pagination(20, 100));
    }

    private AppUserPrincipal principal() {
        return new AppUserPrincipal(UUID.randomUUID(), "ada@example.com", "Ada", Role.ADMIN, true);
    }

    @Test
    void issuesAndParsesTokenWithAllClaims() {
        JwtService service = new JwtService(props(SECRET, Duration.ofMinutes(15)));
        AppUserPrincipal principal = principal();

        JwtService.IssuedToken token = service.issueAccessToken(principal);
        AppUserPrincipal parsed = service.parse(token.value());

        assertThat(parsed.id()).isEqualTo(principal.id());
        assertThat(parsed.email()).isEqualTo(principal.email());
        assertThat(parsed.role()).isEqualTo(Role.ADMIN);
        assertThat(token.expiresInSeconds()).isEqualTo(900);
    }

    @Test
    void rejectsTamperedSignature() {
        JwtService service = new JwtService(props(SECRET, Duration.ofMinutes(15)));
        String token = service.issueAccessToken(principal()).value();
        String tampered = token.substring(0, token.length() - 3)
                + (token.endsWith("aaa") ? "bbb" : "aaa");

        assertThatThrownBy(() -> service.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() throws InterruptedException {
        JwtService service = new JwtService(props(SECRET, Duration.ofMillis(1)));
        String token = service.issueAccessToken(principal()).value();
        Thread.sleep(50);

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void rejectsTokenFromAnotherIssuer() {
        JwtService issuing = new JwtService(props(SECRET, Duration.ofMinutes(15)));
        String token = issuing.issueAccessToken(principal()).value();

        AppProperties otherIssuer = new AppProperties(
                new AppProperties.Jwt(SECRET, "someone-else", "taskhub-client", Duration.ofMinutes(15), Duration.ofDays(7)),
                new AppProperties.Cors(List.of()),
                new AppProperties.RateLimit(true, 10, Duration.ofMinutes(5)),
                new AppProperties.Pagination(20, 100));

        assertThatThrownBy(() -> new JwtService(otherIssuer).parse(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenWithWrongAudience() {
        JwtService issuing = new JwtService(props(SECRET, Duration.ofMinutes(15)));
        String token = issuing.issueAccessToken(principal()).value();

        AppProperties otherAudience = new AppProperties(
                new AppProperties.Jwt(SECRET, "taskhub-api", "another-client", Duration.ofMinutes(15), Duration.ofDays(7)),
                new AppProperties.Cors(List.of()),
                new AppProperties.RateLimit(true, 10, Duration.ofMinutes(5)),
                new AppProperties.Pagination(20, 100));

        assertThatThrownBy(() -> new JwtService(otherAudience).parse(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void failsFastOnTooShortSecret() {
        String shortSecret = java.util.Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new JwtService(props(shortSecret, Duration.ofMinutes(15))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 32 bytes");
    }

    @Test
    void failsFastOnNonBase64Secret() {
        assertThatThrownBy(() -> new JwtService(props("not base64 !!!", Duration.ofMinutes(15))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Base64");
    }
}
