package com.sales.sync.auth.web;

import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Internal controller exposing the {@code GET /internal/auth/users/{id}}
 * endpoint consumed by {@code order-service.SyncAuthClient} for the
 * forward-direction cross-DB Vendor↔User integrity check (design §3.3,
 * D-06).
 *
 * <p>Security: gated by a separate {@code SecurityFilterChain} registered
 * under {@code InternalSecurityConfig} — a static bearer service-token
 * validator that does NOT participate in the JWT-based
 * {@code /api/v1/auth/**} chain of the auth-foundation.
 */
@RestController
public class InternalUserController {

    private final UserRepository users;

    public InternalUserController(UserRepository users) {
        this.users = users;
    }

    @GetMapping("/internal/auth/users/{id}")
    public ResponseEntity<?> getById(@PathVariable UUID id) {
        // sync-service's User entity currently has no `deleted_at` field, so
        // the `deletedAt` reported here is always null for now. The
        // cross-DB integrity check uses the `locked` flag as the
        // closest lifecycle proxy at this maturity (deferred: a follow-up
        // SDD migrates User to add a `deleted_at` column).
        return users.findById(id)
                .<ResponseEntity<?>>map(u -> ResponseEntity.ok().body(
                        new InternalUserResponse(u.getId(), u.getEmail(), u.isLocked(), null)))
                .orElseGet(() -> ResponseEntity
                        .status(404)
                        .body(Map.of("error", "user_not_found", "id", String.valueOf(id))));
    }
}