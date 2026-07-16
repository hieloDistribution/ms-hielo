package com.sales.sync.auth.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request body for {@code POST /api/v1/admin/invites}.
 * Owner: change {@code admin-console} PR4.
 */
public record AdminInviteRequest(
        @NotBlank @Email String email,
        @NotNull String role
) {}
