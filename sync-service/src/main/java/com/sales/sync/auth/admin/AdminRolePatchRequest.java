package com.sales.sync.auth.admin;

import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

/**
 * Request body for {@code PATCH /api/v1/admin/users/{id}/roles}.
 * Owner: change {@code admin-console} PR4.
 */
public record AdminRolePatchRequest(@NotEmpty Set<String> roles) {}
