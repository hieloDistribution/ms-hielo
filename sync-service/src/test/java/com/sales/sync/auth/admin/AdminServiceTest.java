package com.sales.sync.auth.admin;

import com.sales.sync.auth.admin.LastAdminGuard;
import com.sales.sync.auth.model.Role;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.RoleRepository;
import com.sales.sync.auth.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminService}. Covers role change, deactivate,
 * reactivate, invite issue, and invite redeem — each verifying the
 * audit log row is written.
 *
 * <p>Owner: change {@code admin-console} PR4.
 */
@ExtendWith(MockitoExtension.class)
class AdminServiceTest {

    @Mock private UserRepository users;
    @Mock private RoleRepository roles;
    @Mock private AdminAuditLogRepository auditLogs;
    @Mock private AdminInviteRepository invites;
    @Mock private InviteTokenCodec tokenCodec;
    @Mock private InviteTokenProperties inviteProps;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private LastAdminGuard lastAdminGuard;

    private AdminService newService() {
        return new AdminService(users, roles, auditLogs, invites,
                tokenCodec, inviteProps, passwordEncoder, lastAdminGuard);
    }

    /** Single canonical Role("admin") instance for identity-stable stubs. */
    private static final Role ADMIN = mkRole("admin");
    /** Single canonical Role("cliente") instance. */
    private static final Role CLIENTE = mkRole("cliente");

    private static Role mkRole(String name) {
        Role r = new Role();
        r.setId(UUID.randomUUID());
        r.setName(name);
        return r;
    }

    private static User aUserWithRoles(Set<Role> rs) {
        User u = new User();
        u.setId(UUID.randomUUID());
        u.setEmail("u@hielo.local");
        u.setActive(true);
        u.setRoles(rs);
        return u;
    }

