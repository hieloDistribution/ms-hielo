package com.sales.sync.auth.security;

import com.sales.sync.auth.model.User;
import org.springframework.beans.factory.annotation.Configurable;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;
import org.springframework.web.context.WebApplicationContext;

import java.util.Optional;
import java.util.UUID;

/**
 * Request-scoped container for the authenticated principal parsed from the JWT.
 * Populated by {@link JwtAuthenticationFilter} (which runs in the servlet
 * filter chain, BEFORE the DispatcherServlet activates the request scope).
 *
 * <p>{@code proxyMode = TARGET_CLASS} is required so the filter — which
 * is itself a singleton — can inject a scoped proxy. Without the proxy,
 * the filter receives a singleton instance and its writes never reach the
 * per-request instance that the controllers see, which manifests as
 * {@code IllegalStateException("No authenticated user in this request")}
 * even on requests where the JWT was successfully parsed.
 */
@Component
@Scope(value = WebApplicationContext.SCOPE_REQUEST, proxyMode = ScopedProxyMode.TARGET_CLASS)
@Configurable
public class AuthContext {

    private JwtService.ParsedToken parsed;

    public void set(JwtService.ParsedToken parsed) {
        this.parsed = parsed;
    }

    public void clear() {
        this.parsed = null;
    }

    public Optional<JwtService.ParsedToken> get() {
        return Optional.ofNullable(parsed);
    }

    public UUID requireUserId() {
        if (parsed == null) {
            throw new IllegalStateException("No authenticated user in this request");
        }
        return parsed.userId();
    }

    public User.Role requireRole() {
        if (parsed == null) {
            throw new IllegalStateException("No authenticated user in this request");
        }
        if (parsed.roles().isEmpty()) {
            throw new IllegalStateException("Authenticated user has no roles");
        }
        return parsed.roles().iterator().next();
    }

    /**
     * Returns the full multi-role set of the authenticated user.
     */
    public java.util.Set<User.Role> requireRoles() {
        if (parsed == null) {
            throw new IllegalStateException("No authenticated user in this request");
        }
        return parsed.roles();
    }
}