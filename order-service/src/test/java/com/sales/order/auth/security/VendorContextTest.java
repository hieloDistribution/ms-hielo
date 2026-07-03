package com.sales.order.auth.security;

import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class VendorContextTest {

    @Test
    void default_is_empty() {
        assertThat(new VendorContext().get()).isEmpty();
    }

    @Test
    void set_then_get_returns_value() {
        VendorContext ctx = new VendorContext();
        UUID v = UUID.randomUUID();
        ctx.set(Optional.of(v));
        assertThat(ctx.get()).contains(v);
    }

    @Test
    void set_null_is_treated_as_empty() {
        VendorContext ctx = new VendorContext();
        ctx.set(null);
        assertThat(ctx.get()).isEmpty();
    }
}
