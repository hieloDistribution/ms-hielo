package com.sales.order.vendor.web;

import java.util.UUID;

/**
 * Response body for {@code GET /internal/vendors/by-user/{userId}} (design §3.1
 * reverse direction / D-05). {@code hasActiveVendor=true} instructs
 * {@code sync-service.UserService.delete} to refuse the User deletion.
 */
public record HasActiveVendorResponse(UUID userId, boolean hasActiveVendor) {
}