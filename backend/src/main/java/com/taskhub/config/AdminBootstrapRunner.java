package com.taskhub.config;

import com.taskhub.user.Role;
import com.taskhub.user.User;
import com.taskhub.user.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the first administrator from environment variables when the database has no admin yet.
 *
 * <p>Public registration only ever grants MEMBER, so a fresh production deployment needs exactly
 * one bootstrap step. This is idempotent and never runs when an ADMIN already exists, which makes
 * it safe to leave configured across restarts. The password is never logged.
 */
@Component
@Profile("!test")
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String email;
    private final String password;
    private final String displayName;

    public AdminBootstrapRunner(UserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                @Value("${taskhub.bootstrap.admin-email:}") String email,
                                @Value("${taskhub.bootstrap.admin-password:}") String password,
                                @Value("${taskhub.bootstrap.admin-name:Administrator}") String displayName) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.email = email == null ? "" : email.trim();
        this.password = password == null ? "" : password;
        this.displayName = displayName == null || displayName.isBlank() ? "Administrator" : displayName.trim();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (email.isBlank()) {
            return;
        }
        if (userRepository.countByRole(Role.ADMIN) > 0) {
            log.debug("Administrator already exists; bootstrap skipped");
            return;
        }
        if (password.length() < 12) {
            log.warn("TASKHUB_BOOTSTRAP_ADMIN_PASSWORD is missing or shorter than 12 characters. "
                    + "No administrator was created.");
            return;
        }
        if (userRepository.existsByEmail(User.normalizeEmail(email))) {
            log.warn("Cannot bootstrap administrator: the email is already registered as a non-admin user.");
            return;
        }

        User admin = new User(email, passwordEncoder.encode(password), displayName, Role.ADMIN);
        userRepository.save(admin);
        log.info("Bootstrapped administrator account {}", User.normalizeEmail(email));
    }
}
