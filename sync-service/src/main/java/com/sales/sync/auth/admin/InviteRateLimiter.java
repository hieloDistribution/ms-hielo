package com.sales.sync.auth.admin;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hand-rolled in-memory token bucket per IP for the
 * {@code /api/v1/auth/admin/invites/redeem} endpoint. Single-tenant,
 * single-instance assumption; we do not need a distributed limiter for
 * this slice.
 *
 * <p>Capacity and window come from {@link InviteTokenProperties}.
 * Default: 5 attempts per IP per hour. On exceed: return false
 * (caller surfaces 429 with Retry-After).
 *
 * <p>Owner: change {@code admin-console} PR4.
 */
@Component
public class InviteRateLimiter {

    private final int capacity;
    private final Duration window;
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public InviteRateLimiter(InviteTokenProperties props) {
        this.capacity = props.getRateLimitCapacity();
        this.window = Duration.ofMinutes(props.getRateLimitWindowMinutes());
    }

    public boolean tryAcquire(String ip) {
        if (ip == null || ip.isBlank()) ip = "unknown";
        return buckets.computeIfAbsent(ip, k -> new Bucket(capacity, window)).tryConsume();
    }

    public Duration timeUntilRefill(String ip) {
        if (ip == null || ip.isBlank()) ip = "unknown";
        Bucket b = buckets.get(ip);
        if (b == null) return Duration.ZERO;
        return b.timeUntilRefill();
    }

    private static final class Bucket {
        private final int capacity;
        private final Duration window;
        private int tokens;
        private Instant refilledAt;

        Bucket(int capacity, Duration window) {
            this.capacity = capacity;
            this.window = window;
            this.tokens = capacity;
            this.refilledAt = Instant.now();
        }

        synchronized boolean tryConsume() {
            refillIfDue();
            if (tokens <= 0) return false;
            tokens--;
            return true;
        }

        synchronized Duration timeUntilRefill() {
            Instant next = refilledAt.plus(window);
            Duration d = Duration.between(Instant.now(), next);
            return d.isNegative() ? Duration.ZERO : d;
        }

        private void refillIfDue() {
            if (Instant.now().isAfter(refilledAt.plus(window))) {
                tokens = capacity;
                refilledAt = Instant.now();
            }
        }
    }
}
