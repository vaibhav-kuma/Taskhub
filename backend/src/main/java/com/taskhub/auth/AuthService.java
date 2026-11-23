package com.taskhub.auth;

import com.taskhub.common.exception.ApiException;
import com.taskhub.config.AppProperties;
import com.taskhub.security.AppUserPrincipal;
import com.taskhub.security.JwtService;
import com.taskhub.user.Role;
import com.taskhub.user.User;
import com.taskhub.user.UserRepository;
import com.taskhub.user.UserResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login, refresh-token rotation and logout.
 *
 * <p>Security properties:
 * <ul>
 *   <li>Generic {@code INVALID_CREDENTIALS} for both unknown email and bad password.</li>
 *   <li>A dummy BCrypt verification runs for unknown emails so response time does not reveal
 *       whether an account exists.</li>
 *   <li>Refresh tokens are single-use and rotated; replay revokes the whole token family.</li>
 * </ul>
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AppProperties properties;
    private final TokenFamilyRevoker tokenFamilyRevoker;
    private final boolean registrationEnabled;
    /** Hash of a random throwaway value, used to equalize timing for unknown accounts. */
    private final String dummyHash;

    public AuthService(UserRepository userRepository,
                       RefreshTokenRepository refreshTokenRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       AppProperties properties,
                       TokenFamilyRevoker tokenFamilyRevoker,
                       @org.springframework.beans.factory.annotation.Value(
                               "${taskhub.registration.enabled:true}") boolean registrationEnabled) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.properties = properties;
        this.tokenFamilyRevoker = tokenFamilyRevoker;
        this.registrationEnabled = registrationEnabled;
        this.dummyHash = passwordEncoder.encode(UUID.randomUUID().toString());
    }

    @Transactional
    public AuthDtos.TokenResponse register(AuthDtos.RegisterRequest request) {
        if (!registrationEnabled) {
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, "REGISTRATION_DISABLED",
                    "Self-service registration is disabled on this server.");
        }
        String email = User.normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw ApiException.conflict("EMAIL_ALREADY_REGISTERED", "An account with this email already exists.");
        }
        User user = new User(email, passwordEncoder.encode(request.password()), request.displayName().trim(),
                Role.MEMBER);
        userRepository.save(user);
        log.info("Registered new user id={}", user.getId());
        return issueTokens(user, UUID.randomUUID());
    }

    @Transactional
    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest request) {
        String email = User.normalizeEmail(request.email());
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null) {
            passwordEncoder.matches(request.password(), dummyHash);
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw invalidCredentials();
        }
        if (!user.isEnabled()) {
            throw ApiException.forbidden("This account is disabled. Contact an administrator.");
        }
        return issueTokens(user, UUID.randomUUID());
    }

    /**
     * Rotates a refresh token. Reuse of an already-rotated token is treated as theft: the entire
     * family is revoked and the caller must authenticate again.
     */
    @Transactional
    public AuthDtos.TokenResponse refresh(String rawToken) {
        String hash = sha256(rawToken);
        RefreshToken token = refreshTokenRepository.findByTokenHash(hash)
                .orElseThrow(() -> ApiException.unauthorized("INVALID_REFRESH_TOKEN",
                        "The refresh token is invalid."));

        if (token.isRevoked()) {
            // Committed in its own transaction: the 401 thrown below must not undo the revocation.
            token.revoke();
            int revoked = tokenFamilyRevoker.revokeFamily(token.getFamilyId());
            log.warn("Refresh token reuse detected for user={} family={}; revoked {} token(s)",
                    token.getUser().getId(), token.getFamilyId(), revoked);
            throw ApiException.unauthorized("REFRESH_TOKEN_REUSED",
                    "This refresh token has already been used. Sign in again.");
        }
        if (!token.getExpiresAt().isAfter(Instant.now())) {
            throw ApiException.unauthorized("INVALID_REFRESH_TOKEN", "The refresh token has expired.");
        }
        if (!token.getUser().isEnabled()) {
            throw ApiException.forbidden("This account is disabled. Contact an administrator.");
        }

        token.revoke();
        return issueTokens(token.getUser(), token.getFamilyId());
    }

    /** Revokes the supplied token family, or every session for the user when no token is given. */
    @Transactional
    public void logout(AppUserPrincipal principal, String rawRefreshToken) {
        if (rawRefreshToken != null && !rawRefreshToken.isBlank()) {
            refreshTokenRepository.findByTokenHash(sha256(rawRefreshToken))
                    .filter(token -> token.getUser().getId().equals(principal.id()))
                    .ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId()));
        } else {
            refreshTokenRepository.revokeAllForUser(principal.id());
        }
    }

    @Transactional(readOnly = true)
    public UserResponse currentUser(AppUserPrincipal principal) {
        return userRepository.findById(principal.id())
                .map(UserResponse::from)
                .orElseThrow(() -> ApiException.unauthorized("UNAUTHENTICATED", "Account no longer exists."));
    }

    private AuthDtos.TokenResponse issueTokens(User user, UUID familyId) {
        AppUserPrincipal principal = new AppUserPrincipal(user.getId(), user.getEmail(),
                user.getDisplayName(), user.getRole(), user.isEnabled());
        JwtService.IssuedToken access = jwtService.issueAccessToken(principal);

        String rawRefresh = generateRefreshToken();
        Instant refreshExpiry = Instant.now().plus(properties.jwt().refreshTokenTtl());
        refreshTokenRepository.save(new RefreshToken(user, sha256(rawRefresh), familyId, refreshExpiry));

        return new AuthDtos.TokenResponse(
                access.value(),
                "Bearer",
                access.expiresInSeconds(),
                rawRefresh,
                properties.jwt().refreshTokenTtl().toSeconds(),
                UserResponse.from(user));
    }

    private static String generateRefreshToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static ApiException invalidCredentials() {
        return ApiException.unauthorized("INVALID_CREDENTIALS", "Invalid email or password.");
    }
}
