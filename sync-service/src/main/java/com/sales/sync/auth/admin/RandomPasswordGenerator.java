package com.sales.sync.auth.admin;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates cryptographically-random passwords for the bootstrap admin.
 *
 * <p>Output is base64url-encoded random bytes. Length is in bytes (so
 * {@code generate(16)} yields a 22-character base64url string with
 * 128 bits of entropy, comfortably above the canonical auth spec's
 * 12-character minimum).
 *
 * <p>Owner: change {@code admin-console} PR2.
 */
@Component
public class RandomPasswordGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    public String generate(int byteLength) {
        if (byteLength < 12) {
            throw new IllegalArgumentException(
                    "bootstrap password must be at least 12 random bytes (96 bits); got " + byteLength);
        }
        byte[] buf = new byte[byteLength];
        RANDOM.nextBytes(buf);
        // base64url without padding — friendly for operator capture.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
    }
}
