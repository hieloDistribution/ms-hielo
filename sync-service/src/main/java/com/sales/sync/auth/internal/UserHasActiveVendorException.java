package com.sales.sync.auth.internal;

import java.util.UUID;

/**
 * Domain exception thrown by {@code sync-service.UserService.delete} when
 * the reverse-direction probe says {@code hasActiveVendor=true} for the
 * given {@code userId}. Mapped at the API layer to HTTP {@code 409 Conflict}
 * — the User cannot be deleted while a live Vendor still points at it
 * (design §3.1 reverse-direction step 3).
 */
public class UserHasActiveVendorException extends RuntimeException {

    private final UUID userId;

    public UserHasActiveVendorException(UUID userId) {
        super("User " + userId + " has an active Vendor and cannot be deleted");
        this.userId = userId;
    }

    public UUID getUserId() {
        return userId;
    }
}