package com.sales.sync.auth.controller;

import com.sales.sync.auth.dto.AuthResponse;
import com.sales.sync.auth.dto.ChangePasswordRequest;
import com.sales.sync.auth.dto.LocationUpdateRequest;
import com.sales.sync.auth.dto.LoginRequest;
import com.sales.sync.auth.dto.LogoutRequest;
import com.sales.sync.auth.dto.RefreshRequest;
import com.sales.sync.auth.dto.SignupRequest;
import com.sales.sync.auth.dto.UpdateProfileRequest;
import com.sales.sync.auth.dto.UserResponse;
import com.sales.sync.auth.model.RefreshToken;
import com.sales.sync.auth.repository.RefreshTokenRepository;
import com.sales.sync.auth.security.AccountLockedException;
import com.sales.sync.auth.security.AuthContext;
import com.sales.sync.auth.security.InvalidCredentialsException;
import com.sales.sync.auth.security.JwtService;
import com.sales.sync.auth.security.RefreshTokenCodec;
import com.sales.sync.auth.security.TokenExpiredException;
import com.sales.sync.auth.security.TokenRevokedException;
import com.sales.sync.auth.service.AuthService;
import com.sales.sync.auth.service.RefreshRotationService;
import com.sales.sync.auth.service.SignupService;
import com.sales.sync.auth.service.UserProfileService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshRotationService refreshRotationService;
    private final SignupService signupService;
    private final UserProfileService userProfileService;
    private final RefreshTokenRepository tokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthContext authContext;

    public AuthController(AuthService authService,
                          RefreshRotationService refreshRotationService,
                          SignupService signupService,
                          UserProfileService userProfileService,
                          RefreshTokenRepository tokens,
                          PasswordEncoder passwordEncoder,
                          JwtService jwtService,
                          AuthContext authContext) {
        this.authService = authService;
        this.refreshRotationService = refreshRotationService;
        this.signupService = signupService;
        this.userProfileService = userProfileService;
        this.tokens = tokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.authContext = authContext;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body) {
        try {
            return ResponseEntity.ok(authService.login(body));
        } catch (InvalidCredentialsException ex) {
            return ResponseEntity.status(401)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "invalid_credentials"));
        } catch (AccountLockedException ex) {
            return ResponseEntity.status(423)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "account_locked"));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshRequest body) {
        try {
            return ResponseEntity.ok(refreshRotationService.rotate(body.refresh_token()));
        } catch (TokenExpiredException ex) {
            return ResponseEntity.status(401)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "token_expired"));
        } catch (TokenRevokedException ex) {
            return ResponseEntity.status(401)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("error", "token_revoked"));
        }
    }

    @PostMapping("/signup")
    public ResponseEntity<AuthResponse> signup(@Valid @RequestBody SignupRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(signupService.signup(body));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody LogoutRequest body) {
        String hash = RefreshTokenCodec.sha256Hex(body.refresh_token());
        tokens.findByTokenHash(hash).ifPresent(t -> {
            t.setRevoked(true);
            tokens.save(t);
        });
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/change-password")
    public ResponseEntity<Void> changePassword(@Valid @RequestBody ChangePasswordRequest body) {
        userProfileService.changePassword(authContext.requireUserId(), body);
        return ResponseEntity.noContent().build();
    }
}