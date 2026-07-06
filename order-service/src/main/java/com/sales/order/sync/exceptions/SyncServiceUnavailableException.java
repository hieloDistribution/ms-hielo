package com.sales.order.sync.exceptions;

/**
 * Thrown when {@code sync-service} is unreachable (connection refused, read
 * timeout, 5xx response). Translated at the API layer to HTTP
 * {@code 503 Service Unavailable} with {@code Retry-After: 5} (D-01 step 8).
 *
 * <p>Not subject to the positive cache (D-04).
 */
public class SyncServiceUnavailableException extends RuntimeException {

    public SyncServiceUnavailableException(String message) {
        super(message);
    }

    public SyncServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}