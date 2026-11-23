package com.taskhub.auth;

import com.taskhub.user.UserRepository;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Housekeeping: keeps the refresh-token table small. Expired and long-revoked rows are deleted
 * daily; tokens older than the retention window are useless by then.
 */
@Component
@Profile("!test")
public class RefreshTokenCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanupJob.class);
    private static final long RETENTION_DAYS = 30;

    private final RefreshTokenRepository refreshTokenRepository;
    private final UserRepository userRepository;

    public RefreshTokenCleanupJob(RefreshTokenRepository refreshTokenRepository, UserRepository userRepository) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
    }

    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purge() {
        Instant cutoff = Instant.now().minusSeconds(RETENTION_DAYS * 24 * 3600);
        int deleted = refreshTokenRepository.deleteExpired(cutoff);
        if (deleted > 0) {
            log.info("Purged {} expired/revoked refresh tokens (users={})", deleted, userRepository.count());
        }
    }
}
