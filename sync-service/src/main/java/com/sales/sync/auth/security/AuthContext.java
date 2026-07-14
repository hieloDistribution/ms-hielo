package com.sales.sync.auth.security;

import com.sales.sync.auth.model.User;
import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.Optional;
import java.util.UUID;

/**
 * Request-scoped container for the authenticated principal parsed from the JWT.
 * Populated by {@link JwtAuthenticationFilter}.
 */
@Component
@RequestScope
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
        return parsed.role();
    }
}