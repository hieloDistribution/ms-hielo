package com.sales.sync.auth.admin;

import java.util.UUID;

/**
 * Domain event recorded in {@code admin_audit_log}. All fields are nullable
 * except {@code action}; the audit writer is tolerant of nulls and only
 * persists the columns provided.
 *
 * <p>The {@code requestId} is filled by {@link AdminAuditLogger} if the
 * caller does not supply one. In PR4 it will be sourced from
 * {@code RequestIdFilter} (a per-request attribute).
 *
 * <p>Owner: change {@code admin-console}. This is a primitive used by
 * PR1 (signup bypass closure), PR4 (admin-write endpoints), and any
 * future admin-tooling flow.
 */
public record AuditEvent(
        UUID actorUserId,
        String action,
        UUID targetUserId,
        String targetEmail,
        String beforeJson,
        String afterJson,
        String notes,
        String requestId
) {
    /**
     * Convenience factory for the signup bypass case in PR1 — actor is
     * anonymous (the bypass attempt comes from the network, not a known
     * user), so {@code actorUserId} is null.
     */
    public static AuditEvent anonymous(String action, String targetEmail, String notes) {
        return new AuditEvent(null, action, null, targetEmail, null, null, notes, null);
    }
}
