package com.sales.order.controller;

import com.sales.order.service.AssignmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Internal (service-to-service) endpoints for the assignment workflow.
 *
 * <p>Protected by {@code InternalSecurityConfig} via a static bearer
 * service-token; the {@code @PreAuthorize} gate below is an additional
 * defense-in-depth check that requires the
 * {@code SCOPE_internal:read} authority the internal filter sets.
 */
@RestController
@RequestMapping("/internal")
@PreAuthorize("hasAuthority('SCOPE_internal:read')")
public class InternalAssignmentsController {

    private final AssignmentService assignmentService;

    public InternalAssignmentsController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    /**
     * Cascade-close all active client assignments associated with the Vendor
     * whose {@code user_id} matches {@code userId}. Used by sync-service when
     * a preventista user is deactivated: their clients must surface in
     * {@code GET /api/v1/admin/clients/unassigned} so the admin can re-assign.
     *
     * @return the number of assignments closed (zero if no vendor matches the
     *         user, e.g. when the deactivated user was never a preventista).
     */
    @PostMapping("/users/{userId}/cascade-close-vendor-assignments")
    public ResponseEntity<CascadeResponse> cascadeCloseForUser(@PathVariable UUID userId) {
        int closed = assignmentService.cascadeCloseAssignmentsForUser(userId);
        return ResponseEntity.ok(new CascadeResponse(userId, closed));
    }

    public record CascadeResponse(UUID userId, int assignmentsClosed) {}
}