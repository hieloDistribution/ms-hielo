package com.sales.sync.auth.service;

import com.sales.sync.auth.internal.OrderServiceUnavailable;
import com.sales.sync.auth.internal.OrderInternalVendorsClient;
import com.sales.sync.auth.internal.UserHasActiveVendorException;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Soft-delete entry-point for a {@link User}.
 *
 * <p>Encodes the reverse-direction cross-DB integrity invariant from design
 * §3.1, D-05: BEFORE locking the User row, consult
 * {@link OrderInternalVendorsClient#hasActiveVendorForUser(UUID)}. If
 * {@code true}, refuse the delete and surface
 * {@link UserHasActiveVendorException} (HTTP 409). If {@code false}, set
 * {@code user.locked = true} (the closest soft-delete proxy at the current
 * maturity — User does NOT yet have a {@code deleted_at} column; the lock
 * makes login impossible and is internally treated as deletion).
 *
 * <p>If order-service itself is unreachable, the client throws
 * {@link OrderServiceUnavailable}; the caller (typically a controller)
 * surfaces this as HTTP 503. Soft-delete is BLOCKED in that case — fail
 * closed.
 *
 * <p><strong>Deviation from design §3.1:</strong> the design assumes a real
 * soft-delete with {@code deleted_at}; the User entity has no such field
 * today, and a follow-up SDD will migrate the User schema. Until then, lock
 * = "soft-deleted".
 */
@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepository userRepository;
    private final OrderInternalVendorsClient orderInternalVendorsClient;

    public UserService(UserRepository userRepository,
                       OrderInternalVendorsClient orderInternalVendorsClient) {
        this.userRepository = userRepository;
        this.orderInternalVendorsClient = orderInternalVendorsClient;
    }

    /**
     * Soft-delete the user identified by {@code userId}. Idempotent — locking
     * an already-locked user is a no-op.
     *
     * @throws UserHasActiveVendorException if order-service reports a live
     *         Vendor still references {@code userId}
     * @throws OrderServiceUnavailable if order-service is unreachable;
     *         caller treats this as fail-closed (do NOT soft-delete)
     * @throws IllegalArgumentException if no User with {@code userId} exists
     */
    @Transactional
    public void delete(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // If the user is already locked, treat as idempotent no-op — skip the
        // upstream probe entirely (no live Vendor would have a reference to a
        // locked user post-integrity-fix).
        if (user.isLocked()) {
            log.info("delete called on already-locked user {} — treated as idempotent no-op", userId);
            return;
        }

        boolean hasActiveVendor = orderInternalVendorsClient.hasActiveVendorForUser(userId);
        if (hasActiveVendor) {
            throw new UserHasActiveVendorException(userId);
        }

        user.setLocked(true);
        userRepository.save(user);
        log.info("user {} soft-deleted (locked = true)", userId);
    }
}