package com.sales.sync.auth.security;

/** Thrown when a JWT signature does not verify or its issuer/audience is wrong. */
public class TokenInvalidException extends RuntimeException {
    public TokenInvalidException(String message) {
        super(message);
    }
}
