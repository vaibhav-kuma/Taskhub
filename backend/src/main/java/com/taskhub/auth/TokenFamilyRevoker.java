package com.taskhub.auth;

import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Revokes a token family in its own transaction.
 *
 * <p>This exists because the caller deliberately throws afterwards (reuse detected → 401).
 * A plain call inside the caller's transaction would be rolled back by that exception, so the
 * revocation would silently never persist — a real vulnerability. {@code REQUIRES_NEW} commits
 * the revocation independently of the request outcome.
 */
@Component
public class TokenFamilyRevoker {

    private final RefreshTokenRepository refreshTokenRepository;

    public TokenFamilyRevoker(RefreshTokenRepository refreshTokenRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeFamily(UUID familyId) {
        return refreshTokenRepository.revokeFamily(familyId);
    }
}
