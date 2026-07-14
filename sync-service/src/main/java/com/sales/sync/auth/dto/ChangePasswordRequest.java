package com.sales.sync.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequest(
        @NotBlank String old_password,
        @NotBlank @Size(min = 8, max = 128) String new_password
) {}