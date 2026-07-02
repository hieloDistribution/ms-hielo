package com.sales.sync.auth.support;

/**
 * Wire-level error codes returned in the {@code {"error": ...}} body of
 * every 4xx response from the auth endpoints. The HTTP status itself is
 * carried alongside; this enum carries only the body token.
 */
public enum ErrorCode {
    INVALID_CREDENTIALS("invalid_credentials"),
    ACCOUNT_LOCKED("account_locked"),
    TOKEN_EXPIRED("token_expired"),
    TOKEN_REVOKED("token_revoked"),
    INVALID_REQUEST("invalid_request");

    private final String body;

    ErrorCode(String body) {
        this.body = body;
    }

    public String body() {
        return body;
    }
}
