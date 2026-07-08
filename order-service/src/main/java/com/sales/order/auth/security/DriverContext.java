package com.sales.order.auth.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.Optional;
import java.util.UUID;

/**
 * Request-scoped holder for the delivery driver profile ID. Populated by
 * {@link JwtAuthenticationFilter}. Read-only to controllers/services.
 */
@Component
@RequestScope
public class DriverContext {

    private Optional<UUID> driverId = Optional.empty();

    public void set(Optional<UUID> driverId) {
        this.driverId = driverId == null ? Optional.empty() : driverId;
    }

    public Optional<UUID> get() {
        return driverId;
    }
}
