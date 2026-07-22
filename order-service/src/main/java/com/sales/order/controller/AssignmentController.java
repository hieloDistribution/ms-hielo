package com.sales.order.controller;

import com.sales.order.controller.dto.AssignRequest;
import com.sales.order.controller.dto.ClientAssignmentResponse;
import com.sales.order.controller.dto.VendorCarteraResponse;
import com.sales.order.controller.dto.VendorSummary;
import com.sales.order.model.Client;
import com.sales.order.repository.VendorRepository;
import com.sales.order.service.AssignmentService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Admin endpoints for the {@code (vendor, client)} assignment workflow.
 *
 * <p>Authorization: every method requires the {@code ROLE_ADMIN} authority,
 * which is populated by {@code JwtAuthenticationFilter} from the JWT
 * {@code roles} claim. The role check is gated by {@code @EnableMethodSecurity}
 * on {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AssignmentController {

    private final AssignmentService assignmentService;
    private final VendorRepository vendors;

    public AssignmentController(AssignmentService assignmentService,
                                VendorRepository vendors) {
        this.assignmentService = assignmentService;
        this.vendors = vendors;
    }

    // --- principal extraction (see actorId below) -----------------------

    // --- commands --------------------------------------------------------

    /**
     * Assign {@code clientId} to {@code request.vendorId}. Closes any prior
     * active assignment for the same client. Returns the updated client view.
     */
    @PatchMapping("/clients/{clientId}/assign")
    public ResponseEntity<ClientAssignmentResponse> assign(
            @PathVariable UUID clientId,
            @Valid @RequestBody AssignRequest request,
            Authentication auth) {
        var result = assignmentService.assign(clientId, request.vendorId(), actorId(auth));
        return ResponseEntity.ok(ClientAssignmentResponse.from(result.client(), result.assignment()));
    }

    /**
     * Close the active assignment (if any) for {@code clientId}. Idempotent.
     */
    @PatchMapping("/clients/{clientId}/unassign")
    public ResponseEntity<ClientAssignmentResponse> unassign(
            @PathVariable UUID clientId,
            Authentication auth) {
        Client full = assignmentService.unassign(clientId, actorId(auth));
        return ResponseEntity.ok(ClientAssignmentResponse.unassigned(full));
    }

    // --- queries ---------------------------------------------------------

    /**
     * List the active cartera (assigned clients) for {@code vendorId}.
     */
    @GetMapping("/vendors/{vendorId}/clients")
    public VendorCarteraResponse getCartera(@PathVariable UUID vendorId) {
        var view = assignmentService.getCartera(vendorId);
        var clientResponses = view.clients().stream()
                .map(v -> ClientAssignmentResponse.from(v.client(), v.assignment()))
                .toList();
        return VendorCarteraResponse.from(view.vendor(), clientResponses);
    }

    /**
     * List non-deleted clients with no active assignment ("orphans" after a
     * cascade-close from deactivating a preventista).
     */
    @GetMapping("/clients/unassigned")
    public List<ClientAssignmentResponse> getUnassigned() {
        return assignmentService.getUnassignedClients().stream()
                .map(ClientAssignmentResponse::unassigned)
                .toList();
    }

    /**
     * List active, non-deleted vendors for picker UIs.
     */
    @GetMapping("/vendors")
    public List<VendorSummary> listVendors() {
        return vendors.findAll().stream()
                .filter(v -> v.getDeletedAt() == null)
                .filter(v -> Boolean.TRUE.equals(v.getActive()))
                .map(VendorSummary::from)
                .toList();
    }

    /**
     * Extract the actor UUID from the authentication. Handles both
     * shapes: the custom {@code JwtAuthenticationFilter} populates the
     * principal as a {@code UUID} directly; the OAuth2 resource-server
     * default populates a {@code Jwt} whose {@code sub} claim is the user id.
     */
    private static UUID actorId(Authentication auth) {
        Object p = auth.getPrincipal();
        if (p instanceof UUID u) return u;
        if (p instanceof Jwt jwt) return UUID.fromString(jwt.getSubject());
        throw new IllegalStateException("Unexpected principal type: " + (p == null ? "null" : p.getClass()));
    }
}