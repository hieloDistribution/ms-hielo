package com.sales.sync.auth.internal;

/**
 * Thrown when {@code sync-service.UserService.delete} cannot get an answer
 * from {@code order-service}'s reverse-direction endpoint (timeout, 5xx,
 * connection refused, 4xx blueprint-mismatch). Caller treats this as a
 * fail-closed "do not delete" outcome (HTTP 503).
 */
public class OrderServiceUnavailable extends RuntimeException {

    public OrderServiceUnavailable(String message) {
        super(message);
    }

    public OrderServiceUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}