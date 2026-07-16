package com.sales.sync.auth.admin;

import java.time.Instant;
import java.util.UUID;

/**
 * Response for {@code POST /api/v1/admin/invites} (issue) and
 * {@code POST /api/v1/auth/admin/invites/redeem} (redeem).
 * The {@code token} field is the cleartext — returned exactly once at
 * issue time.
 *
 * <p>Owner: change {@code admin-console} PR4.
 */
public record AdminInviteResponse(
        UUID inviteId,
        String token,
        Instant expiresAt
) {}
