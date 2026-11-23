package com.taskhub.user;

import com.taskhub.common.PageResponse;
import com.taskhub.common.PageableFactory;
import com.taskhub.common.exception.ApiException;
import com.taskhub.config.AppProperties;
import com.taskhub.security.AppUserPrincipal;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private static final Set<String> SORTABLE = Set.of("displayName", "email", "role", "createdAt");

    private final UserRepository userRepository;
    private final AppProperties properties;

    public UserService(UserRepository userRepository, AppProperties properties) {
        this.userRepository = userRepository;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> list(Integer page, Integer size, String search, Boolean enabled,
                                           AppUserPrincipal principal) {
        Pageable pageable = PageableFactory.create(page, size, "displayName", "asc", SORTABLE,
                properties.pagination().defaultSize(), properties.pagination().maxSize(), "displayName");

        Specification<User> spec = (root, query, cb) -> cb.conjunction();
        String pattern = PageableFactory.likePattern(search, true);
        if (pattern != null) {
            spec = spec.and((root, query, cb) -> cb.or(
                    cb.like(cb.lower(root.get("displayName")), pattern, '\\'),
                    cb.like(cb.lower(root.get("email")), pattern, '\\')));
        }
        if (enabled != null) {
            spec = spec.and((root, query, cb) -> cb.equal(root.get("enabled"), enabled));
        }

        Page<User> result = userRepository.findAll(spec, pageable);
        return PageResponse.from(result, UserResponse::from, "displayName", "asc");
    }

    @Transactional(readOnly = true)
    public UserResponse get(UUID id) {
        return userRepository.findById(id).map(UserResponse::from)
                .orElseThrow(() -> ApiException.notFound("User not found."));
    }

    @Transactional
    public UserResponse updateRole(UUID id, Role role, AppUserPrincipal actingUser) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("User not found."));
        if (user.getRole() == Role.ADMIN && role != Role.ADMIN && userRepository.countByRole(Role.ADMIN) <= 1) {
            throw ApiException.conflict("LAST_ADMIN_PROTECTED", "The last administrator cannot lose the ADMIN role.");
        }
        if (user.getId().equals(actingUser.id()) && role != Role.ADMIN) {
            throw ApiException.conflict("SELF_DEMOTION_BLOCKED",
                    "You cannot change your own role. Ask another administrator.");
        }
        user.setRole(role);
        return UserResponse.from(userRepository.save(user));
    }

    @Transactional
    public UserResponse setEnabled(UUID id, boolean enabled, AppUserPrincipal actingUser) {
        User user = userRepository.findById(id)
                .orElseThrow(() -> ApiException.notFound("User not found."));
        if (!enabled) {
            if (user.getId().equals(actingUser.id())) {
                throw ApiException.conflict("SELF_DISABLE_BLOCKED", "You cannot disable your own account.");
            }
            if (user.getRole() == Role.ADMIN && userRepository.countByRole(Role.ADMIN) <= 1) {
                throw ApiException.conflict("LAST_ADMIN_PROTECTED", "The last administrator cannot be disabled.");
            }
        }
        user.setEnabled(enabled);
        return UserResponse.from(userRepository.save(user));
    }
}
