package com.sales.sync.auth.admin;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Configuration knobs for the admin-invite flow. All values have safe
 * defaults; the only operator-relevant knob is {@link #ttlHours}
 * (overridable via env {@code APP_INVITE_TTL_HOURS}).
 *
 * <p>Owner: change {@code admin-console} PR4.
 */
@ConfigurationProperties(prefix = "app.invite")
public class InviteTokenProperties {

    private int ttlHours = 24;
    private int rateLimitCapacity = 5;
    private int rateLimitWindowMinutes = 60;

    public int getTtlHours() { return ttlHours; }
    public void setTtlHours(int ttlHours) { this.ttlHours = ttlHours; }

    public int getRateLimitCapacity() { return rateLimitCapacity; }
    public void setRateLimitCapacity(int rateLimitCapacity) { this.rateLimitCapacity = rateLimitCapacity; }

    public int getRateLimitWindowMinutes() { return rateLimitWindowMinutes; }
    public void setRateLimitWindowMinutes(int rateLimitWindowMinutes) {
        this.rateLimitWindowMinutes = rateLimitWindowMinutes;
    }
}
