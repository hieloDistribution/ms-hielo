package com.sales.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sales.order.auth.security.DriverContext;
import com.sales.order.auth.security.JwtAuthenticationFilter;
import com.sales.order.auth.security.JwtService;
import com.sales.order.auth.security.SecurityConfig;
import com.sales.order.auth.security.VendorContext;
import com.sales.order.auth.support.AuthExceptionHandler;
import com.sales.order.controller.dto.AssignRequest;
import com.sales.order.model.Client;
import com.sales.order.model.Vendor;
import com.sales.order.model.VendorClientAssignment;
import com.sales.order.repository.VendorRepository;
import com.sales.order.service.AssignmentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TDD-style slice tests for {@link AssignmentController}.
 *
 * <p>{@code @WebMvcTest} loads the Spring MVC slice and (via the imported
 * {@link SecurityConfig}) the JWT filter chain. We mock the
 * {@link AssignmentService} so each test asserts pure controller-layer
 * behaviour: status codes, JSON shape, and (importantly) the
 * {@code @PreAuthorize("hasRole('ADMIN')")} gate.
 *
 * <p>Authenticated admin requests are simulated via
 * {@link SecurityMockMvcRequestPostProcessors#jwt} with the appropriate
 * authority claim. Non-admin requests carry only {@code ROLE_USER} to verify
 * the gate denies them with 403.
 */
@WebMvcTest(controllers = AssignmentController.class)
@Import({SecurityConfig.class, AuthExceptionHandler.class, JwtAuthenticationFilter.class})
class AssignmentControllerTest {

    private static final UUID TEST_ACTOR =
            UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;

    @MockBean AssignmentService assignmentService;
    @MockBean VendorRepository vendorRepository;

    // Security beans the SecurityConfig / JwtAuthenticationFilter need at
    // construction time. Mocked because we exercise the filter via
    // SecurityMockMvcRequestPostProcessors.jwt, not via a real JWT.
    @MockBean JwtService jwtService;
    @MockBean VendorContext vendorContext;
    @MockBean DriverContext driverContext;

    /** Jwt post-processor with a valid-UUID subject and ROLE_ADMIN. */
    private static RequestPostProcessor adminJwt() {
        return jwt()
                .jwt(j -> j.subject(TEST_ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** Jwt post-processor with a valid-UUID subject and ROLE_USER only. */
    private static RequestPostProcessor userJwt() {
        return jwt()
                .jwt(j -> j.subject(TEST_ACTOR.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_USER"));
    }

    // --- helpers --------------------------------------------------------

    private Client sampleClient(UUID id) {
        Client c = new Client();
        c.setId(id);
        c.setName("Heladería del Sur");
        c.setTaxId("30-12345678-9");
        c.setAddress("Av. Siempre Viva 742");
        c.setPhone("+5491155550000");
        c.setEmail("sur@example.com");
        c.setActive(true);
        c.setDeletedAt(null);
        return c;
    }

    private Vendor sampleVendor(UUID id, String displayName, boolean active) {
        Vendor v = new Vendor();
        v.setId(id);
        v.setUserId(UUID.randomUUID());
        v.setDisplayName(displayName);
        v.setEmployeeCode("EMP-" + id.toString().substring(0, 4));
        v.setActive(active);
        v.setDeletedAt(null);
        return v;
    }

    private VendorClientAssignment sampleAssignment(UUID vendorId, UUID clientId) {
        VendorClientAssignment a = new VendorClientAssignment();
        a.setId(UUID.randomUUID());
        a.setVendorId(vendorId);
        a.setClientId(clientId);
        a.activateFrom(Instant.now());
        return a;
    }

    // --- PATCH /clients/{id}/assign -------------------------------------

    @Test
    void assign_as_admin_returns_200_with_client_view() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();

        Client c = sampleClient(clientId);
        VendorClientAssignment a = sampleAssignment(vendorId, clientId);
        when(assignmentService.assign(eq(clientId), eq(vendorId), any(UUID.class)))
                .thenReturn(new AssignmentService.AssignmentResult(c, a, null));

        AssignRequest body = new AssignRequest(vendorId);

        mvc.perform(patch("/api/v1/admin/clients/{clientId}/assign", clientId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value(clientId.toString()))
                .andExpect(jsonPath("$.assignedVendorId").value(vendorId.toString()))
                .andExpect(jsonPath("$.assignmentId").isNotEmpty());
    }

    @Test
    void assign_without_admin_role_returns_403() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();

        mvc.perform(patch("/api/v1/admin/clients/{clientId}/assign", clientId)
                        .with(userJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignRequest(vendorId))))
                .andExpect(status().isForbidden());

        verify(assignmentService, never()).assign(any(), any(), any());
    }

    @Test
    void assign_without_auth_returns_401() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();

        mvc.perform(patch("/api/v1/admin/clients/{clientId}/assign", clientId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignRequest(vendorId))))
                .andExpect(status().isUnauthorized());

        verify(assignmentService, never()).assign(any(), any(), any());
    }

    @Test
    void assign_with_unknown_client_propagates_404() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        when(assignmentService.assign(eq(clientId), eq(vendorId), any(UUID.class)))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Client not found"));

        mvc.perform(patch("/api/v1/admin/clients/{clientId}/assign", clientId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignRequest(vendorId))))
                .andExpect(status().isNotFound());
    }

    @Test
    void assign_to_inactive_vendor_propagates_409() throws Exception {
        UUID clientId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        when(assignmentService.assign(eq(clientId), eq(vendorId), any(UUID.class)))
                .thenThrow(new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT, "Vendor is inactive"));

        mvc.perform(patch("/api/v1/admin/clients/{clientId}/assign", clientId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AssignRequest(vendorId))))
                .andExpect(status().isConflict());
    }

    @Test
    void assign_with_malformed_body_returns_400() throws Exception {
        UUID clientId = UUID.randomUUID();

        mvc.perform(patch("/api/v1/admin/clients/{clientId}/assign", clientId)
                        .with(adminJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"vendorId\":null}"))
                .andExpect(status().isBadRequest());

        verify(assignmentService, never()).assign(any(), any(), any());
    }

    // --- PATCH /clients/{id}/unassign -----------------------------------

    @Test
    void unassign_as_admin_returns_200_with_unassigned_view() throws Exception {
        UUID clientId = UUID.randomUUID();
        Client c = sampleClient(clientId);
        when(assignmentService.unassign(eq(clientId), any(UUID.class))).thenReturn(c);

        mvc.perform(patch("/api/v1/admin/clients/{clientId}/unassign", clientId)
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clientId").value(clientId.toString()))
                .andExpect(jsonPath("$.assignmentId").doesNotExist())
                .andExpect(jsonPath("$.assignedVendorId").doesNotExist());
    }

    @Test
    void unassign_without_admin_role_returns_403() throws Exception {
        UUID clientId = UUID.randomUUID();

        mvc.perform(patch("/api/v1/admin/clients/{clientId}/unassign", clientId)
                        .with(userJwt()))
                .andExpect(status().isForbidden());

        verify(assignmentService, never()).unassign(any(), any());
    }

    // --- GET /vendors/{id}/clients --------------------------------------

    @Test
    void getCartera_as_admin_returns_vendor_with_clients() throws Exception {
        UUID vendorId = UUID.randomUUID();
        UUID clientId = UUID.randomUUID();
        Vendor v = sampleVendor(vendorId, "Juan Pérez", true);
        Client c = sampleClient(clientId);
        VendorClientAssignment a = sampleAssignment(vendorId, clientId);

        when(assignmentService.getCartera(vendorId))
                .thenReturn(new AssignmentService.VendorCarteraView(v,
                        new ArrayList<>(List.of(new AssignmentService.ClientView(c, a)))));

        mvc.perform(get("/api/v1/admin/vendors/{vendorId}/clients", vendorId)
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.vendorId").value(vendorId.toString()))
                .andExpect(jsonPath("$.displayName").value("Juan Pérez"))
                .andExpect(jsonPath("$.clientCount").value(1))
                .andExpect(jsonPath("$.clients[0].clientId").value(clientId.toString()))
                .andExpect(jsonPath("$.clients[0].assignedVendorId").value(vendorId.toString()));
    }

    @Test
    void getCartera_with_unknown_vendor_returns_404() throws Exception {
        UUID vendorId = UUID.randomUUID();
        when(assignmentService.getCartera(vendorId)).thenThrow(
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Vendor not found"));

        mvc.perform(get("/api/v1/admin/vendors/{vendorId}/clients", vendorId)
                        .with(adminJwt()))
                .andExpect(status().isNotFound());
    }

    // --- GET /clients/unassigned ----------------------------------------

    @Test
    void getUnassigned_as_admin_returns_orphan_list() throws Exception {
        UUID orphanId = UUID.randomUUID();
        Client orphan = sampleClient(orphanId);
        when(assignmentService.getUnassignedClients()).thenReturn(List.of(orphan));

        mvc.perform(get("/api/v1/admin/clients/unassigned")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].clientId").value(orphanId.toString()))
                .andExpect(jsonPath("$[0].assignedVendorId").doesNotExist());
    }

    // --- GET /vendors ---------------------------------------------------

    @Test
    void listVendors_as_admin_returns_only_active_vendors() throws Exception {
        UUID activeId = UUID.randomUUID();
        Vendor active = sampleVendor(activeId, "Activo López", true);
        when(vendorRepository.findAll()).thenReturn(List.of(active));

        mvc.perform(get("/api/v1/admin/vendors")
                        .with(adminJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(activeId.toString()))
                .andExpect(jsonPath("$[0].displayName").value("Activo López"))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    @Test
    void listVendors_without_admin_role_returns_403() throws Exception {
        mvc.perform(get("/api/v1/admin/vendors")
                        .with(userJwt()))
                .andExpect(status().isForbidden());

        verify(vendorRepository, never()).findAll();
    }
}