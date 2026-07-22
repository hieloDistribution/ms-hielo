package com.sales.order.controller.dto;

import com.sales.order.model.Vendor;

import java.util.UUID;

/**
 * Lightweight Vendor projection for picker UIs and admin listings.
 * Excludes sensitive or redundant fields.
 */
public record VendorSummary(
        UUID id,
        UUID userId,
        String displayName,
        String employeeCode,
        String email,
        String phone,
        Boolean active
) {
    public static VendorSummary from(Vendor v) {
        return new VendorSummary(
                v.getId(),
                v.getUserId(),
                v.getDisplayName(),
                v.getEmployeeCode(),
                v.getEmail(),
                v.getPhone(),
                v.getActive()
        );
    }
}