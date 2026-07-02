package com.sales.sync.auth.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Bound via {@code @ConfigurationProperties(prefix = "jwt")} so the
 * application.yml values land here.
 */
@ConfigurationProperties(prefix = "jwt")
public record JwtProperties(
        String secret,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String issuer,
        String audience
) {
}
