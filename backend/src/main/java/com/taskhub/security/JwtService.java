package com.taskhub.security;

import com.taskhub.config.AppProperties;
import com.taskhub.user.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Issues and verifies access tokens (HS256).
 *
 * <p>Access tokens are intentionally short-lived (15 min by default) and stateless: there is no
 * server-side revocation list. Session termination is achieved by revoking the refresh token,
 * which makes the next access-token renewal fail. This trade-off is documented in SECURITY.md.
 */
@Service
public class JwtService {

    private static final int MIN_SECRET_BYTES = 32;

    private final AppProperties.Jwt config;
    private final SecretKey key;

    public JwtService(AppProperties properties) {
        this.config = properties.jwt();
        this.key = buildKey(config.secret());
    }

    private static SecretKey buildKey(String base64Secret) {
        byte[] decoded;
        try {
            decoded = Decoders.BASE64.decode(base64Secret);
            if (decoded.length < MIN_SECRET_BYTES) {
                decoded = base64Secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
        } catch (RuntimeException ex) {
            decoded = base64Secret.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        if (decoded.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("taskhub.jwt.secret must decode to at least "
                    + MIN_SECRET_BYTES + " bytes (got " + decoded.length + ").");
        }
        return Keys.hmacShaKeyFor(decoded);
    }

    public IssuedToken issueAccessToken(AppUserPrincipal principal) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(config.accessTokenTtl());
        String token = Jwts.builder()
                .subject(principal.id().toString())
                .issuer(config.issuer())
                .audience().add(config.audience()).and()
                .id(UUID.randomUUID().toString())
                .claim("email", principal.email())
                .claim("name", principal.displayName())
                .claim("role", principal.role().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expiresAt, config.accessTokenTtl().toSeconds());
    }

    /**
     * Verifies signature, issuer, audience and expiry.
     *
     * @throws io.jsonwebtoken.JwtException when the token is invalid, tampered with, or expired
     */
    public AppUserPrincipal parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(config.issuer())
                .requireAudience(config.audience())
                .build()
                .parseSignedClaims(token)
                .getPayload();

        String role = claims.get("role", String.class);
        if (role == null || claims.getSubject() == null) {
            throw new io.jsonwebtoken.MalformedJwtException("Token is missing required claims");
        }
        return new AppUserPrincipal(
                UUID.fromString(claims.getSubject()),
                claims.get("email", String.class),
                claims.get("name", String.class),
                Role.valueOf(role),
                true);
    }

    public long accessTokenTtlSeconds() {
        return config.accessTokenTtl().toSeconds();
    }

    public record IssuedToken(String value, Instant expiresAt, long expiresInSeconds) {
    }
}
