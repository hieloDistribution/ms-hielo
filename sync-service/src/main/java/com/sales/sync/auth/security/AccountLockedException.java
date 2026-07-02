package com.sales.sync.auth.security;

/** Thrown when the account being authenticated is marked locked. */
public class AccountLockedException extends RuntimeException {
    public AccountLockedException(String message) {
        super(message);
    }
}
