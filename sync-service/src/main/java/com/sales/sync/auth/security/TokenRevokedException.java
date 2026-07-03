package com.sales.sync.auth.security;

/** Thrown when a refresh token or JWT has been revoked / not found. */
public class TokenRevokedException extends RuntimeException {
    public TokenRevokedException(String message) {
        super(message);
    }
}
