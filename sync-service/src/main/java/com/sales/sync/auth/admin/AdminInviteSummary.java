package com.sales.sync.auth.admin;

import java.time.Instant;
import java.util.UUID;

/**
 * DTO for {@code GET /api/v1/admin/invites}. The cleartext token is
 * intentionally NOT included — it is one-time at issue time and never
 * persisted, so a listing endpoint has no business returning it.
 *
 * <p>Status is a precomputed string so the front-end doesn't have to
 * reason about the state machine. All timestamps are pre-formatted as
 * ISO-8601 strings to avoid Jackson serialization issues with record
 * components that are nullable in Java but always absent in the JSON
 * for invites that haven't been redeemed/revoked yet.
 *
 * <p>Owner: change {@code admin-console} PR4 follow-up.
 */
public record AdminInviteSummary(
        UUID inviteId,
        String email,
        String role,
        String status,            // "pending" | "used" | "revoked" | "expired"
        String createdAt,
        String expiresAt,
        String usedAt,            // empty string when null
        String revokedAt          // empty string when null
) {
    public static AdminInviteSummary from(AdminInvite a) {
        String status;
        if (a.getRevokedAt() != null) {
            status = "revoked";
        } else if (a.getUsedAt() != null) {
            status = "used";
        } else if (!a.getExpiresAt().isAfter(Instant.now())) {
            status = "expired";
        } else {
            status = "pending";
        }
        return new AdminInviteSummary(
                a.getId(),
                a.getEmail(),
                a.getRole(),
                status,
                a.getCreatedAt() == null ? null : a.getCreatedAt().toString(),
                a.getExpiresAt() == null ? null : a.getExpiresAt().toString(),
                a.getUsedAt() == null ? null : a.getUsedAt().toString(),
                a.getRevokedAt() == null ? null : a.getRevokedAt().toString()
        );
    }
}
