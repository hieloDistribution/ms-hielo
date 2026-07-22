package com.sales.order.controller.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request body for {@code PATCH /api/v1/admin/clients/{clientId}/assign}.
 * The target {@code vendorId} must point at an active, non-soft-deleted Vendor.
 */
public record AssignRequest(
        @NotNull UUID vendorId
) {
}