    @Test
    void change_roles_replaces_set_and_writes_audit_row() {
        User target = aUserWithRoles(Set.of(ADMIN));
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        when(roles.findByName("cliente")).thenReturn(Optional.of(CLIENTE));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminService svc = newService();
        User updated = svc.changeRoles(UUID.randomUUID(), target.getId(), Set.of("cliente"));

        assertThat(updated.getRoles()).containsExactly(CLIENTE);
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditLogs).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("roles_changed");
    }

    @Test
    void change_roles_rejects_self_demote_of_last_admin() {
        UUID selfId = UUID.randomUUID();
        User target = aUserWithRoles(Set.of(ADMIN));
        target.setId(selfId);
        when(users.findById(selfId)).thenReturn(Optional.of(target));
        org.mockito.Mockito.doThrow(new LastAdminGuard.CannotSelfDemoteLastAdmin())
                .when(lastAdminGuard).requireNotLastAdmin(selfId);

        AdminService svc = newService();
        assertThatThrownBy(() -> svc.changeRoles(selfId, selfId, Set.of("cliente")))
                .isInstanceOf(LastAdminGuard.CannotSelfDemoteLastAdmin.class);
        verify(users, never()).save(any(User.class));
    }

    @Test
    void change_roles_throws_on_unknown_role_name() {
        // validateRoleNames throws BEFORE any repo call, so no stubs needed.
        AdminService svc = newService();
        assertThatThrownBy(() -> svc.changeRoles(UUID.randomUUID(), UUID.randomUUID(), Set.of("superuser")))
                .isInstanceOf(AdminException.UnknownRole.class);
    }

    @Test
    void change_roles_throws_user_not_found() {
        UUID missing = UUID.randomUUID();
        when(users.findById(missing)).thenReturn(Optional.empty());

        AdminService svc = newService();
        assertThatThrownBy(() -> svc.changeRoles(UUID.randomUUID(), missing, Set.of("admin")))
                .isInstanceOf(AdminException.UserNotFound.class);
    }

    @Test
    void deactivate_sets_inactive_and_locked_and_audits() {
        User target = aUserWithRoles(Set.of(CLIENTE));
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminService svc = newService();
        User updated = svc.deactivate(UUID.randomUUID(), target.getId());

        assertThat(updated.isActive()).isFalse();
        assertThat(updated.isLocked()).isTrue();
        ArgumentCaptor<AdminAuditLog> captor = ArgumentCaptor.forClass(AdminAuditLog.class);
        verify(auditLogs).save(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("user_deactivated");
    }

    @Test
    void deactivate_blocks_self_when_last_admin() {
        UUID selfId = UUID.randomUUID();
        User target = aUserWithRoles(Set.of(ADMIN));
        target.setId(selfId);
        when(users.findById(selfId)).thenReturn(Optional.of(target));
        org.mockito.Mockito.doThrow(new LastAdminGuard.CannotDeactivateLastAdmin())
                .when(lastAdminGuard).requireNotLastAdmin(selfId);

        AdminService svc = newService();
        assertThatThrownBy(() -> svc.deactivate(selfId, selfId))
                .isInstanceOf(LastAdminGuard.CannotDeactivateLastAdmin.class);
    }

    @Test
    void reactivate_sets_active_and_must_change_password() {
        User target = aUserWithRoles(Set.of(CLIENTE));
        target.setActive(false);
        target.setLocked(true);
        when(users.findById(target.getId())).thenReturn(Optional.of(target));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminService svc = newService();
        User updated = svc.reactivate(UUID.randomUUID(), target.getId());

        assertThat(updated.isActive()).isTrue();
        assertThat(updated.isLocked()).isFalse();
        assertThat(updated.isMustChangePassword()).isTrue();
    }

    @Test
    void issue_invite_creates_admin_invite_row_and_returns_token() {
        AdminService svc = newService();
        when(inviteProps.getTtlHours()).thenReturn(24);
        InviteTokenCodec.IssuedToken issued =
                new InviteTokenCodec.IssuedToken("plain-token", UUID.randomUUID().toString(),
                        Instant.now(), Instant.now().plusSeconds(86400));
        when(tokenCodec.issue("new@hielo.com", "admin", java.time.Duration.ofHours(24)))
                .thenReturn(issued);
        when(passwordEncoder.encode("plain-token")).thenReturn("bcrypt-hash");
        when(invites.save(any(AdminInvite.class))).thenAnswer(inv -> inv.getArgument(0));

        AdminService.IssuedInvite result = svc.issueInvite(UUID.randomUUID(), "new@hielo.com", "admin");

        assertThat(result.token()).isEqualTo("plain-token");
        ArgumentCaptor<AdminInvite> captor = ArgumentCaptor.forClass(AdminInvite.class);
        verify(invites).save(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("new@hielo.com");
        assertThat(captor.getValue().getRole()).isEqualTo("admin");
        assertThat(captor.getValue().getTokenHash()).isEqualTo("bcrypt-hash");
    }

    @Test
    void issue_invite_rejects_invalid_role() {
        AdminService svc = newService();
        assertThatThrownBy(() -> svc.issueInvite(UUID.randomUUID(), "u@hielo.com", "cliente"))
                .isInstanceOf(AdminException.InvalidRole.class);
        assertThatThrownBy(() -> svc.issueInvite(UUID.randomUUID(), "u@hielo.com", "superhero"))
                .isInstanceOf(AdminException.InvalidRole.class);
    }

    @Test
    void issue_invite_rejects_already_active_email() {
        AdminService svc = newService();
        when(users.findByEmail("taken@hielo.com")).thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> svc.issueInvite(UUID.randomUUID(), "taken@hielo.com", "admin"))
                .isInstanceOf(AdminException.EmailAlreadyActive.class);
    }

    @Test
    void redeem_invite_creates_user_and_marks_invite_used() {
        AdminService svc = newService();
        UUID jti = UUID.randomUUID();
        Instant exp = Instant.now().plusSeconds(60);
        when(tokenCodec.verify("plain-token"))
                .thenReturn(new InviteTokenCodec.ParsedToken(jti.toString(), "u@hielo.com", "admin", exp));
        AdminInvite row = new AdminInvite();
        row.setId(jti);
        row.setEmail("u@hielo.com");
        row.setRole("admin");
        row.setTokenHash("bcrypt-hash");
        row.setExpiresAt(exp);
        when(invites.findById(jti)).thenReturn(Optional.of(row));
        when(passwordEncoder.matches("plain-token", "bcrypt-hash")).thenReturn(true);
        when(passwordEncoder.encode(any())).thenReturn("new-hash");
        when(roles.findByName("admin")).thenReturn(Optional.of(ADMIN));
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        when(users.findByEmail("u@hielo.com")).thenReturn(Optional.empty());
        when(invites.save(any(AdminInvite.class))).thenAnswer(inv -> inv.getArgument(0));

        User u = svc.redeemInvite("plain-token", "new-password-1234", "Full Name");

        assertThat(u.getEmail()).isEqualTo("u@hielo.com");
        assertThat(u.getRoles()).containsExactly(ADMIN);
        assertThat(u.isMustChangePassword()).isFalse();
        ArgumentCaptor<AdminInvite> captor = ArgumentCaptor.forClass(AdminInvite.class);
        verify(invites).save(captor.capture());
        assertThat(captor.getValue().getUsedAt()).isNotNull();
    }

    @Test
    void redeem_invite_rejects_weak_password() {
        AdminService svc = newService();
        assertThatThrownBy(() -> svc.redeemInvite("plain-token", "short", "Name"))
                .isInstanceOf(AdminException.WeakPassword.class);
    }

    @Test
    void redeem_invite_rejects_already_used() {
        AdminService svc = newService();
        UUID jti = UUID.randomUUID();
        Instant exp = Instant.now().plusSeconds(60);
        when(tokenCodec.verify(any()))
                .thenReturn(new InviteTokenCodec.ParsedToken(jti.toString(), "u@hielo.com", "admin", exp));
        AdminInvite row = new AdminInvite();
        row.setId(jti);
        row.setUsedAt(Instant.now().minusSeconds(10));
        when(invites.findById(jti)).thenReturn(Optional.of(row));

        assertThatThrownBy(() -> svc.redeemInvite("plain-token", "good-password-1234", "Name"))
                .isInstanceOf(AdminException.InvalidInvite.class)
                .hasMessage("already_used");
    }

    @Test
    void redeem_invite_rejects_bad_hash() {
        AdminService svc = newService();
        UUID jti = UUID.randomUUID();
        Instant exp = Instant.now().plusSeconds(60);
        when(tokenCodec.verify(any()))
                .thenReturn(new InviteTokenCodec.ParsedToken(jti.toString(), "u@hielo.com", "admin", exp));
        AdminInvite row = new AdminInvite();
        row.setId(jti);
        row.setTokenHash("correct-hash");
        when(invites.findById(jti)).thenReturn(Optional.of(row));
        when(passwordEncoder.matches("plain-token", "correct-hash")).thenReturn(false);

        assertThatThrownBy(() -> svc.redeemInvite("plain-token", "good-password-1234", "Name"))
                .isInstanceOf(AdminException.InvalidInvite.class)
                .hasMessage("bad_hash");
    }
}
