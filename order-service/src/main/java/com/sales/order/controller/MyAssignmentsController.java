package com.sales.order.controller;

import com.sales.order.controller.dto.ClientAssignmentResponse;
import com.sales.order.service.AssignmentService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Self-service endpoints for the preventista dashboard. Authenticated
 * (any role) but NOT role-gated to ADMIN \u2014 a preventista needs to see
 * their own cartera without an admin acting on their behalf.
 *
 * <p>The preventista's userId is read from the JWT principal (the
 * custom {@code JwtAuthenticationFilter} populates it as a {@code UUID};
 * the OAuth2 resource-server default populates a {@code Jwt} whose
 * {@code sub} claim is the user id).
 */
@RestController
@RequestMapping("/api/v1/me")
public class MyAssignmentsController {

    private final AssignmentService assignmentService;

    public MyAssignmentsController(AssignmentService assignmentService) {
        this.assignmentService = assignmentService;
    }

    /**
     * Returns the active clients assigned to the calling preventista.
     * Empty list when the user is not a preventista (no vendor row matches).
     */
    @GetMapping("/clients")
    public ResponseEntity<List<ClientAssignmentResponse>> myClients(Authentication auth) {
        UUID userId = actorId(auth);
        var views = assignmentService.getCarteraForUser(userId);
        var out = views.stream()
                .map(v -> ClientAssignmentResponse.from(v.client(), v.assignment()))
                .toList();
        return ResponseEntity.ok(out);
    }

    private static UUID actorId(Authentication auth) {
        Object p = auth.getPrincipal();
        if (p instanceof UUID u) return u;
        if (p instanceof Jwt jwt) return UUID.fromString(jwt.getSubject());
        throw new IllegalStateException("Unexpected principal type: "
                + (p == null ? "null" : p.getClass()));
    }
}