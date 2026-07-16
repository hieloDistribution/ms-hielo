package com.sales.sync.auth.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link InviteTokenCodec}. Verifies round-trip,
 * signature tampering, and expiry.
 *
 * <p>Owner: change {@code admin-console} PR4.
 */
class InviteTokenCodecTest {

    private final InviteTokenCodec codec =
            new InviteTokenCodec("a".repeat(40), new ObjectMapper());

    @Test
    void issue_then_verify_roundtrips_email_role_jti() {
        InviteTokenCodec.IssuedToken issued = codec.issue(
                "new.admin@hielo.local", "admin", Duration.ofHours(24));
        InviteTokenCodec.ParsedToken parsed = codec.verify(issued.token());
        assertThat(parsed.email()).isEqualTo("new.admin@hielo.local");
        assertThat(parsed.role()).isEqualTo("admin");
        assertThat(parsed.jti()).isEqualTo(issued.jti());
        // The codec serializes exp as epoch seconds, so nanos are
        // truncated. Compare at second precision.
        assertThat(parsed.expiresAt().getEpochSecond())
                .isEqualTo(issued.expiresAt().getEpochSecond());
    }

    @Test
    void verify_rejects_tampered_signature() {
        InviteTokenCodec.IssuedToken issued = codec.issue(
                "u@hielo.local", "repartidor", Duration.ofHours(1));
        String tampered = flipMiddleChar(issued.token());
        assertThatThrownBy(() -> codec.verify(tampered))
                .isInstanceOf(InviteTokenCodec.InvalidTokenException.class);
    }

    @Test
    void verify_rejects_malformed_token() {
        assertThatThrownBy(() -> codec.verify("not-a-token"))
                .isInstanceOf(InviteTokenCodec.InvalidTokenException.class);
        assertThatThrownBy(() -> codec.verify("only-one-half"))
                .isInstanceOf(InviteTokenCodec.InvalidTokenException.class);
    }

    @Test
    void verify_rejects_expired_token() throws Exception {
        // Issue with a tiny TTL and then sleep a moment so it expires.
        InviteTokenCodec.IssuedToken issued = codec.issue(
                "u@hielo.local", "admin", Duration.ofMillis(1));
        Thread.sleep(50);
        assertThatThrownBy(() -> codec.verify(issued.token()))
                .isInstanceOf(InviteTokenCodec.InvalidTokenException.class)
                .hasMessage("expired");
    }

    @Test
    void issue_produces_jti_that_is_uuid() {
        InviteTokenCodec.IssuedToken issued = codec.issue(
                "u@hielo.local", "admin", Duration.ofHours(1));
        assertThat(UUID.fromString(issued.jti())).isNotNull();
    }

    private static String flipMiddleChar(String s) {
        int dot = s.indexOf('.');
        String payload = s.substring(0, dot);
        String sig = s.substring(dot + 1);
        int mid = sig.length() / 2;
        char c = sig.charAt(mid);
        char flipped = c == 'A' ? 'B' : 'A';
        return payload + "." + sig.substring(0, mid) + flipped + sig.substring(mid + 1);
    }
}
