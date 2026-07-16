package com.sales.sync.auth.admin;

import com.sales.sync.auth.repository.UserRepository;
import org.springframework.stereotype.Component;

/**
 * Helper that prevents the last active admin from locking themselves
 * out of the system by self-demoting or self-deactivating.
 *
 * <p>Owner: change {@code admin-console} PR3 (stub) / PR4 (full wiring).
 * PR3 ships the count method; PR4 calls it from
 * {@code AdminService.changeRoles} and {@code AdminService.deactivate}.
 */
@Component
public class LastAdminGuard {

    private final UserRepository users;

    public LastAdminGuard(UserRepository users) {
        this.users = users;
    }

    /**
     * Throws if {@code selfUserId} is the ONLY active admin in the
     * system. Caller is responsible for catching
     * {@link CannotSelfDemoteLastAdmin} / {@link CannotDeactivateLastAdmin}
     * and mapping to 409.
     *
     * <p>The two exception types are distinct so that the HTTP layer
     * can produce the right {@code error} code per PR4's contract
     * (R4 self-demote: 409 {@code cannot_self_demote_last_admin};
     *  R5 self-deactivate: 409 {@code cannot_deactivate_last_admin}).
     */
    public void requireNotLastAdmin(java.util.UUID selfUserId) {
        long others = users.countActiveByRoleName("admin");
        // countActiveByRoleName counts ALL active admins. If > 1, the
        // caller is not the last one. We subtract 1 if selfUserId is
        // currently an active admin (i.e., is being demoted).
        boolean selfIsActiveAdmin = users.findById(selfUserId)
                .map(u -> u.isActive()
                        && u.getRoles().stream().anyMatch(r -> "admin".equals(r.getName())))
                .orElse(false);
        long othersAfter = selfIsActiveAdmin ? others - 1 : others;
        if (othersAfter == 0) {
            throw new CannotSelfDemoteLastAdmin();
        }
    }

    /** Thrown by {@link #requireNotLastAdmin}. */
    public static class CannotSelfDemoteLastAdmin extends RuntimeException {
        public CannotSelfDemoteLastAdmin() {
            super("cannot_self_demote_last_admin");
        }
    }

    /** Thrown by the deactivate path (wired in PR4). */
    public static class CannotDeactivateLastAdmin extends RuntimeException {
        public CannotDeactivateLastAdmin() {
            super("cannot_deactivate_last_admin");
        }
    }
}
