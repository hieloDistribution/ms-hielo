package com.sales.sync.auth.admin;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

/**
 * Repository for {@code admin_audit_log}. Append-only at the DB level
 * (V7 migration revokes UPDATE and DELETE on the runtime role), so
 * the only mutating operation is INSERT. SELECTs for the listing
 * endpoint use a paginated, filterable query.
 *
 * <p>Owner: change {@code admin-console} PR4.
 */
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, UUID> {

    /**
     * Paginated listing with optional filters. The {@code action},
     * {@code actorUserId}, and {@code targetUserId} parameters are
     * nullable; when null, the corresponding WHERE clause is dropped.
     */
    @Query("""
            SELECT a FROM AdminAuditLog a
            WHERE (:action IS NULL OR a.action = :action)
              AND (:actorUserId IS NULL OR a.actorUserId = :actorUserId)
              AND (:targetUserId IS NULL OR a.targetUserId = :targetUserId)
            ORDER BY a.createdAt DESC
            """)
    Page<AdminAuditLog> findFiltered(@Param("action") String action,
                                     @Param("actorUserId") UUID actorUserId,
                                     @Param("targetUserId") UUID targetUserId,
                                     Pageable pageable);
}
