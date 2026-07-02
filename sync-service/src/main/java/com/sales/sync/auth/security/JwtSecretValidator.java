package com.sales.sync.auth.security;

import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Backstop validator that runs once the bean is constructed. The primary
 * fail-fast is in {@link JwtSecretEnvironmentPostProcessor} (which runs
 * earlier in the lifecycle); this bean exists for code paths that
 * construct the {@link JwtSecretValidator} directly outside of
 * {@code SpringApplication.run()}, e.g. unit tests.
 */
@Component
public class JwtSecretValidator {

    private static final int MIN_KEY_BYTES = 32;

    private final JwtProperties props;

    public JwtSecretValidator(JwtProperties props) {
        this.props = props;
    }

    @PostConstruct
    public void validateOnStartup() {
        if (props.secret() == null || props.secret().isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret (JWT_SECRET env) is required.");
        }
        int len = props.secret().getBytes(StandardCharsets.UTF_8).length;
        if (len < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret must be >= " + MIN_KEY_BYTES + " bytes (got " + len + ").");
        }
    }
}
