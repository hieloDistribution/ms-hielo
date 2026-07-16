package com.sales.sync.auth.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * HMAC-SHA256 signed invite token. Format:
 * <pre>
 *   {base64url(JSON payload)}.{base64url(HMAC-SHA256 signature)}
 * </pre>
 *
 * <p>NOT a JWT — no JSON header, no JWKS. The signature key is derived
 * from the JWT secret + the literal suffix {@code ":admin-invite"} so
 * the two signatures cannot collide. The cleartext token is returned
 * ONCE at issue time; only its bcrypt hash is persisted.
 *
 * <p>Owner: change {@code admin-console} PR4.
 */
@Component
public class InviteTokenCodec {

    private static final String KEY_SUFFIX = ":admin-invite";

    private final byte[] keyBytes;
    private final ObjectMapper objectMapper;

    public InviteTokenCodec(@Value("${jwt.secret:}") String jwtSecret,
                            ObjectMapper objectMapper) {
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalStateException(
                    "jwt.secret must be set (>=32 bytes) for invite token signing");
        }
        this.keyBytes = (jwtSecret + KEY_SUFFIX).getBytes(StandardCharsets.UTF_8);
        this.objectMapper = objectMapper;
    }

    public IssuedToken issue(String email, String role, Duration ttl) {
        String jti = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant exp = now.plus(ttl);
        Map<String, Object> payload = Map.of(
                "email", email,
                "role", role,
                "jti", jti,
                "iat", now.getEpochSecond(),
                "exp", exp.getEpochSecond()
        );
        try {
            String payloadJson = objectMapper.writeValueAsString(payload);
            String payloadB64 = base64UrlEncode(payloadJson.getBytes(StandardCharsets.UTF_8));
            byte[] sig = hmac(payloadB64);
            String sigB64 = base64UrlEncode(sig);
            return new IssuedToken(payloadB64 + "." + sigB64, jti, now, exp);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize invite token", e);
        }
    }

    public ParsedToken verify(String token) {
        int dot = token.indexOf('.');
        if (dot < 0) throw new InvalidTokenException("malformed");
        String payloadB64 = token.substring(0, dot);
        String sigB64 = token.substring(dot + 1);
        byte[] expected = hmac(payloadB64);
        byte[] actual;
        try {
            actual = base64UrlDecode(sigB64);
        } catch (IllegalArgumentException e) {
            throw new InvalidTokenException("bad signature encoding");
        }
        if (!constantTimeEquals(expected, actual)) {
            throw new InvalidTokenException("bad signature");
        }
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    base64UrlDecode(payloadB64), Map.class);
            String email = (String) payload.get("email");
            String role = (String) payload.get("role");
            String jti = (String) payload.get("jti");
            Number expNum = (Number) payload.get("exp");
            if (email == null || role == null || jti == null || expNum == null) {
                throw new InvalidTokenException("missing claims");
            }
            Instant exp = Instant.ofEpochSecond(expNum.longValue());
            if (exp.isBefore(Instant.now())) {
                throw new InvalidTokenException("expired");
            }
            return new ParsedToken(jti, email, role, exp);
        } catch (InvalidTokenException ex) {
            throw ex;
        } catch (Exception e) {
            throw new InvalidTokenException("malformed payload: " + e.getClass().getSimpleName());
        }
    }

    private byte[] hmac(String input) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(keyBytes, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC failure", e);
        }
    }

    private static String base64UrlEncode(byte[] bytes) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static byte[] base64UrlDecode(String s) {
        return java.util.Base64.getUrlDecoder().decode(s);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }

    public record IssuedToken(String token, String jti, Instant issuedAt, Instant expiresAt) {}

    public record ParsedToken(String jti, String email, String role, Instant expiresAt) {}

    public static class InvalidTokenException extends RuntimeException {
        public InvalidTokenException(String reason) { super(reason); }
    }
}
