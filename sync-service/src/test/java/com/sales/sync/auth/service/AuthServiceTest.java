package com.sales.sync.auth.service;

import com.sales.sync.auth.dto.AuthResponse;
import com.sales.sync.auth.dto.LoginRequest;
import com.sales.sync.auth.model.RefreshToken;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.RefreshTokenRepository;
import com.sales.sync.auth.repository.UserRepository;
import com.sales.sync.auth.security.InvalidCredentialsException;
import com.sales.sync.auth.security.JwtProperties;
import com.sales.sync.auth.security.JwtService;
import com.sales.sync.auth.security.RefreshTokenCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for the login flow's PR2 changes:
 * must_change_password propagation into both the JWT (mcp claim) and
 * the {@link AuthResponse} body field.
 *
 * <p>Owner: change admin-console PR2. Spec reference: B2 of
 * {@code openspec/changes/admin-console/specs/admin-bootstrap/spec.md}.
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository users;
    @Mock private RefreshTokenRepository tokens;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenCodec refreshTokenCodec;
    @Mock private JwtProperties props;

    private AuthService newAuthService() {
        return new AuthService(users, tokens, passwordEncoder, jwtService, refreshTokenCodec, props);
    }

    @Test
    @DisplayName("RED: bootstrap admin login -> JWT carries mcp=true and response carries must_change_password=true")
    void login_bootstrap_admin_propagates_mcp_flag() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("bootstrap-admin@hielo.local");
        u.setPasswordHash("bcrypt-hash");
        u.setLocked(false);
        u.setActive(true);
        u.setMustChangePassword(true);
        u.setRoles(Set.of(User.Role.admin)); // multi-role source of truth

        when(users.findByEmail("bootstrap-admin@hielo.local")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(any(), eq("bcrypt-hash"))).thenReturn(true);
        when(tokens.findActiveFamilyByUserId(u.getId())).thenReturn(Optional.empty());
        when(jwtService.sign(any(), any(), eq("bootstrap-admin@hielo.local"), eq(User.Role.admin), eq(true)))
                .thenReturn("signed-jwt-with-mcp-true");
        when(props.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(refreshTokenCodec.generate()).thenReturn(
                new RefreshTokenCodec.OpaqueRefreshToken("plain-refresh", "hash-refresh"));

        AuthService svc = newAuthService();
        AuthResponse resp = svc.login(new LoginRequest("bootstrap-admin@hielo.local", "any-password"));

        assertThat(resp.must_change_password()).isTrue();
        assertThat(resp.access_token()).isEqualTo("signed-jwt-with-mcp-true");
        // Verify jwtService.sign was called with mustChangePassword=true.
        verify(jwtService).sign(
                eq(u.getId()), any(), eq("bootstrap-admin@hielo.local"), eq(User.Role.admin), eq(true));
    }

    @Test
    @DisplayName("TRIANGULATE: normal user login -> JWT mcp=false, response flag=false")
    void login_normal_user_propagates_mcp_false() {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("normal@hielo.local");
        u.setPasswordHash("bcrypt-hash");
        u.setLocked(false);
        u.setActive(true);
        u.setMustChangePassword(false);
        u.setRoles(Set.of(User.Role.cliente)); // multi-role source of truth

        when(users.findByEmail("normal@hielo.local")).thenReturn(Optional.of(u));
        when(passwordEncoder.matches(any(), eq("bcrypt-hash"))).thenReturn(true);
        when(tokens.findActiveFamilyByUserId(u.getId())).thenReturn(Optional.empty());
        when(jwtService.sign(any(), any(), eq("normal@hielo.local"), eq(User.Role.cliente), eq(false)))
                .thenReturn("signed-jwt-without-mcp");
        when(props.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(refreshTokenCodec.generate()).thenReturn(
                new RefreshTokenCodec.OpaqueRefreshToken("plain-refresh", "hash-refresh"));

        AuthService svc = newAuthService();
        AuthResponse resp = svc.login(new LoginRequest("normal@hielo.local", "any-password"));

        assertThat(resp.must_change_password()).isFalse();
        verify(jwtService).sign(
                eq(u.getId()), any(), eq("normal@hielo.local"), eq(User.Role.cliente), eq(false));
    }

    @Test
    @DisplayName("REFACTOR: unknown user -> InvalidCredentialsException, no JWT issued")
    void login_unknown_user_throws_and_does_not_sign_jwt() {
        when(users.findByEmail("nobody@hielo.local")).thenReturn(Optional.empty());

        AuthService svc = newAuthService();
        assertThatThrownBy(() -> svc.login(new LoginRequest("nobody@hielo.local", "any")))
                .isInstanceOf(InvalidCredentialsException.class);

        verify(jwtService, never()).sign(any(), any(), any(), any(), anyBoolean());
        verify(tokens, never()).save(any(RefreshToken.class));
    }
}
