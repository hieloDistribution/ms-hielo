package com.sales.sync.auth.dto;

public record AuthResponse(
        String access_token,
        String refresh_token,
        long   expires_in
) {
}
