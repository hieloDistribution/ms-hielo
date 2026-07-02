package com.sales.order.auth.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.Optional;
import java.util.UUID;

/**
 * Request-scoped holder for the {@code vendor_id} claim. Populated by
 * {@link JwtAuthenticationFilter}. Read-only to controllers/services for
 * logging or query hints; never a sole authorisation gate.
 */
@Component
@RequestScope
public class VendorContext {

    private Optional<UUID> vendorId = Optional.empty();

    public void set(Optional<UUID> vendorId) {
        this.vendorId = vendorId == null ? Optional.empty() : vendorId;
    }

    public Optional<UUID> get() {
        return vendorId;
    }
}
