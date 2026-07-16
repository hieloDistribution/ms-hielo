package com.sales.sync.auth.admin;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@code app.admin.*} and {@code app.invite.*}
 * configuration classes for the admin-console change.
 *
 * <p>Owner: change {@code admin-console} (PR2 + PR4).
 */
@Configuration
@EnableConfigurationProperties({
        AdminBootstrapProperties.class,
        InviteTokenProperties.class
})
public class AdminConfigurationProperties {
}
