package com.sales.sync.auth.internal;

import java.util.UUID;

/**
 * Reverse-direction cross-DB integrity probe (design §3.1).
 *
 * <p>Called by {@code sync-service.UserService.delete(userId)} BEFORE the
 * User is locked/removed, to verify no live Vendor still references
 * {@code userId}. {@code has=true} ⇒ reject the delete and surface
 * {@link UserHasActiveVendorException}. {@code has=false} ⇒ proceed.
 *
 * <p>Implementation: {@link OrderInternalVendorsClientImpl} takes a real
 * HTTP round-trip; tests use a {@link org.springframework.boot.test.mock
 * .mockito.MockBean} stub.
 */
public interface OrderInternalVendorsClient {

    /**
     * @return true iff at least one non-soft-deleted Vendor with
     *         {@code active = true} references {@code userId} in order_db.
     * @throws OrderServiceUnavailable when order-service is unreachable or
     *         returns a non-success status (caller treats this as fail-closed)
     */
    boolean hasActiveVendorForUser(UUID userId);
}