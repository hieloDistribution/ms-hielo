package com.sales.sync.auth.admin;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for {@code admin_invites}.
 *
 * <p>Owner: change {@code admin-console} PR4 + follow-up. The application
 * code only inserts (issue), updates {@code used_at} (redeem) or
 * {@code revoked_at} (cancel). No UPDATE or DELETE on other columns; the
 * table is append-only.
 */
public interface AdminInviteRepository extends JpaRepository<AdminInvite, UUID> {

    /**
     * Pending invites that haven't been redeemed AND haven't been revoked.
     * Expired ones (regardless of revoke state) are filtered out by the
     * query filter.
     */
    @Query("SELECT a FROM AdminInvite a "
            + "WHERE a.usedAt IS NULL AND a.revokedAt IS NULL "
            + "ORDER BY a.createdAt DESC")
    List<AdminInvite> findPending();

    /** All invites (any state) ordered by created_at desc, for admin review. */
    @Query("SELECT a FROM AdminInvite a ORDER BY a.createdAt DESC")
    List<AdminInvite> findAllOrderedByCreated();

    /**
     * Lookup a single pending invite by id. The redeem/cancel paths MUST
     * use this method (not {@code findById}) so that used/revoked invites
     * 404 cleanly instead of being re-cancelled by mistake.
     */
    @Query("SELECT a FROM AdminInvite a "
            + "WHERE a.id = :id AND a.usedAt IS NULL AND a.revokedAt IS NULL")
    java.util.Optional<AdminInvite> findPendingById(UUID id);

    /** Find an invite by id regardless of state. Used for the GET listing. */
    java.util.Optional<AdminInvite> findById(UUID id);

    /** For the redeem path: any invite whose id matches (used OR pending). */
    @Query("SELECT a FROM AdminInvite a WHERE a.id = :id")
    java.util.Optional<AdminInvite> findByIdAlways(UUID id);
}
