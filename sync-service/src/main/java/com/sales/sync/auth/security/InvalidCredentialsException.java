package com.sales.sync.auth.security;

/** Thrown when the supplied email/password pair is wrong or the user does not exist. */
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException(String message) {
        super(message);
    }
}
