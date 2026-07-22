package com.sales.sync.auth.admin;

import com.sales.sync.auth.model.User;
import com.sales.sync.auth.security.AuthContext;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin endpoints. All paths under {@code /api/v1/admin/**} are gated by
 * {@code AdminRoleGateFilter} (PR3) which enforces the role + mcp + DB
 * re-query checks. This controller additionally requires the
 * {@code ROLE_ADMIN} authority (Spring Security), which the
 * JwtAuthenticationFilter populates from the JWT's {@code roles} claim.
 *
 * <p>Owner: change {@code admin-console} PR4.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    private final AdminService adminService;
    private final AuthContext authContext;

    public AdminController(AdminService adminService, AuthContext authContext) {
        this.adminService = adminService;
        this.authContext = authContext;
    }

    @GetMapping("/users")
    public AdminListResponse<AdminUserSummary> listUsers(
            @RequestParam(required = false) String role,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        if (pageSize > 100) pageSize = 100;
        Page<User> result = adminService.listUsers(role, q, page, pageSize);
        List<AdminUserSummary> items = result.getContent().stream()
                .map(AdminUserSummary::from).toList();
        return new AdminListResponse<>(items, result.getTotalElements(), page, pageSize);
    }

    @PatchMapping("/users/{id}/roles")
    public AdminUserSummary changeRoles(@PathVariable UUID id,
                                       @RequestBody @Valid AdminRolePatchRequest body) {
        User updated = adminService.changeRoles(authContext.requireUserId(), id, body.roles());
        return AdminUserSummary.from(updated);
    }

    @PostMapping("/users/{id}/deactivate")
    public AdminUserSummary deactivate(@PathVariable UUID id) {
        User u = adminService.deactivate(authContext.requireUserId(), id);
        return AdminUserSummary.from(u);
    }

    @PostMapping("/users/{id}/reactivate")
    public AdminUserSummary reactivate(@PathVariable UUID id) {
        User u = adminService.reactivate(authContext.requireUserId(), id);
        return AdminUserSummary.from(u);
    }

    @PostMapping("/invites")
    public ResponseEntity<AdminInviteResponse> issueInvite(@RequestBody @Valid AdminInviteRequest body) {
        AdminService.IssuedInvite issued = adminService.issueInvite(
                authContext.requireUserId(), body.email(), body.role());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                    new AdminInviteResponse(issued.inviteId(), issued.token(), issued.expiresAt()));
    }

    @GetMapping("/invites")
    public AdminListResponse<AdminInviteSummary> listInvites(
            @RequestParam(defaultValue = "true") boolean pendingOnly) {
        var items = adminService.listInvites(pendingOnly);
        return new AdminListResponse<>(items, items.size(), 1, items.size());
    }

    @DeleteMapping("/invites/{id}")
    public AdminInviteSummary revokeInvite(@PathVariable UUID id) {
        return adminService.revokeInvite(authContext.requireUserId(), id);
    }

    @GetMapping("/audit-log")
    public AdminListResponse<AdminAuditLogEntry> listAuditLog(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actorUserId,
            @RequestParam(required = false) UUID targetUserId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        if (pageSize > 100) pageSize = 100;
        Page<AdminAuditLog> result = adminService.listAuditLog(
                action, actorUserId, targetUserId, page, pageSize);
        // Resolve actor email best-effort (one query per page; the
        // listing endpoint is admin-only, so the volume is low).
        List<AdminAuditLogEntry> items = result.getContent().stream()
                .map(a -> new AdminAuditLogEntry(
                        a.getId(),
                        a.getActorUserId(),
                        a.getActorUserId() == null ? null
                                : adminService.listUsers(null, null, 1, 100)
                                        .getContent().stream()
                                        .filter(u -> u.getId().equals(a.getActorUserId()))
                                        .map(User::getEmail).findFirst().orElse(null),
                        a.getAction(),
                        a.getTargetUserId(),
                        a.getTargetEmail(),
                        a.getBeforeJson(),
                        a.getAfterJson(),
                        a.getRequestId(),
                        a.getNotes(),
                        a.getCreatedAt()))
                .toList();
        return new AdminListResponse<>(items, result.getTotalElements(), page, pageSize);
    }
}
