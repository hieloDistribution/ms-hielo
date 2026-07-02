package com.sales.sync.auth.security;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;

import java.nio.charset.StandardCharsets;

/**
 * Runs before the {@link org.springframework.context.ApplicationContext} is
 * created and aborts startup if {@code jwt.secret} (typically populated
 * from the {@code JWT_SECRET} environment variable) is absent or shorter
 * than 32 bytes. Registered from {@code META-INF/spring.factories}.
 */
public class JwtSecretEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final int MIN_KEY_BYTES = 32;

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String secret = env.getProperty("jwt.secret");
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "jwt.secret (JWT_SECRET env) is required. Set JWT_SECRET to a value of at least "
                            + MIN_KEY_BYTES + " bytes.");
        }
        int len = secret.getBytes(StandardCharsets.UTF_8).length;
        if (len < MIN_KEY_BYTES) {
            throw new IllegalStateException(
                    "jwt.secret must be >= " + MIN_KEY_BYTES + " bytes (got " + len + "). "
                            + "Set JWT_SECRET to a longer value.");
        }
    }
}
