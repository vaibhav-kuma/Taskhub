package com.taskhub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Content-Security-Policy for the bundled browser client.
 *
 * <p>Applied only to the SPA entry document: Swagger UI relies on inline styles and would break
 * under this policy, and JSON API responses do not need one. The SPA itself loads a single
 * external stylesheet and script, so a strict {@code 'self'} policy is enough.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SpaSecurityHeadersFilter extends OncePerRequestFilter {

    private static final Set<String> SPA_DOCUMENTS = Set.of("/", "/index.html");

    private static final String CSP = String.join("; ",
            "default-src 'self'",
            "script-src 'self'",
            "style-src 'self'",
            "img-src 'self' data:",
            "font-src 'self'",
            "connect-src 'self'",
            "frame-ancestors 'none'",
            "base-uri 'self'",
            "form-action 'self'");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SPA_DOCUMENTS.contains(request.getRequestURI())) {
            response.setHeader("Content-Security-Policy", CSP);
            response.setHeader("Cache-Control", "no-cache");
        }
        chain.doFilter(request, response);
    }
}
