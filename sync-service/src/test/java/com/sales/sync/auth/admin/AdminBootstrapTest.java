package com.sales.sync.auth.admin;

import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.ApplicationArguments;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit-level coverage for the first-admin bootstrap (PR2 of change
 * {@code admin-console}). Mockito-only, fast deterministic cycle.
 *
 * <p>Owner: change admin-console. Spec reference: B1 (first-boot seeder)
 * and B3 (--admin-recover) in
 * {@code openspec/changes/admin-console/specs/admin-bootstrap/spec.md}.
 */
@ExtendWith(MockitoExtension.class)
class AdminBootstrapTest {

    @Mock private UserRepository users;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AdminBootstrapProperties props;
    @Mock private RandomPasswordGenerator passwordGenerator;

    private AdminBootstrap bootstrap;

    private final ByteArrayOutputStream stdoutCapture = new ByteArrayOutputStream();
    private final PrintStream originalOut = System.out;

    @BeforeEach
    void setUp() {
        bootstrap = new AdminBootstrap(users, passwordEncoder, passwordGenerator, props);
        System.setOut(new PrintStream(stdoutCapture, true, StandardCharsets.UTF_8));
    }

    @AfterEach
    void tearDown() {
        System.setOut(originalOut);
    }

    private String capturedStdout() {
        return stdoutCapture.toString(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("RED-1: empty DB -> creates one admin with [admin] role, must_change_password=true, prints credentials to stdout")
    void run_on_empty_db_creates_one_admin() {
        when(props.isBootstrapEnabled()).thenReturn(true);
        when(props.getRecoverEmail()).thenReturn("");
        when(users.countActiveByRole(User.Role.admin)).thenReturn(0L);
        when(passwordGenerator.generate(16)).thenReturn("random-pw-16bytes");
        when(passwordEncoder.encode("random-pw-16bytes")).thenReturn("bcrypt-hash");
        when(users.findByEmail(any())).thenReturn(Optional.empty());

        bootstrap.run(mock(ApplicationArguments.class));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        User u = captor.getValue();

        assertThat(u.getEmail())
                .as("synthetic email must be admin+<uuid>@bootstrap.local")
                .startsWith("admin+")
                .endsWith("@bootstrap.local");
        assertThat(u.getRoles())
                .as("multi-role source of truth")
                .containsExactly(User.Role.admin);
        assertThat(u.isMustChangePassword())
                .as("first login must force password change")
                .isTrue();
        assertThat(u.isActive()).isTrue();
        assertThat(u.isLocked()).isFalse();
        assertThat(u.getPasswordHash()).isEqualTo("bcrypt-hash");

        String out = capturedStdout();
        assertThat(out).contains("[admin-bootstrap] credentials");
        assertThat(out).contains("email=" + u.getEmail());
        assertThat(out).contains("password=random-pw-16bytes");
        assertThat(out).contains("capture now");
    }

    @Test
    @DisplayName("TRIANGULATE: existing active admin -> noop, no save, no stdout")
    void run_with_existing_admin_does_nothing() {
        when(props.isBootstrapEnabled()).thenReturn(true);
        when(props.getRecoverEmail()).thenReturn("");
        when(users.countActiveByRole(User.Role.admin)).thenReturn(1L);

        bootstrap.run(mock(ApplicationArguments.class));

        verify(users, never()).save(any());
        assertThat(capturedStdout())
                .as("must not print credentials when an admin already exists")
                .doesNotContain("[admin-bootstrap] credentials");
    }

    @Test
    @DisplayName("TRIANGULATE: bootstrap disabled -> noop")
    void run_with_disabled_property_skips() {
        when(props.isBootstrapEnabled()).thenReturn(false);

        bootstrap.run(mock(ApplicationArguments.class));

        verify(users, never()).save(any());
        verify(users, never()).countActiveByRole(any());
        assertThat(capturedStdout()).doesNotContain("[admin-bootstrap] credentials");
    }

    @Test
    @DisplayName("TRIANGULATE: --admin-recover=email and no active admin -> creates admin with that email")
    void run_with_recover_email_and_no_admin_creates_admin() {
        when(props.isBootstrapEnabled()).thenReturn(true);
        when(props.getRecoverEmail()).thenReturn("ceo@hielo.com");
        when(users.countActiveByRole(User.Role.admin)).thenReturn(0L);
        when(users.findByEmail("ceo@hielo.com")).thenReturn(Optional.empty());
        when(passwordGenerator.generate(16)).thenReturn("recovery-pw");
        when(passwordEncoder.encode("recovery-pw")).thenReturn("bcrypt-recovery");

        bootstrap.run(mock(ApplicationArguments.class));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("ceo@hielo.com");
        assertThat(captor.getValue().getRoles()).containsExactly(User.Role.admin);
        assertThat(captor.getValue().isMustChangePassword()).isTrue();

        assertThat(capturedStdout()).contains("email=ceo@hielo.com");
        assertThat(capturedStdout()).contains("password=recovery-pw");
    }

    @Test
    @DisplayName("TRIANGULATE: --admin-recover=email with existing admin -> noop (refuse override)")
    void run_with_recover_email_and_existing_admin_is_noop() {
        when(props.isBootstrapEnabled()).thenReturn(true);
        when(props.getRecoverEmail()).thenReturn("ceo@hielo.com");
        when(users.countActiveByRole(User.Role.admin)).thenReturn(1L);

        bootstrap.run(mock(ApplicationArguments.class));

        verify(users, never()).save(any());
        assertThat(capturedStdout()).doesNotContain("[admin-bootstrap] credentials");
    }

    @Test
    @DisplayName("REFACTOR: idempotent on second call")
    void run_is_idempotent_on_second_call() {
        when(props.isBootstrapEnabled()).thenReturn(true);
        when(props.getRecoverEmail()).thenReturn("");
        // First call sees 0 admins, second call sees 1 (because first saved one).
        when(users.countActiveByRole(User.Role.admin))
                .thenReturn(0L)
                .thenReturn(1L);
        when(users.findByEmail(any())).thenReturn(Optional.empty());
        when(passwordGenerator.generate(16)).thenReturn("first-pw");
        when(passwordEncoder.encode("first-pw")).thenReturn("first-hash");

        bootstrap.run(mock(ApplicationArguments.class));
        bootstrap.run(mock(ApplicationArguments.class));

        verify(users, times(1)).save(any());
    }

    @Test
    @DisplayName("EDGE: recover-email collides with existing user (non-admin) -> refuse clobber")
    void run_with_recover_email_colliding_with_existing_user_skips() {
        when(props.isBootstrapEnabled()).thenReturn(true);
        when(props.getRecoverEmail()).thenReturn("already-taken@hielo.com");
        when(users.countActiveByRole(User.Role.admin)).thenReturn(0L);
        when(users.findByEmail("already-taken@hielo.com"))
                .thenReturn(Optional.of(new User()));

        bootstrap.run(mock(ApplicationArguments.class));

        verify(users, never()).save(any());
        assertThat(capturedStdout()).doesNotContain("[admin-bootstrap] credentials");
    }

    @Test
    @DisplayName("EDGE: recover-email upper-cased and trimmed -> normalized")
    void run_with_recover_email_normalizes_case_and_whitespace() {
        when(props.isBootstrapEnabled()).thenReturn(true);
        when(props.getRecoverEmail()).thenReturn("  CEO@Hielo.COM  ");
        when(users.countActiveByRole(User.Role.admin)).thenReturn(0L);
        when(users.findByEmail("ceo@hielo.com")).thenReturn(Optional.empty());
        when(passwordGenerator.generate(16)).thenReturn("normalized-pw");
        when(passwordEncoder.encode("normalized-pw")).thenReturn("normalized-hash");

        bootstrap.run(mock(ApplicationArguments.class));

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("ceo@hielo.com");
    }
}
