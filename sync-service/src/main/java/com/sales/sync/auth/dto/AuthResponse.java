package com.sales.sync.auth.dto;

/**
 * Login / refresh response.
 *
 * <p>{@code must_change_password} is the same flag as the {@code mcp}
 * claim in the JWT, surfaced to the client so the UI can route to the
 * password-change screen BEFORE any other screen. The canonical auth
 * spec's B2 requirement covers this.
 *
 * <p>Owner: change {@code admin-console} PR2.
 */
public record AuthResponse(
        String access_token,
        String refresh_token,
        long   expires_in,
        boolean must_change_password
) {
}
