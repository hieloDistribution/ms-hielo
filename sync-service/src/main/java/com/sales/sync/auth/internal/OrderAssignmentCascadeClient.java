package com.sales.sync.auth.internal;

import java.util.UUID;

/**
 * Reverse-direction cross-DB operation: cascade-close a preventista's active
 * client assignments when their user is deactivated (sync-service
 * {@code AdminService.deactivate}).
 *
 * <p>Implementation: HTTP POST to
 * {@code <order-service>/internal/users/{userId}/cascade-close-vendor-assignments}
 * with the static service bearer token. Returns the number of assignments
 * closed (zero when the user was never a preventista).
 *
 * <p>On transport or 5xx failure the call surfaces
 * {@link OrderServiceUnavailable}, which the caller treats as fail-soft (the
 * user has already been deactivated; logging + audit is enough).
 */
public interface OrderAssignmentCascadeClient {

    /**
     * Cascade-close all active client assignments for the Vendor associated
     * with {@code userId}.
     *
     * @return number of assignments closed.
     */
    int cascadeCloseForUser(UUID userId);
}