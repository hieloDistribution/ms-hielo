package com.sales.sync.auth.admin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link InviteRateLimiter}. Validates the per-IP bucket
 * logic and the time-until-refill computation.
 *
 * <p>Owner: change {@code admin-console} PR4.
 */
class InviteRateLimiterTest {

    @Test
    void allows_5_attempts_then_blocks() {
        InviteTokenProperties props = new InviteTokenProperties();
        props.setRateLimitCapacity(5);
        props.setRateLimitWindowMinutes(60);
        InviteRateLimiter rl = new InviteRateLimiter(props);

        for (int i = 0; i < 5; i++) {
            assertThat(rl.tryAcquire("1.2.3.4"))
                    .as("attempt %d of 5 should be allowed", i + 1)
                    .isTrue();
        }
        assertThat(rl.tryAcquire("1.2.3.4"))
                .as("6th attempt must be blocked")
                .isFalse();
    }

    @Test
    void blocks_per_independent_ip() {
        InviteTokenProperties props = new InviteTokenProperties();
        props.setRateLimitCapacity(2);
        props.setRateLimitWindowMinutes(60);
        InviteRateLimiter rl = new InviteRateLimiter(props);

        assertThat(rl.tryAcquire("1.1.1.1")).isTrue();
        assertThat(rl.tryAcquire("1.1.1.1")).isTrue();
        assertThat(rl.tryAcquire("1.1.1.1")).isFalse();
        // A different IP has its own bucket.
        assertThat(rl.tryAcquire("2.2.2.2")).isTrue();
        assertThat(rl.tryAcquire("2.2.2.2")).isTrue();
        assertThat(rl.tryAcquire("2.2.2.2")).isFalse();
    }

    @Test
    void unknown_ip_collapses_to_default_bucket() {
        InviteTokenProperties props = new InviteTokenProperties();
        props.setRateLimitCapacity(2);
        props.setRateLimitWindowMinutes(60);
        InviteRateLimiter rl = new InviteRateLimiter(props);

        assertThat(rl.tryAcquire(null)).isTrue();
        assertThat(rl.tryAcquire("")).isTrue();
        assertThat(rl.tryAcquire(null)).isFalse();
    }

    @Test
    void time_until_refill_is_zero_for_never_seen_ip() {
        InviteTokenProperties props = new InviteTokenProperties();
        props.setRateLimitCapacity(5);
        props.setRateLimitWindowMinutes(60);
        InviteRateLimiter rl = new InviteRateLimiter(props);

        assertThat(rl.timeUntilRefill("never-seen")).isEqualTo(java.time.Duration.ZERO);
    }
}
