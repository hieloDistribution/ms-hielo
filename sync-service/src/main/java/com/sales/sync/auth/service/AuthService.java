package com.sales.sync.auth.service;

import com.sales.sync.auth.dto.AuthResponse;
import com.sales.sync.auth.dto.LoginRequest;
import com.sales.sync.auth.model.RefreshToken;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.RefreshTokenRepository;
import com.sales.sync.auth.repository.UserRepository;
import com.sales.sync.auth.security.AccountLockedException;
import com.sales.sync.auth.security.InvalidCredentialsException;
import com.sales.sync.auth.security.JwtProperties;
import com.sales.sync.auth.security.JwtService;
import com.sales.sync.auth.security.RefreshTokenCodec;
import com.sales.sync.auth.support.AuthExceptionHandler;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Login flow: find the user, check the lock flag, BCrypt-compare the
 * password, issue a new access JWT, mint a fresh refresh token and persist
 * its SHA-256 hash tied to the user's current active family (or a new one
 * if no active family exists yet).
 */
@Service
public class AuthService {

    private final UserRepository users;
    private final RefreshTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenCodec refreshTokenCodec;
    private final JwtProperties props;

    public AuthService(UserRepository users,
                       RefreshTokenRepository tokens,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenCodec refreshTokenCodec,
                       JwtProperties props) {
        this.users = users;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenCodec = refreshTokenCodec;
        this.props = props;
    }

    @Transactional
    public AuthResponse login(LoginRequest req) {
        String email = req.email().toLowerCase(Locale.ROOT);
        User user = users.findByEmail(email)
                .orElseThrow(() -> new InvalidCredentialsException("Unknown user"));

        if (user.isLocked()) {
            throw new AccountLockedException("Account is locked");
        }
        if (!passwordEncoder.matches(req.password(), user.getPasswordHash())) {
            throw new InvalidCredentialsException("Wrong password");
        }

        UUID userId = user.getId();
        UUID family = tokens.findActiveFamilyByUserId(userId)
                .orElseGet(UUID::randomUUID);

        String access = jwtService.sign(userId, user.getVendorId());
        RefreshTokenCodec.OpaqueRefreshToken rt = refreshTokenCodec.generate();

        RefreshToken row = new RefreshToken();
        row.setUserId(userId);
        row.setTokenFamily(family);
        row.setTokenHash(rt.hash());
        row.setExpiresAt(Instant.now().plus(props.refreshTokenTtl()));
        tokens.save(row);

        return new AuthResponse(
                access,
                rt.plaintext(),
                props.accessTokenTtl().toSeconds());
    }
}
