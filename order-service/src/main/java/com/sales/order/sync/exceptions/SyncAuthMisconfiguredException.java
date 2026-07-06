package com.sales.order.sync.exceptions;

/**
 * Thrown when {@code sync-service} refuses our service-to-service token with
 * {@code 401 Unauthorized}. Operational misconfiguration (token mismatch,
 * revoked secret). Translated at the API layer to HTTP {@code 503 Service
 * Unavailable} — we cannot operate without a trustworthy upstream answer.
 *
 * <p>Not subject to the positive cache (D-04): a transient misconfiguration
 * must not pin a verdict.
 */
public class SyncAuthMisconfiguredException extends RuntimeException {

    public SyncAuthMisconfiguredException(String message) {
        super(message);
    }
}