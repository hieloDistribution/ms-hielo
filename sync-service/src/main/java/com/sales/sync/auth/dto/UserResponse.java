package com.sales.sync.auth.dto;

import java.util.UUID;

public record UserResponse(
        UUID id,
        String email,
        String role,
        String full_name,
        String avatar_url,
        String phone,
        String dni,
        String business_name,
        Double business_lat,
        Double business_lng,
        String business_address,
        UUID vendor_id
) {}