package com.sales.sync.auth.security;

/** Thrown when a JWT or refresh token is past its {@code exp / expires_at}. */
public class TokenExpiredException extends RuntimeException {
    public TokenExpiredException(String message) {
        super(message);
    }
}
