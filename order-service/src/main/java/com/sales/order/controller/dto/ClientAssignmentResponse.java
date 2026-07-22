package com.sales.order.controller.dto;

import com.sales.order.model.Client;
import com.sales.order.model.VendorClientAssignment;

import java.time.Instant;
import java.util.UUID;

/**
 * Response shape returned by assign / unassign / get-cartera endpoints. Carries
 * the {@link Client} plus the active assignment metadata (or {@code null} for
 * assignment fields when the client is currently unassigned).
 */
public record ClientAssignmentResponse(
        UUID clientId,
        String clientName,
        String taxId,
        String address,
        String phone,
        String email,
        Boolean active,
        UUID assignmentId,
        UUID assignedVendorId,
        Instant effectiveFrom
) {
    public static ClientAssignmentResponse from(Client c, VendorClientAssignment a) {
        return new ClientAssignmentResponse(
                c.getId(),
                c.getName(),
                c.getTaxId(),
                c.getAddress(),
                c.getPhone(),
                c.getEmail(),
                c.getActive(),
                a == null ? null : a.getId(),
                a == null ? null : a.getVendorId(),
                a == null ? null : a.getEffectiveFrom()
        );
    }

    /** Convenience for unassigned clients (no assignment row). */
    public static ClientAssignmentResponse unassigned(Client c) {
        return from(c, null);
    }
}