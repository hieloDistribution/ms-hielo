package com.sales.sync.auth.security;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RefreshTokenCodecTest {

    @Test
    void generate_produces_unique_plaintexts() {
        RefreshTokenCodec codec = new RefreshTokenCodec();
        RefreshTokenCodec.OpaqueRefreshToken a = codec.generate();
        RefreshTokenCodec.OpaqueRefreshToken b = codec.generate();
        assertThat(a.plaintext()).isNotEqualTo(b.plaintext());
        assertThat(a.hash()).isNotEqualTo(b.hash());
    }

    @Test
    void hash_is_deterministic_and_64_hex_chars() {
        RefreshTokenCodec codec = new RefreshTokenCodec();
        String h1 = RefreshTokenCodec.sha256Hex("hello");
        String h2 = RefreshTokenCodec.sha256Hex("hello");
        assertThat(h1).isEqualTo(h2);
        assertThat(h1).hasSize(64).matches("[0-9a-f]+");
    }

    @Test
    void hash_for_plaintext_matches_record_hash() {
        RefreshTokenCodec codec = new RefreshTokenCodec();
        RefreshTokenCodec.OpaqueRefreshToken t = codec.generate();
        assertThat(t.hash()).isEqualTo(RefreshTokenCodec.sha256Hex(t.plaintext()));
    }
}
