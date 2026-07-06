package com.sales.sync.auth.security;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Generates opaque refresh tokens (UUID v4) and computes their SHA-256
 * hex hash for storage. The plaintext is returned to the client exactly
 * once; only the hash is persisted.                  
 */
@Component
public class RefreshTokenCodec {

    public OpaqueRefreshToken generate() {
        String plaintext = UUID.randomUUID().toString();
        String hash = sha256Hex(plaintext);
        return new OpaqueRefreshToken(plaintext, hash);
    }

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record OpaqueRefreshToken(String plaintext, String hash) {}
}
