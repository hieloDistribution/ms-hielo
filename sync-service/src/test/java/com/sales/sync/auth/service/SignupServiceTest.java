package com.sales.sync.auth.service;

import com.sales.sync.auth.admin.AdminAuditLogger;
import com.sales.sync.auth.admin.AuditEvent;
import com.sales.sync.auth.dto.AuthResponse;
import com.sales.sync.auth.dto.SignupRequest;
import com.sales.sync.auth.model.RefreshToken;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.RefreshTokenRepository;
import com.sales.sync.auth.repository.UserRepository;
import com.sales.sync.auth.security.JwtProperties;
import com.sales.sync.auth.security.JwtService;
import com.sales.sync.auth.security.RefreshTokenCodec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for the signup-bypass closure (PR1 of change
 * {@code admin-console}). Mockito-only — no Spring context, no database —
 * so the cycle is fast and deterministic.
 *
 * <p>Owner: change admin-console. Spec reference: R1 in
 * {@code openspec/changes/admin-console/specs/admin-roles/spec.md}.
 */
@ExtendWith(MockitoExtension.class)
class SignupServiceTest {

    @Mock private UserRepository users;
    @Mock private RefreshTokenRepository tokens;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private RefreshTokenCodec refreshTokenCodec;
    @Mock private JwtProperties props;
    @Mock private AdminAuditLogger auditLogger;

    @InjectMocks private SignupService signup;

    @Test
    @DisplayName("RED-1: signup with role=admin in body creates a CLIENT user and writes a forensic audit row")
    void signup_with_role_admin_in_body_creates_cliente_and_audits_bypass() {
        SignupRequest req = new SignupRequest(
                "hacker@x.com",
                "weakpassword123",
                "admin", // bypass attempt
                "Hacker",
                null, null, null, null, null, null);

        when(users.findByEmail("hacker@x.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("weakpassword123")).thenReturn("hashed-pw");
        when(jwtService.sign(any(), any(), any(), any())).thenReturn("access.jwt");
        when(props.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(refreshTokenCodec.generate()).thenReturn(
                new RefreshTokenCodec.OpaqueRefreshToken("plain-refresh", "hash-refresh"));

        AuthResponse resp = signup.signup(req);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole())
                .as("bypass attempt must NOT yield admin role")
                .isEqualTo(User.Role.cliente);
        assertThat(userCaptor.getValue().getEmail()).isEqualTo("hacker@x.com");
        assertThat(resp.access_token()).isEqualTo("access.jwt");

        // Audit row written for the bypass attempt.
        ArgumentCaptor<AuditEvent> auditCaptor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(auditLogger).log(auditCaptor.capture());
        AuditEvent evt = auditCaptor.getValue();
        assertThat(evt.action()).isEqualTo("signup_role_ignored");
        assertThat(evt.targetEmail()).isEqualTo("hacker@x.com");
        assertThat(evt.actorUserId())
                .as("signup bypass comes from anonymous network traffic, not a known user")
                .isNull();
        assertThat(evt.notes()).contains("admin");
    }

    @Test
    @DisplayName("GREEN-1: signup with role=cliente creates a CLIENT user and does NOT audit")
    void signup_with_role_cliente_does_not_audit() {
        SignupRequest req = new SignupRequest(
                "u@x.com",
                "weakpassword123",
                "cliente",
                "User",
                null, null, null, null, null, null);

        when(users.findByEmail("u@x.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("weakpassword123")).thenReturn("hashed-pw");
        when(jwtService.sign(any(), any(), any(), any())).thenReturn("access.jwt");
        when(props.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(refreshTokenCodec.generate()).thenReturn(
                new RefreshTokenCodec.OpaqueRefreshToken("plain-refresh", "hash-refresh"));

        AuthResponse resp = signup.signup(req);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole()).isEqualTo(User.Role.cliente);

        // Cliente is the no-op path: NO audit row.
        verify(auditLogger, never()).log(any());
        assertThat(resp.refresh_token()).isEqualTo("plain-refresh");
    }

    @Test
    @DisplayName("TRIANGULATE: signup with role=repartidor creates CLIENT and audits bypass")
    void signup_with_role_repartidor_creates_cliente_and_audits_bypass() {
        SignupRequest req = new SignupRequest(
                "driver-attempt@x.com",
                "weakpassword123",
                "repartidor",
                "Driver",
                null, null, null, null, null, null);

        when(users.findByEmail("driver-attempt@x.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("hashed-pw");
        when(jwtService.sign(any(), any(), any(), any())).thenReturn("access.jwt");
        when(props.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(refreshTokenCodec.generate()).thenReturn(
                new RefreshTokenCodec.OpaqueRefreshToken("plain-refresh", "hash-refresh"));

        signup.signup(req);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole()).isEqualTo(User.Role.cliente);

        verify(auditLogger).log(argThat(event ->
                "signup_role_ignored".equals(event.action())
                        && "driver-attempt@x.com".equals(event.targetEmail())
                        && event.notes() != null
                        && event.notes().contains("repartidor")));
    }

    @Test
    @DisplayName("REFACTOR: signup with role=null (clean clients) creates CLIENT and does NOT audit")
    void signup_with_null_role_does_not_audit() {
        SignupRequest req = new SignupRequest(
                "new@x.com",
                "weakpassword123",
                null, // clean new clients stop sending the role field
                "Clean",
                null, null, null, null, null, null);

        when(users.findByEmail("new@x.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("hashed-pw");
        when(jwtService.sign(any(), any(), any(), any())).thenReturn("access.jwt");
        when(props.accessTokenTtl()).thenReturn(Duration.ofMinutes(15));
        when(refreshTokenCodec.generate()).thenReturn(
                new RefreshTokenCodec.OpaqueRefreshToken("plain-refresh", "hash-refresh"));

        signup.signup(req);

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(users).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getRole()).isEqualTo(User.Role.cliente);

        // Null = no bypass attempt = no audit row.
        verify(auditLogger, never()).log(any());
    }
}
