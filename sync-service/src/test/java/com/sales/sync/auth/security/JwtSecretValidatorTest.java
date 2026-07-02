package com.sales.sync.auth.security;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit test for the {@code @PostConstruct} fail-fast in
 * {@link JwtSecretValidator}. The Spring environment post-processor is
 * covered by {@code ApplicationSecurityStartupIT} (boot test).
 */
class JwtSecretValidatorTest {

    private static JwtProperties props(String secret) {
        return new JwtProperties(
                secret,
                Duration.ofSeconds(900),
                Duration.ofSeconds(604800),
                "hielo-sync",
                "hielo-order");
    }

    @Test
    void empty_secret_is_rejected() {
        JwtSecretValidator v = new JwtSecretValidator(props(""));
        assertThatThrownBy(v::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("jwt.secret");
    }

    @Test
    void short_secret_is_rejected() {
        JwtSecretValidator v = new JwtSecretValidator(props("short"));
        assertThatThrownBy(v::validateOnStartup)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    void long_enough_secret_passes() {
        JwtSecretValidator v = new JwtSecretValidator(props("a".repeat(40)));
        assertThatCode(v::validateOnStartup).doesNotThrowAnyException();
    }
}
