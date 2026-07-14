package com.sales.order.sync;

/**
 * Resolves the service-to-service bearer token sent on every internal call
 * to {@code sync-service}. Production impl: {@link StaticSyncAuthTokenProvider}
 * backed by the env-var-driven shared secret {@code SYNC_SERVICE_TOKEN}
 * (R-3, no default — a missing env var causes inter-service auth to fail
 * closed at runtime).
 *
 * <p>Future improvement (deferred): mTLS or a JWT issued by a shared KID —
 * owned by a follow-up {@code auth-internal-api-mtls} SDD.
 */
public interface SyncAuthTokenProvider {
    String getServiceToken();
}