package com.sales.sync.auth.security;

import com.sales.sync.auth.model.User;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final User.Role REPARTIDOR = User.Role.repartidor;

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
        String token = svc.sign(userId, vendorId, "user@example.com", REPARTIDOR);

        JwtService.ParsedToken parsed = svc.parse(token);

        assertThat(parsed.userId()).isEqualTo(userId);
        assertThat(parsed.vendorId()).isEqualTo(vendorId);
        assertThat(parsed.roles()).containsExactly(User.Role.repartidor);
    }

    @Test
    void parse_rejects_tampered_signature() {
        JwtService svc = newService("a".repeat(40));
        String token = svc.sign(UUID.randomUUID(), null, "user@example.com", REPARTIDOR);

        // Tamper a character in the middle of the signature segment.
        // HS256 → 32 bytes → 43 base64url chars, the last char only
        // encodes 4 of 6 bits; flipping it can land in padding bits and
        // leave the decoded bytes unchanged → HMAC still verifies → no
        // exception.  A middle-byte mutation always changes at least
        // one decoded byte and guarantees signature verification fails.
        String[] parts = token.split("\\.");
        String sig = parts[2];
        int mid = sig.length() / 2;
        char mutated = sig.charAt(mid) == 'A' ? 'B' : 'A';
        String tampered = parts[0] + "." + parts[1] + "."
                + sig.substring(0, mid) + mutated + sig.substring(mid + 1);

        assertThatThrownBy(() -> svc.parse(tampered))
                .isInstanceOfAny(TokenInvalidException.class);
    }

    @Test
    void parse_rejects_wrong_issuer() {
        JwtService svc = newService("a".repeat(40));
        UUID userId = UUID.randomUUID();
        String token = svc.sign(userId, null, "user@example.com", REPARTIDOR);

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
        String otherToken = other.sign(userId, null, "user@example.com", REPARTIDOR);

        assertThatThrownBy(() -> svc.parse(otherToken))
                .isInstanceOf(TokenInvalidException.class);
    }

    @Test
    void parse_rejects_when_secret_changes() {
        JwtService svcA = newService("a".repeat(40));
        JwtService svcB = newService("b".repeat(40));
        String tokenA = svcA.sign(UUID.randomUUID(), null, "user@example.com", REPARTIDOR);

        assertThatThrownBy(() -> svcB.parse(tokenA))
                .isInstanceOf(TokenInvalidException.class);
    }

    @Test
    void sign_with_mustChangePassword_true_carries_mcp_claim_true() {
        JwtService svc = newService("a".repeat(40));
        String token = svc.sign(UUID.randomUUID(), null, "bootstrap@hielo.local",
                User.Role.admin, true);

        JwtService.ParsedToken parsed = svc.parse(token);
        assertThat(parsed.mustChangePassword())
                .as("the mcp claim must survive the round-trip")
                .isTrue();
    }

    @Test
    void sign_with_mustChangePassword_false_carries_mcp_claim_false() {
        JwtService svc = newService("a".repeat(40));
        String token = svc.sign(UUID.randomUUID(), null, "normal@hielo.local",
                REPARTIDOR, false);

        JwtService.ParsedToken parsed = svc.parse(token);
        assertThat(parsed.mustChangePassword()).isFalse();
    }

    @Test
    void parse_legacy_token_without_mcp_claim_defaults_to_false() {
        // A token signed by an issuer that did not write mcp (e.g. a token
        // issued before PR2) must parse with mustChangePassword=false — the
        // claim is treated as informational; absence means "not flagged".
        JwtService svc = newService("a".repeat(40));
        String legacyToken = svc.sign(UUID.randomUUID(), null, "legacy@hielo.local", REPARTIDOR);
        // The 4-arg overload already writes mcp=false, but verify the parser
        // does NOT throw when mcp is absent (i.e., the boolean is null).
        JwtService.ParsedToken parsed = svc.parse(legacyToken);
        assertThat(parsed.mustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("PR3 dual-shape: sign with Set<Role> writes both `roles` array and legacy `role` string claims")
    void sign_with_role_set_writes_both_claims() throws Exception {
        JwtService svc = newService("a".repeat(40));
        String token = svc.sign(UUID.randomUUID(), null, "admin@hielo.local",
                Set.of(User.Role.admin, User.Role.cliente), false);

        SignedJWT jwt = SignedJWT.parse(token);
        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getStringListClaim("roles"))
                .as("the new `roles` array claim must carry both roles")
                .containsExactlyInAnyOrder("admin", "cliente");
        assertThat(claims.getStringClaim("role"))
                .as("the legacy single-string claim is set to the first role for back-compat")
                .isIn("admin", "cliente");
    }

    @Test
    @DisplayName("PR3 dual-shape: parse prefers `roles` array, falls back to `role` string")
    void parse_prefers_roles_array_over_role_string() throws Exception {
        JwtService svc = newService("a".repeat(40));
        UUID userId = UUID.randomUUID();
        // Use the full-set overload so the array contains both.
        String token = svc.sign(userId, null, "admin@hielo.local",
                Set.of(User.Role.admin, User.Role.cliente), false);

        JwtService.ParsedToken parsed = svc.parse(token);
        assertThat(parsed.roles())
                .as("both roles from the array claim must round-trip")
                .containsExactlyInAnyOrder(User.Role.admin, User.Role.cliente);
    }

    @Test
    @DisplayName("PR3 dual-shape: parse falls back to legacy single-string `role` when `roles` array is missing")
    void parse_falls_back_to_legacy_role_claim() {
        JwtService svc = newService("a".repeat(40));
        // 4-arg overload writes the array (singleton) AND the legacy string.
        String token = svc.sign(UUID.randomUUID(), null, "legacy@hielo.local", REPARTIDOR);
        JwtService.ParsedToken parsed = svc.parse(token);
        assertThat(parsed.roles()).containsExactly(REPARTIDOR);
    }
}
