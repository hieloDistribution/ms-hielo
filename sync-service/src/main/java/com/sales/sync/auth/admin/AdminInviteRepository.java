package com.sales.sync.auth.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Repository for {@code admin_invites}.
 *
 * <p>Owner: change {@code admin-console} PR4. The application code
 * only inserts (issue) and updates {@code used_at} (redeem). No
 * UPDATE or DELETE on other columns; the table is append-only.
 */
public interface AdminInviteRepository extends JpaRepository<AdminInvite, UUID> {
}
