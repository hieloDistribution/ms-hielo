package com.sales.order.sync;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration bound from {@code hielo.sync.auth.*} in {@code application.yml}.
 *
 * <pre>
 * hielo:
 *   sync:
 *     auth:
 *       base-url: http://localhost:8081       # sync-service (props override yml → 8081)
 *       connect-timeout: 1s                    # D-14
 *       read-timeout: 2s                       # D-14 — combined budget 3s
 *       service-token: ${SYNC_SERVICE_TOKEN:dev-shared-secret-change-me}  # R-3
 *       enabled: true                          # set false in tests to stub
 * </pre>
 */
@ConfigurationProperties(prefix = "hielo.sync.auth")
public class SyncAuthProperties {

    private String baseUrl = "http://localhost:8081";
    private Duration connectTimeout = Duration.ofSeconds(1);
    private Duration readTimeout = Duration.ofSeconds(2);
    private String serviceToken = "dev-shared-secret-change-me";
    /** When false, the bean wiring still loads but consumes {@link #serviceToken} = "" */
    private boolean enabled = true;

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }

    public String getServiceToken() { return serviceToken; }
    public void setServiceToken(String serviceToken) { this.serviceToken = serviceToken; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
}