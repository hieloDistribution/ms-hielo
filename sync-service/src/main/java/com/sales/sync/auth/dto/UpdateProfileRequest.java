package com.sales.sync.auth.dto;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @Size(max = 255) String full_name,
        @Size(max = 500) String avatar_url,
        @Size(max = 50) String phone,
        @Size(max = 255) String business_name,
        Double business_lat,
        Double business_lng,
        @Size(max = 500) String business_address
) {}