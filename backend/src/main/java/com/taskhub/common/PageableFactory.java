package com.taskhub.common;

import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Builds a {@link Pageable} from untrusted query parameters.
 *
 * <p>Hardening: page/size are bounded, and {@code sort} must come from a per-endpoint allow-list.
 * Passing user input straight into {@code Sort.by()} is how ORDER BY injection and accidental
 * full-table sorts get in.
 */
public final class PageableFactory {

    private PageableFactory() {
    }

    public static Pageable create(Integer page, Integer size, String sort, String direction,
                                  Set<String> allowedSorts, int defaultSize, int maxSize,
                                  String defaultSort) {
        int safePage = page == null ? 0 : page;
        if (safePage < 0) {
            throw com.taskhub.common.exception.ApiException.badRequest("INVALID_PAGE", "page must be >= 0");
        }
        int requested = size == null ? defaultSize : size;
        if (requested < 1) {
            throw com.taskhub.common.exception.ApiException.badRequest("INVALID_SIZE", "size must be >= 1");
        }
        int safeSize = Math.min(requested, maxSize);

        String requestedSort = (sort == null || sort.isBlank()) ? defaultSort : sort.trim();
        if (!allowedSorts.contains(requestedSort)) {
            throw com.taskhub.common.exception.ApiException.badRequest("INVALID_SORT",
                    "Unsupported sort field. Allowed: " + String.join(", ", allowedSorts));
        }

        Sort.Direction dir = "asc".equalsIgnoreCase(direction) ? Sort.Direction.ASC : Sort.Direction.DESC;
        return PageRequest.of(safePage, safeSize, Sort.by(dir, requestedSort));
    }

    public static String normalizeDirection(String direction) {
        return "asc".equalsIgnoreCase(direction) ? "asc" : "desc";
    }

    /**
     * Builds a case-insensitive CONTAINS pattern and escapes LIKE wildcards, so a search term is
     * always treated literally. Contains-matching matches user expectation in the UI; for very
     * large tables add a pg_trgm GIN index (see docs/DEPLOYMENT.md#search-performance).
     */
    public static String likePattern(String search, boolean caseInsensitive) {
        if (search == null) {
            return null;
        }
        String trimmed = search.trim();
        if (trimmed.length() < 2) {
            return null;
        }
        String capped = trimmed.length() > 120 ? trimmed.substring(0, 120) : trimmed;
        String escaped = capped.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        String pattern = (caseInsensitive ? escaped.toLowerCase(Locale.ROOT) : escaped);
        return "%" + pattern + "%";
    }
}
