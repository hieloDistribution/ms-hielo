package com.sales.order.controller.dto;

import com.sales.order.model.Vendor;

import java.util.List;
import java.util.UUID;

/**
 * Response shape for {@code GET /api/v1/admin/vendors/{vendorId}/clients}.
 * Carries the vendor identity plus the full list of its currently-active
 * client assignments.
 */
public record VendorCarteraResponse(
        UUID vendorId,
        UUID userId,
        String displayName,
        String employeeCode,
        Boolean active,
        long clientCount,
        List<ClientAssignmentResponse> clients
) {
    public static VendorCarteraResponse from(Vendor v, List<ClientAssignmentResponse> clients) {
        return new VendorCarteraResponse(
                v.getId(),
                v.getUserId(),
                v.getDisplayName(),
                v.getEmployeeCode(),
                v.getActive(),
                clients == null ? 0L : clients.size(),
                clients
        );
    }
}