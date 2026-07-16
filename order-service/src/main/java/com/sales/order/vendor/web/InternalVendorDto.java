package com.sales.order.vendor.web;

import java.util.UUID;

public record InternalVendorDto(
        UUID id,
        UUID userId,
        String displayName,
        String email,
        String phone
) {}
