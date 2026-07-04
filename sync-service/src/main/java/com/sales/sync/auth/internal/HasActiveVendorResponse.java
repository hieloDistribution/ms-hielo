package com.sales.sync.auth.internal;

import java.util.UUID;

/**
 * Local mirror of {@code order-service}'s
 * {@code com.sales.order.vendor.web.HasActiveVendorResponse}. Jackson parses
 * the same JSON shape — named here in sync-service's package to avoid a
 * cross-module compile-time dependency on {@code order-service}.
 */
record HasActiveVendorResponse(UUID userId, boolean hasActiveVendor) {
}