package com.sales.sync.auth.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SignupRequest(
        @NotBlank @Email @Size(max = 320) String email,
        @NotBlank @Size(min = 8, max = 128) String password,
        @NotBlank @Pattern(regexp = "vendedor|cliente") String role,
        @NotBlank @Size(max = 255) String full_name,
        @Size(max = 50) String phone,
        @Size(max = 50) String dni,
        @Size(max = 255) String business_name,
        Double business_lat,
        Double business_lng,
        @Size(max = 500) String business_address
) {}