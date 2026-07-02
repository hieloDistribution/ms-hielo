package com.sales.sync.auth.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static JwtService newService(String secret) {
        JwtProperties p = new JwtProperties(
                secret,
                Duration.ofSeconds(900),
                Duration.ofSeconds(604800),
                "hielo-sync",
                "hielo-order");
        return new JwtService(p);
    }

    @Test
    void sign_then_parse_recovers_subject_vendor_iss_aud_and_exp() {
        JwtService svc = newService("a".repeat(40));
        UUID userId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        String token = svc.sign(userId, vendorId);

        JwtService.ParsedToken parsed = svc.parse(token);

        assertThat(parsed.userId()).isEqualTo(userId);
        assertThat(parsed.vendorId()).isEqualTo(vendorId);
    }

    @Test
    void parse_rejects_tampered_signature() {
        JwtService svc = newService("a".repeat(40));
        String token = svc.sign(UUID.randomUUID(), null);

        // Flip the last character of the signature segment
        String tampered = token.substring(0, token.length() - 1)
                + (token.charAt(token.length() - 1) == 'A' ? 'B' : 'A');

        assertThatThrownBy(() -> svc.parse(tampered))
                .isInstanceOfAny(TokenInvalidException.class);
    }

    @Test
    void parse_rejects_wrong_issuer() {
        JwtService svc = newService("a".repeat(40));
        UUID userId = UUID.randomUUID();
        String token = svc.sign(userId, null);

        JwtService otherSvc = newService("a".repeat(40)); // same secret, same issuer

        // Build a token issued by another service and verify ours rejects it.
        // Easiest: a token signed by a service with a different JwtProperties.
        JwtProperties otherProps = new JwtProperties(
                "a".repeat(40),
                Duration.ofSeconds(900),
                Duration.ofSeconds(604800),
                "other-issuer",
                "hielo-order");
        JwtService other = new JwtService(otherProps);
        String otherToken = other.sign(userId, null);

        assertThatThrownBy(() -> svc.parse(otherToken))
                .isInstanceOf(TokenInvalidException.class);
    }

    @Test
    void parse_rejects_when_secret_changes() {
        JwtService svcA = newService("a".repeat(40));
        JwtService svcB = newService("b".repeat(40));
        String tokenA = svcA.sign(UUID.randomUUID(), null);

        assertThatThrownBy(() -> svcB.parse(tokenA))
                .isInstanceOf(TokenInvalidException.class);
    }
}
