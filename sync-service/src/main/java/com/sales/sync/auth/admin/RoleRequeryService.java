package com.sales.sync.auth.admin;

import com.sales.sync.auth.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Re-queries the {@code users} table for an active admin before honoring
 * any {@code /api/v1/admin/**} request. This is the "authority rule for
 * elevated actions" half of the dual-authority contract: the JWT claim
 * is authoritative for the gate (routing), but elevated operations
 * MUST verify that the user row is still {@code active=true} and still
 * carries the {@code admin} role. Mirrors the canonical
 * "vendor_id is informational only" rule from
 * {@code openspec/specs/auth/spec.md}.
 *
 * <p>Owner: change {@code admin-console} PR3.
 */
@Service
public class RoleRequeryService {

    private final UserRepository users;

    public RoleRequeryService(UserRepository users) {
        this.users = users;
    }

    /**
     * @param userId the JWT subject ({@code sub} claim).
     * @return {@code true} if the user row exists, is {@code active=true},
     *         and has the {@code admin} role in its multi-role set.
     */
    public boolean isActiveAdmin(UUID userId) {
        if (userId == null) {
            return false;
        }
        // The query JOIN through user_roles to roles and filters by
        // roles.name='admin' AND users.active=true.
        return users.countActiveByRoleName("admin") > 0
                && users.findById(userId)
                        .map(u -> u.isActive() && u.getRoles().stream()
                                .anyMatch(r -> "admin".equals(r.getName())))
                        .orElse(false);
    }
}
