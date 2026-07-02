package com.sales.sync.auth.security;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.RequestScope;

import java.util.Optional;
import java.util.UUID;

/**
 * Request-scoped bag for the {@code vendor_id} claim surfaced by the JWT
 * filter. Read-only to controllers/services that want to log or annotate
 * a downstream query — never gate an authorisation decision on it.
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
