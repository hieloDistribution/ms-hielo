package com.sales.order.auth.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "a".repeat(40);

    private static JwtService newService(String secret) {
        JwtProperties p = new JwtProperties(
                secret,
                Duration.ofSeconds(900),
                Duration.ofSeconds(604800),
                "hielo-sync",
                "hielo-order");
        return new JwtService(p);
    }

    private static String mintToken(String secret, String iss, String aud,
                                    long expEpochSeconds, UUID userId, UUID vendorId) {
        try {
            JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                    .subject(userId.toString())
                    .issuer(iss)
                    .audience(aud)
                    .issueTime(new Date(System.currentTimeMillis() - 1000))
                    .expirationTime(new Date(expEpochSeconds))
                    .jwtID(UUID.randomUUID().toString());
            if (vendorId != null) b.claim("vendor_id", vendorId.toString());
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                    b.build());
            jwt.sign(new MACSigner(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void parse_recovers_subject_vendor_iss_aud_when_token_was_signed_with_same_secret() {
        UUID userId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        long exp = System.currentTimeMillis() + 60_000;
        String token = mintToken(SECRET, "hielo-sync", "hielo-order", exp, userId, vendorId);

        JwtService.ParsedToken parsed = newService(SECRET).parse(token);

        assertThat(parsed.userId()).isEqualTo(userId);
        assertThat(parsed.vendorId()).isEqualTo(vendorId);
    }

    @Test
    void parse_throws_token_invalid_when_signature_uses_different_secret() {
        UUID userId = UUID.randomUUID();
        long exp = System.currentTimeMillis() + 60_000;
        String token = mintToken("b".repeat(40), "hielo-sync", "hielo-order", exp, userId, null);

        assertThatThrownBy(() -> newService(SECRET).parse(token))
                .isInstanceOf(TokenInvalidException.class);
    }

    @Test
    void parse_throws_token_invalid_when_issuer_is_wrong() {
        UUID userId = UUID.randomUUID();
        long exp = System.currentTimeMillis() + 60_000;
        String token = mintToken(SECRET, "someone-else", "hielo-order", exp, userId, null);

        assertThatThrownBy(() -> newService(SECRET).parse(token))
                .isInstanceOf(TokenInvalidException.class);
    }

    @Test
    void parse_throws_token_expired_when_exp_is_in_the_past() {
        UUID userId = UUID.randomUUID();
        long exp = System.currentTimeMillis() - 60_000;
        String token = mintToken(SECRET, "hielo-sync", "hielo-order", exp, userId, null);

        assertThatThrownBy(() -> newService(SECRET).parse(token))
                .isInstanceOf(TokenExpiredException.class);
    }
}
