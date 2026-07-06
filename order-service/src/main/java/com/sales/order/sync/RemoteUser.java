package com.sales.order.sync;

import java.time.Instant;
import java.util.UUID;

/**
 * Minimum-exposure view of the upstream {@code User} in {@code sync_db} as
 * returned by {@code GET /internal/auth/users/{id}} (design §3.3).
 *
 * <p>Path: order-service → SyncAuthRestClient → sync-service. The field set
 * is what the cross-DB integrity invariant needs — no password hashes, no
 * refresh tokens.
 */
public record RemoteUser(UUID id, String email, boolean locked, Instant deletedAt) {
}