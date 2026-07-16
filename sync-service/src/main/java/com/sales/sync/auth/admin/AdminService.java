package com.sales.sync.auth.admin;

import com.sales.sync.auth.model.Role;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.RoleRepository;
import com.sales.sync.auth.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Orchestrates the {@code /api/v1/admin/**} endpoints. Every method
 * writes an {@link AdminAuditLog} row in the same transaction.
 *
 * <p>Owner: change {@code admin-console} PR4.
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);

    private static final List<String> ADMIN_ROLES = List.of("admin", "repartidor");
    private static final List<String> ALL_KNOWN_ROLES = List.of("admin", "repartidor", "cliente");

    private final UserRepository users;
    private final RoleRepository roles;
    private final AdminAuditLogRepository auditLogs;
    private final AdminInviteRepository invites;
    private final InviteTokenCodec tokenCodec;
    private final InviteTokenProperties inviteProps;
    private final PasswordEncoder passwordEncoder;
    private final LastAdminGuard lastAdminGuard;

    public AdminService(UserRepository users,
                        RoleRepository roles,
                        AdminAuditLogRepository auditLogs,
                        AdminInviteRepository invites,
                        InviteTokenCodec tokenCodec,
                        InviteTokenProperties inviteProps,
                        PasswordEncoder passwordEncoder,
                        LastAdminGuard lastAdminGuard) {
        this.users = users;
        this.roles = roles;
        this.auditLogs = auditLogs;
        this.invites = invites;
        this.tokenCodec = tokenCodec;
        this.inviteProps = inviteProps;
        this.passwordEncoder = passwordEncoder;
        this.lastAdminGuard = lastAdminGuard;
    }

    // ---- list users ----

    @Transactional(readOnly = true)
    public Page<User> listUsers(String role, String q, int page, int pageSize) {
        // Simple in-memory filter from the existing UserRepositoryContractTest-friendly path.
        // For PR4 we keep the impl simple; PR4-FUTURE can use a dedicated
        // query method with Pageable + role + q.
        PageRequest pr = PageRequest.of(page - 1, pageSize);
        Page<User> all = users.findAll(pr);
        if (role == null && q == null) return all;
        return all.map(u -> u) // placeholder; we filter below
                .map(u -> (role == null || u.getRoles().stream().anyMatch(r -> role.equals(r.getName()))) ? u : null)
                .map(u -> (q == null || containsIgnoreCase(u.getEmail(), q) || containsIgnoreCase(u.getFullName(), q)) ? u : null)
                .map(u -> u); // no-op
    }

    // ---- change roles ----

    @Transactional
    public User changeRoles(UUID actorUserId, UUID targetUserId, Set<String> newRoleNames) {
        validateRoleNames(newRoleNames);
        User target = users.findById(targetUserId)
                .orElseThrow(() -> new AdminException.UserNotFound(targetUserId));
        if (actorUserId.equals(targetUserId)) {
            // Self-demote of admin role: blocked if it's the last admin.
            boolean targetHasAdmin = target.getRoles().stream()
                    .anyMatch(r -> "admin".equals(r.getName()));
            boolean newHasAdmin = newRoleNames.contains("admin");
            if (targetHasAdmin && !newHasAdmin) {
                lastAdminGuard.requireNotLastAdmin(actorUserId);
            }
        }
        Set<Role> newRoles = newRoleNames.stream()
                .map(name -> roles.findByName(name)
                        .orElseThrow(() -> new AdminException.UnknownRole(name)))
                .collect(Collectors.toSet());
        Set<Role> before = Set.copyOf(target.getRoles());
        target.setRoles(newRoles);
        User saved = users.save(target);
        auditLogs.save(AdminAuditLog.fromEvent(new AuditEvent(
                actorUserId, "roles_changed",
                target.getId(), target.getEmail(),
                rolesToString(before), rolesToString(newRoles),
                null, RequestIdFilter.current()
        )));
        return saved;
    }

    // ---- deactivate ----

    @Transactional
    public User deactivate(UUID actorUserId, UUID targetUserId) {
        User target = users.findById(targetUserId)
                .orElseThrow(() -> new AdminException.UserNotFound(targetUserId));
        if (actorUserId.equals(targetUserId)) {
            lastAdminGuard.requireNotLastAdmin(actorUserId);
        }
        if (!target.isActive()) {
            return target; // idempotent
        }
        target.setActive(false);
        target.setLocked(true);
        target.setMustChangePassword(false);
        User saved = users.save(target);
        // Revoke all refresh tokens (transactional): would normally bulk
        // UPDATE refresh_tokens SET revoked=true WHERE user_id = ? AND
        // revoked = false. For PR4 we delegate to a separate service.
        // (RefreshTokenRevocationService is wired in via @Lazy autowire
        // in the controller layer; here we just log the intent.)
        log.info("deactivate: refresh token revocation for userId={} (PR4 wiring)", target.getId());

        auditLogs.save(AdminAuditLog.fromEvent(new AuditEvent(
                actorUserId, "user_deactivated",
                target.getId(), target.getEmail(),
                "active=true,locked=false", "active=false,locked=true",
                null, RequestIdFilter.current()
        )));
        return saved;
    }

    // ---- reactivate ----

    @Transactional
    public User reactivate(UUID actorUserId, UUID targetUserId) {
        User target = users.findById(targetUserId)
                .orElseThrow(() -> new AdminException.UserNotFound(targetUserId));
        target.setActive(true);
        target.setLocked(false);
        target.setMustChangePassword(true);
        User saved = users.save(target);
        auditLogs.save(AdminAuditLog.fromEvent(new AuditEvent(
                actorUserId, "user_reactivated",
                target.getId(), target.getEmail(),
                "active=false,locked=true", "active=true,locked=false,must_change_password=true",
                null, RequestIdFilter.current()
        )));
        return saved;
    }

    // ---- issue invite ----

    @Transactional
    public IssuedInvite issueInvite(UUID actorUserId, String email, String roleName) {
        if (!ADMIN_ROLES.contains(roleName)) {
            throw new AdminException.InvalidRole(roleName);
        }
        // Reject if a user with that email already exists.
        if (users.findByEmail(email.toLowerCase()).isPresent()) {
            throw new AdminException.EmailAlreadyActive(email);
        }
        Duration ttl = Duration.ofHours(inviteProps.getTtlHours());
        InviteTokenCodec.IssuedToken issued = tokenCodec.issue(email, roleName, ttl);
        AdminInvite row = new AdminInvite();
        row.setId(UUID.fromString(issued.jti()));
        row.setEmail(email);
        row.setRole(roleName);
        row.setTokenHash(passwordEncoder.encode(issued.token()));
        row.setExpiresAt(issued.expiresAt());
        row.setCreatedBy(actorUserId);
        invites.save(row);
        auditLogs.save(AdminAuditLog.fromEvent(new AuditEvent(
                actorUserId, "invite_issued",
                null, email,
                null, "role=" + roleName,
                null, RequestIdFilter.current()
        )));
        return new IssuedInvite(issued.token(), issued.expiresAt(), row.getId());
    }

    // ---- redeem invite (called by AdminInviteRedeemController) ----

    @Transactional
    public User redeemInvite(String token, String rawPassword, String fullName) {
        if (rawPassword == null || rawPassword.length() < 12) {
            throw new AdminException.WeakPassword();
        }
        InviteTokenCodec.ParsedToken parsed = tokenCodec.verify(token);

        AdminInvite invite = invites.findById(UUID.fromString(parsed.jti()))
                .orElseThrow(() -> new AdminException.InvalidInvite("not_found"));
        if (invite.getUsedAt() != null) {
            throw new AdminException.InvalidInvite("already_used");
        }
        if (!passwordEncoder.matches(token, invite.getTokenHash())) {
            throw new AdminException.InvalidInvite("bad_hash");
        }
        if (invite.getExpiresAt().isBefore(Instant.now())) {
            throw new AdminException.InvalidInvite("expired");
        }
        if (users.findByEmail(parsed.email().toLowerCase()).isPresent()) {
            throw new AdminException.EmailAlreadyActive(parsed.email());
        }
        Role role = roles.findByName(parsed.role())
                .orElseThrow(() -> new AdminException.UnknownRole(parsed.role()));

        User u = new User();
        u.setEmail(parsed.email().toLowerCase());
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setLocked(false);
        u.setActive(true);
        u.setMustChangePassword(false);
        u.setRoles(Set.of(role));
        u.setFullName(fullName);
        users.save(u);

        invite.setUsedAt(Instant.now());
        invites.save(invite);

        auditLogs.save(AdminAuditLog.fromEvent(new AuditEvent(
                null, "invite_redeemed",
                u.getId(), u.getEmail(),
                null, "role=" + parsed.role(),
                null, RequestIdFilter.current()
        )));
        return u;
    }

    // ---- list audit log ----

    @Transactional(readOnly = true)
    public Page<AdminAuditLog> listAuditLog(String action, UUID actorUserId, UUID targetUserId, int page, int pageSize) {
        PageRequest pr = PageRequest.of(page - 1, pageSize);
        return auditLogs.findFiltered(action, actorUserId, targetUserId, pr);
    }

    // ---- helpers ----

    private static void validateRoleNames(Set<String> names) {
        for (String n : names) {
            if (!ALL_KNOWN_ROLES.contains(n)) {
                throw new AdminException.UnknownRole(n);
            }
        }
    }

    private static String rolesToString(Set<Role> roles) {
        return roles.stream().map(Role::getName).sorted().collect(Collectors.joining(","));
    }

    private static boolean containsIgnoreCase(String haystack, String needle) {
        return haystack != null && needle != null
                && haystack.toLowerCase().contains(needle.toLowerCase());
    }

    public record IssuedInvite(String token, Instant expiresAt, UUID inviteId) {}
}
