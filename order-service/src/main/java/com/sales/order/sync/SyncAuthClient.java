package com.sales.order.sync;

import java.util.Optional;
import java.util.UUID;

/**
 * Client-side contract used by {@code VendorRepositoryImpl.save()} to confirm
 * a {@code user_id} points at a live {@code sync_db} user before persisting
 * the Vendor.
 *
 * <p>An empty {@link Optional} from {@link #getUserById(UUID)} signals an
 * "unknown user" — the caller throws {@link com.sales.order.sync.exceptions
 * .UnknownUserException}. Other failures surface via the relevant
 * exception types (misconfigured token, upstream unavailable) per design §3.1
 * step 6-8.
 *
 * <p>Implementations: production {@link SyncAuthRestClientImpl} (real
 * HTTP); tests use a {@link org.springframework.boot.test.mock.mockito
 * .MockBean} or a thin in-memory stub.
 */
public interface SyncAuthClient {

    /**
     * Look up a user by id. Throws on operational failure (misconfigured
     * token, upstream unavailable); returns {@link Optional#empty()} on a
     * 404 response OR when the upstream returns a locked/deleted user.
     */
    Optional<RemoteUser> getUserById(UUID userId);
}