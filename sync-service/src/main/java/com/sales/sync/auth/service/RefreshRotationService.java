package com.sales.sync.auth.service;

import com.sales.sync.auth.dto.AuthResponse;
import com.sales.sync.auth.model.RefreshToken;
import com.sales.sync.auth.repository.RefreshTokenRepository;
import com.sales.sync.auth.repository.UserRepository;
import com.sales.sync.auth.security.JwtProperties;
import com.sales.sync.auth.security.JwtService;
import com.sales.sync.auth.security.RefreshTokenCodec;
import com.sales.sync.auth.security.TokenExpiredException;
import com.sales.sync.auth.security.TokenRevokedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Single-use refresh token rotation. Burn-the-family semantics on replay
 * are described in {@code design.md §6}.
 */
@Service
public class RefreshRotationService {

    private final RefreshTokenRepository tokens;
    private final UserRepository users;
    private final JwtService jwtService;
    private final RefreshTokenCodec refreshTokenCodec;
    private final JwtProperties props;
    private final TokenFamilyOps familyOps;

    public RefreshRotationService(RefreshTokenRepository tokens,
                                  UserRepository users,
                                  JwtService jwtService,
                                  RefreshTokenCodec refreshTokenCodec,
                                  JwtProperties props,
                                  TokenFamilyOps familyOps) {
        this.tokens = tokens;
        this.users = users;
        this.jwtService = jwtService;
        this.refreshTokenCodec = refreshTokenCodec;
        this.props = props;
        this.familyOps = familyOps;
    }

    @Transactional
    public AuthResponse rotate(String presentedPlaintext) {
        String hash = RefreshTokenCodec.sha256Hex(presentedPlaintext);
        RefreshToken row = tokens.findByTokenHash(hash)
                .orElseThrow(() -> new TokenRevokedException("Refresh token not found"));

        if (row.getExpiresAt().isBefore(Instant.now())) {
            // Expired is a benign event: family is NOT burned.
            throw new TokenExpiredException("Refresh token is expired");
        }
        if (row.isRevoked()) {
            // Theft detection: burn the entire family in a separate
            // transaction so the UPDATE persists even if this outer
            // transaction rolls back when we throw below.
            familyOps.burn(row.getTokenFamily());
            throw new TokenRevokedException("Refresh token replayed");
        }

        UUID family = row.getTokenFamily();
        UUID userId = row.getUserId();

        // Mark presented row consumed.
        row.setRevoked(true);
        tokens.save(row);

        var userOpt = users.findById(userId);
        UUID vendorId = userOpt.map(u -> u.getVendorId()).orElse(null);

        String access = jwtService.sign(userId, vendorId, userOpt.get().getEmail(), userOpt.get().getRole());
        RefreshTokenCodec.OpaqueRefreshToken rt = refreshTokenCodec.generate();

        RefreshToken next = new RefreshToken();
        next.setUserId(userId);
        next.setTokenFamily(family);
        next.setTokenHash(rt.hash());
        next.setExpiresAt(Instant.now().plus(props.refreshTokenTtl()));
        tokens.save(next);

        return new AuthResponse(access, rt.plaintext(), props.accessTokenTtl().toSeconds(), false);
    }
}
