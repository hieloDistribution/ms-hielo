package com.sales.sync.auth.service;

import com.sales.sync.auth.dto.ChangePasswordRequest;
import com.sales.sync.auth.dto.LocationUpdateRequest;
import com.sales.sync.auth.dto.UpdateProfileRequest;
import com.sales.sync.auth.dto.UserResponse;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.UserRepository;
import com.sales.sync.auth.security.InvalidCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
public class UserProfileService {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    public UserProfileService(UserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public UserResponse getMe(UUID userId) {
        User u = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in DB: " + userId));
        return com.sales.sync.auth.dto.UserMapper.toResponse(u);
    }

    @Transactional
    public UserResponse updateMe(UUID userId, UpdateProfileRequest req) {
        User u = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in DB: " + userId));
        if (req.full_name() != null) u.setFullName(req.full_name());
        if (req.avatar_url() != null) u.setAvatarUrl(req.avatar_url());
        if (req.phone() != null) u.setPhone(req.phone());
        if (req.business_name() != null) u.setBusinessName(req.business_name());
        if (req.business_lat() != null) u.setBusinessLat(BigDecimal.valueOf(req.business_lat()));
        if (req.business_lng() != null) u.setBusinessLng(BigDecimal.valueOf(req.business_lng()));
        if (req.business_address() != null) u.setBusinessAddress(req.business_address());
        users.save(u);
        return com.sales.sync.auth.dto.UserMapper.toResponse(u);
    }

    @Transactional
    public void updateLocation(UUID userId, LocationUpdateRequest req) {
        User u = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in DB: " + userId));
        u.setLastLatitude(BigDecimal.valueOf(req.latitude()));
        u.setLastLongitude(BigDecimal.valueOf(req.longitude()));
        u.setLastLocationUpdated(Instant.now());
        users.save(u);
    }

    @Transactional
    public void changePassword(UUID userId, ChangePasswordRequest req) {
        User u = users.findById(userId)
                .orElseThrow(() -> new IllegalStateException("Authenticated user not found in DB: " + userId));
        if (!passwordEncoder.matches(req.old_password(), u.getPasswordHash())) {
            throw new InvalidCredentialsException("Wrong current password");
        }
        u.setPasswordHash(passwordEncoder.encode(req.new_password()));
        users.save(u);
    }
}