package com.sales.sync.auth.service;

import com.sales.sync.auth.dto.AuthResponse;
import com.sales.sync.auth.dto.SignupRequest;
import com.sales.sync.auth.model.RefreshToken;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.RefreshTokenRepository;
import com.sales.sync.auth.repository.UserRepository;
import com.sales.sync.auth.security.JwtProperties;
import com.sales.sync.auth.security.JwtService;
import com.sales.sync.auth.security.RefreshTokenCodec;
import com.sales.sync.auth.internal.OrderInternalVendorsClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Self-service user registration. Creates the user with the requested role,
 * hashes the password with BCrypt, issues an access JWT plus an opaque
 * refresh token tied to a fresh token family. Throws
 * {@link EmailAlreadyExistsException} when the email is already taken.
 */
@Service
public class SignupService {

    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenCodec refreshTokenCodec;
    private final JwtProperties props;
    private final OrderInternalVendorsClient orderInternalVendorsClient;

    public SignupService(UserRepository users,
                         RefreshTokenRepository tokens,
                         PasswordEncoder passwordEncoder,
                         JwtService jwtService,
                         RefreshTokenCodec refreshTokenCodec,
                         JwtProperties props,
                         OrderInternalVendorsClient orderInternalVendorsClient) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenCodec = refreshTokenCodec;
        this.props = props;
        this.orderInternalVendorsClient = orderInternalVendorsClient;
    }

    @Transactional
    public AuthResponse signup(SignupRequest req) {
        String email = req.email().toLowerCase(Locale.ROOT);
        if (users.findByEmail(email).isPresent()) {
            throw new EmailAlreadyExistsException(email);
        }

        User u = new User();
        UUID userId = UUID.randomUUID();
        u.setId(userId);
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        u.setLocked(false);
        User.Role role = User.Role.valueOf(req.role());
        if (role == User.Role.cliente) {
            throw new IllegalArgumentException("Cannot create active user account for role 'cliente'");
        }
        u.setRole(role);

        if (req.full_name() != null) u.setFullName(req.full_name());
        if (req.phone() != null) u.setPhone(req.phone());
        if (req.dni() != null) u.setDni(req.dni());
        if (req.business_name() != null) u.setBusinessName(req.business_name());
        if (req.business_lat() != null) u.setBusinessLat(BigDecimal.valueOf(req.business_lat()));
        if (req.business_lng() != null) u.setBusinessLng(BigDecimal.valueOf(req.business_lng()));
        if (req.business_address() != null) u.setBusinessAddress(req.business_address());

        if (User.Role.vendedor == u.getRole()) {
            UUID vendorId = UUID.randomUUID();
            u.setVendorId(vendorId);
            orderInternalVendorsClient.createVendor(vendorId, userId, u.getFullName(), u.getEmail(), u.getPhone());
        }

        users.save(u);

        UUID family = UUID.randomUUID();
        String access = jwtService.sign(u.getId(), u.getVendorId(), u.getEmail(), u.getRole());
        RefreshTokenCodec.OpaqueRefreshToken rt = refreshTokenCodec.generate();

        RefreshToken row = new RefreshToken();
        row.setUserId(u.getId());
        row.setTokenFamily(family);
        row.setTokenHash(rt.hash());
        row.setExpiresAt(Instant.now().plus(props.refreshTokenTtl()));
        tokens.save(row);

        return new AuthResponse(access, rt.plaintext(), props.accessTokenTtl().toSeconds());
    }
}