package com.sales.sync.auth.admin;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for an audit log entry in the listing response. Mirrors the
 * entity plus a denormalized {@code actorEmail} (resolved via
 * user repository at controller level).
 *
 * <p>Owner: change {@code admin-console} PR4.
 */
public record AdminAuditLogEntry(
        UUID id,
        UUID actorUserId,
        String actorEmail,
        String action,
        UUID targetUserId,
        String targetEmail,
        String beforeJson,
        String afterJson,
        String requestId,
        String notes,
        Instant createdAt
) {}
