package com.sales.sync.auth.dto;

import com.sales.sync.auth.model.User;

import java.util.UUID;

public final class UserMapper {

    private UserMapper() {}

    public static UserResponse toResponse(User u) {
        return new UserResponse(
                u.getId(),
                u.getEmail(),
                u.getRole() != null ? u.getRole().name() : null,
                u.getFullName(),
                u.getAvatarUrl(),
                u.getPhone(),
                u.getDni(),
                u.getBusinessName(),
                u.getBusinessLat() != null ? u.getBusinessLat().doubleValue() : null,
                u.getBusinessLng() != null ? u.getBusinessLng().doubleValue() : null,
                u.getBusinessAddress(),
                u.getVendorId()
        );
    }
}