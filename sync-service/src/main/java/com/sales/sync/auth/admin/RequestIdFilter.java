package com.sales.sync.auth.admin;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Stamps a per-request id used by {@link AuditLogWriter} and surfaced
 * in the {@code X-Request-Id} response header. If the caller passes
 * an {@code X-Request-Id} header (UUID-shaped), it is reused; otherwise
 * a fresh UUID is generated. The id is exposed to downstream services
 * via a request attribute ({@link #REQUEST_ID_ATTR}) and to audit
 * writers via a thread-local snapshot ({@link #current()}).
 *
 * <p>Owner: change {@code admin-console} PR4.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String REQUEST_ID_ATTR = "request.id";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    /** Read by {@link AuditLogWriter} when constructing an {@code AuditEvent}. */
    public static String current() {
        String id = CURRENT.get();
        return id != null ? id : UUID.randomUUID().toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain)
            throws ServletException, IOException {
        String incoming = request.getHeader(REQUEST_ID_HEADER);
        String id = (incoming != null && isUuid(incoming)) ? incoming : UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID_ATTR, id);
        response.setHeader(REQUEST_ID_HEADER, id);
        CURRENT.set(id);
        try {
            chain.doFilter(request, response);
        } finally {
            CURRENT.remove();
        }
    }

    private static boolean isUuid(String s) {
        if (s.length() != 36) return false;
        try {
            UUID.fromString(s);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
