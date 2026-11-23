package com.taskhub.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.taskhub.common.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sliding-window rate limiter for the credential endpoints (brute-force / credential-stuffing
 * mitigation).
 *
 * <p>State is per-instance, which is exact for a single node and "best effort" when scaled out.
 * For a multi-instance deployment the runbook documents swapping the store for Redis; the filter
 * contract stays identical.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final List<String> GUARDED_PATHS =
            List.of("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/refresh");

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();
    private volatile long lastSweep = System.currentTimeMillis();

    public RateLimitFilter(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.rateLimit().enabled()
                || GUARDED_PATHS.stream().noneMatch(path -> path.equals(request.getRequestURI()));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String key = clientIp(request) + "|" + request.getRequestURI();
        if (!allowed(key)) {
            log.warn("Rate limit exceeded for {}", key);
            long retryAfter = properties.rateLimit().authWindow().toSeconds();
            response.setHeader(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter));
            var error = new ApiError(Instant.now(), 429, "RATE_LIMITED",
                    "Too many authentication attempts. Try again later.",
                    request.getRequestURI(), MDC.get(RequestIdFilter.MDC_REQUEST_ID), List.of());
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            objectMapper.writeValue(response.getWriter(), error);
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean allowed(String key) {
        long now = System.currentTimeMillis();
        long windowMillis = properties.rateLimit().authWindow().toMillis();
        sweepIfNeeded(now, windowMillis);

        Deque<Long> timestamps = attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && now - timestamps.peekFirst() > windowMillis) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= properties.rateLimit().authAttempts()) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    private void sweepIfNeeded(long now, long windowMillis) {
        if (now - lastSweep < windowMillis) {
            return;
        }
        lastSweep = now;
        attempts.entrySet().removeIf(entry -> {
            Deque<Long> timestamps = entry.getValue();
            synchronized (timestamps) {
                return timestamps.isEmpty() || now - timestamps.peekLast() > windowMillis;
            }
        });
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
