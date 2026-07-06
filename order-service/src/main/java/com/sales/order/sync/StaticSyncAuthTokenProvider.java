package com.sales.order.sync;

import org.springframework.stereotype.Component;

/**
 * Static (env-var or config-driven) service-to-service token provider for v1.
 *
 * <p>The token is the same string configured on {@code sync-service} as
 * {@code hielo.sync.auth.internal-service-token} — both services read
 * {@code SYNC_SERVICE_TOKEN} with the same default and rely on operation to
 * set a strong value in production.
 */
@Component
public class StaticSyncAuthTokenProvider implements SyncAuthTokenProvider {

    private final String token;

    public StaticSyncAuthTokenProvider(SyncAuthProperties props) {
        this.token = props.getServiceToken();
    }

    @Override
    public String getServiceToken() {
        return token;
    }
}