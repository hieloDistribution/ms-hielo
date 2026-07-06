package com.sales.sync.auth.web;

import java.time.Instant;
import java.util.UUID;

/**
 * Minimum-exposure user definition returned by the internal endpoint
 * {@code GET /internal/auth/users/{id}}.
 *
 * <p>The contract deliberately exposes only the fields needed for
 * cross-DB integrity (`id`, `email`, `locked`, `deletedAt`) and never
 * password hashes, refresh tokens, MFA secrets, roles, or PII beyond
 * this surface.
 */
public record InternalUserResponse(UUID id, String email, boolean locked, Instant deletedAt) {
}