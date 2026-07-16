package com.sales.sync.auth.admin;

import com.sales.sync.auth.model.User;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * DTO for the {@code /api/v1/admin/users} listing endpoint.
 * Owner: change {@code admin-console} PR4.
 */
public record AdminUserSummary(
        UUID id,
        String email,
        String fullName,
        Set<String> roles,
        boolean active,
        Instant createdAt
) {
    public static AdminUserSummary from(User u) {
        Set<String> roleNames = u.getRoles().stream()
                .map(r -> r.getName())
                .collect(Collectors.toSet());
        return new AdminUserSummary(
                u.getId(), u.getEmail(), u.getFullName(),
                roleNames, u.isActive(), u.getCreatedAt());
    }
}
