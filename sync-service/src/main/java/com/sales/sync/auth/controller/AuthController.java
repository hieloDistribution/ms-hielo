package com.sales.sync.auth.controller;

import com.sales.sync.auth.dto.AuthResponse;
import com.sales.sync.auth.dto.LoginRequest;
import com.sales.sync.auth.dto.RefreshRequest;
import com.sales.sync.auth.security.AccountLockedException;
import com.sales.sync.auth.security.InvalidCredentialsException;
import com.sales.sync.auth.security.TokenExpiredException;
import com.sales.sync.auth.security.TokenRevokedException;
import com.sales.sync.auth.service.AuthService;
import com.sales.sync.auth.service.RefreshRotationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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

    public AuthController(AuthService authService,
                          RefreshRotationService refreshRotationService) {
        this.authService = authService;
        this.refreshRotationService = refreshRotationService;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body) {
        try {
            return ResponseEntity.ok(authService.login(body));
        } catch (InvalidCredentialsException ex) {
            System.err.println("[DEBUG-CTRL] returning 401 invalid_credentials");
            return ResponseEntity.status(401).body(Map.of("error", "invalid_credentials"));
        } catch (AccountLockedException ex) {
            System.err.println("[DEBUG-CTRL] returning 423 account_locked");
            return ResponseEntity.status(423).body(Map.of("error", "account_locked"));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@Valid @RequestBody RefreshRequest body) {
        try {
            return ResponseEntity.ok(refreshRotationService.rotate(body.refresh_token()));
        } catch (TokenExpiredException ex) {
            return ResponseEntity.status(401).body(Map.of("error", "token_expired"));
        } catch (TokenRevokedException ex) {
            return ResponseEntity.status(401).body(Map.of("error", "token_revoked"));
        }
    }
}
