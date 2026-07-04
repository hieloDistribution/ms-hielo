package com.sales.order.sync.exceptions;

/**
 * Thrown when {@code SyncAuthClient.getUserById} cannot confirm the user exists
 * in {@code sync_db} (sync-service returned {@code 404}, returned a deleted/
 * locked user, or the cross-DB handshake otherwise did not produce a live row).
 *
 * <p>Translated at the API layer to HTTP {@code 422 Unprocessable Entity} —
 * the request was well-formed but references a {@code user_id} that does not
 * point at a usable upstream User.
 */
public class UnknownUserException extends RuntimeException {

    public UnknownUserException(String message) {
        super(message);
    }
}