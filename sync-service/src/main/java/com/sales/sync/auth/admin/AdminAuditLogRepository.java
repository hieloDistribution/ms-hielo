package com.sales.sync.auth.admin;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Write-only repository for {@code admin_audit_log}.
 *
 * <p>The V7 migration (PR4) revokes UPDATE and DELETE on this table from
 * the application role, so the {@code save}-only interface matches the
 * contract. Listing/filtering for the {@code GET /api/v1/admin/audit-log}
 * endpoint is implemented in PR4 via raw JdbcTemplate (bypassing
 * {@link JpaRepository#findAll}) to keep a single INSERT path through
 * the audit infrastructure even when UPDATE/DELETE are revoked.
 */
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {
}
