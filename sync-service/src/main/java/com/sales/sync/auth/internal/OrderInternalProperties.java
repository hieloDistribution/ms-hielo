package com.sales.sync.auth.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration bound from {@code hielo.order.internal.*} in sync-service's
 * {@code application.yml}. Drives the reverse-direction RestClient bean used
 * by {@link OrderInternalVendorsClientImpl} to call
 * {@code GET /internal/vendors/by-user/{userId}} on order-service.
 *
 * <pre>
 * hielo:
 *   order:
 *     internal:
 *       base-url: http://localhost:8082          # order-service actual port
 *       connect-timeout: 1s
 *       read-timeout: 2s
 *       service-token: ${SYNC_SERVICE_TOKEN:dev-shared-secret-change-me}  # R-3
 * </pre>
 */
@ConfigurationProperties(prefix = "hielo.order.internal")
public class OrderInternalProperties {

    private String baseUrl = "http://localhost:8082";
    private Duration connectTimeout = Duration.ofSeconds(1);
    private Duration readTimeout = Duration.ofSeconds(2);
    private String serviceToken = "dev-shared-secret-change-me";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration connectTimeout) { this.connectTimeout = connectTimeout; }

    public Duration getReadTimeout() { return readTimeout; }
    public void setReadTimeout(Duration readTimeout) { this.readTimeout = readTimeout; }

    public String getServiceToken() { return serviceToken; }
    public void setServiceToken(String serviceToken) { this.serviceToken = serviceToken; }
}