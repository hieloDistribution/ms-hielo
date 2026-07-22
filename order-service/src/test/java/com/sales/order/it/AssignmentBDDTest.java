package com.sales.order.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sales.order.OrderServiceApplication;
import com.sales.order.model.Client;
import com.sales.order.model.Vendor;
import com.sales.order.repository.ClientRepository;
import com.sales.order.repository.VendorClientAssignmentRepository;
import com.sales.order.repository.VendorRepository;
import com.sales.order.sync.RemoteUser;
import com.sales.order.sync.SyncAuthClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * BDD-style integration tests for the admin assignment endpoints.
 *
 * <p>Each test follows the Given/When/Then convention via the
 * {@code @DisplayName} annotation, exercising the full
 * {@code JWT filter → @PreAuthorize → controller → service → repository}
 * pipeline against an H2 database with JPA-managed schema.
 */
@SpringBootTest(
        classes = OrderServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "jwt.secret=test-secret-test-secret-test-secret-32+bytes",
        "jwt.issuer=hielo-sync",
        "jwt.audience=hielo-order"
})
class AssignmentBDDTest {

    private static final String TEST_SECRET = "test-secret-test-secret-test-secret-32+bytes"; // 41 bytes

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper objectMapper;
    @Autowired VendorRepository vendorRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired VendorClientAssignmentRepository assignmentRepository;

    /**
     * Mock the cross-DB {@link SyncAuthClient} so {@code VendorRepository.save}
     * can complete its forward integrity check without hitting a real
     * sync-service.
     */
    @MockBean SyncAuthClient syncAuthClient;

    @BeforeEach
    void configureHttp() {
        var apache = new HttpComponentsClientHttpRequestFactory();
        http.getRestTemplate().setRequestFactory(new BufferingClientHttpRequestFactory(apache));
        when(syncAuthClient.getUserById(any(UUID.class)))
                .thenReturn(java.util.Optional.of(new RemoteUser(
                        UUID.randomUUID(), "vendor@example.com", false, null)));
        // Clean slate per test (H2 is in-memory).
        assignmentRepository.deleteAll();
        vendorRepository.deleteAll();
        clientRepository.deleteAll();
    }

    // --- fixtures -------------------------------------------------------

    private Vendor seedVendor(String displayName, boolean active) {
        Vendor v = new Vendor();
        v.setUserId(UUID.randomUUID());
        v.setDisplayName(displayName);
        v.setEmployeeCode("EMP-" + displayName.toUpperCase().replace(' ', '-'));
        v.setActive(active);
        v.setDeletedAt(null);
        return ((com.sales.order.repository.VendorRepositoryCustom) vendorRepository).save(v);
    }

    private Client seedClient(String name) {
        Client c = new Client();
        c.setName(name);
        c.setTaxId("TAX-" + UUID.randomUUID().toString().substring(0, 8));
        c.setAddress("Av. Test 123");
        c.setPhone("+5491100000000");
        c.setActive(true);
        c.setDeletedAt(null);
        return clientRepository.save(c);
    }

    private void seedAssignment(Vendor vendor, Client client) {
        com.sales.order.model.VendorClientAssignment a = new com.sales.order.model.VendorClientAssignment();
        a.setVendorId(vendor.getId());
        a.setClientId(client.getId());
        a.activateFrom(Instant.now());
        assignmentRepository.save(a);
    }

    private String adminToken() throws Exception {
        return mintToken(List.of("admin"));
    }

    private String mintToken(List<String> roles) throws Exception {
        JWTClaimsSet c = new JWTClaimsSet.Builder()
                .subject(UUID.randomUUID().toString())
                .issuer("hielo-sync")
                .audience("hielo-order")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("roles", roles)
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), c);
        jwt.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private HttpHeaders bearerHeaders(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        return h;
    }

    /** Bearer auth + JSON content type for PATCH/POST requests with a body. */
    private HttpHeaders patchJsonHeaders(String token) {
        HttpHeaders h = bearerHeaders(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // --- GET BDD scenarios ----------------------------------------------

    @Test
    @DisplayName("BDD: dado un preventista con 2 clientes asignados, cuando el admin consulta su cartera, "
            + "entonces el endpoint devuelve el vendor con la lista de 2 clientes")
    void given_vendor_with_two_clients_when_admin_fetches_cartera_then_returns_vendor_with_clients()
            throws Exception {
        // Given: vendor con 2 clientes asignados
        Vendor vendor = seedVendor("Roberto Sosa", true);
        Client c1 = seedClient("Maxikiosco 1");
        Client c2 = seedClient("Maxikiosco 2");
        seedAssignment(vendor, c1);
        seedAssignment(vendor, c2);

        // When: GET /vendors/{id}/clients
        String token = adminToken();
        HttpEntity<Void> req = new HttpEntity<>(bearerHeaders(token));
        ResponseEntity<String> resp = http.exchange(
                url("/api/v1/admin/vendors/" + vendor.getId() + "/clients"),
                HttpMethod.GET, req, String.class);

        // Then: 200 con vendor + 2 clientes
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.get("vendorId").asText()).isEqualTo(vendor.getId().toString());
        assertThat(json.get("displayName").asText()).isEqualTo("Roberto Sosa");
        assertThat(json.get("clientCount").asInt()).isEqualTo(2);
        assertThat(json.get("clients")).hasSize(2);

        var clientIds = new java.util.HashSet<String>();
        for (JsonNode n : json.get("clients")) clientIds.add(n.get("clientId").asText());
        assertThat(clientIds).containsExactlyInAnyOrder(c1.getId().toString(), c2.getId().toString());
    }

    @Test
    @DisplayName("BDD: dado un vendor con 2 clientes y un tercero sin asignar, "
            + "cuando el admin consulta huerfanos, entonces ve solo el tercero")
    void given_mix_of_assigned_and_unassigned_when_admin_queries_orphans_then_returns_only_orphans()
            throws Exception {
        // Given: vendor con 2 clientes + 1 huerfano
        Vendor vendor = seedVendor("Carla Mendez", true);
        Client assigned1 = seedClient("Asignado A");
        Client assigned2 = seedClient("Asignado B");
        Client orphan = seedClient("Huerfano C");
        seedAssignment(vendor, assigned1);
        seedAssignment(vendor, assigned2);

        // When: GET /clients/unassigned
        String token = adminToken();
        HttpEntity<Void> req = new HttpEntity<>(bearerHeaders(token));
        ResponseEntity<String> resp = http.exchange(
                url("/api/v1/admin/clients/unassigned"),
                HttpMethod.GET, req, String.class);

        // Then: 200 con solo el huerfano
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.isArray()).isTrue();
        assertThat(json).hasSize(1);
        assertThat(json.get(0).get("clientId").asText()).isEqualTo(orphan.getId().toString());
    }

    @Test
    @DisplayName("BDD: sin vendors activos, cuando el admin lista vendors, entonces recibe lista vacia")
    void given_no_vendors_when_admin_lists_vendors_then_returns_empty_list() throws Exception {
        // Given: DB vacia (sin vendors)
        String token = adminToken();
        HttpEntity<Void> req = new HttpEntity<>(bearerHeaders(token));

        // When
        ResponseEntity<String> resp = http.exchange(
                url("/api/v1/admin/vendors"),
                HttpMethod.GET, req, String.class);

        // Then
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.isArray()).isTrue();
        assertThat(json).isEmpty();
    }

    @Test
    @DisplayName("BDD: con 2 vendors activos y 1 inactivo, cuando el admin lista vendors, "
            + "entonces solo ve los activos")
    void given_mix_active_inactive_vendors_when_admin_lists_then_returns_only_active() throws Exception {
        // Given
        Vendor active = seedVendor("Ana Activa", true);
        Vendor inactive = seedVendor("Pedro Inactivo", false);

        String token = adminToken();
        HttpEntity<Void> req = new HttpEntity<>(bearerHeaders(token));

        // When
        ResponseEntity<String> resp = http.exchange(
                url("/api/v1/admin/vendors"),
                HttpMethod.GET, req, String.class);

        // Then
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json).hasSize(1);
        assertThat(json.get(0).get("id").asText()).isEqualTo(active.getId().toString());
        assertThat(json.get(0).get("displayName").asText()).isEqualTo("Ana Activa");
        assertThat(json.get(0).get("active").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("BDD: dado un request sin Authorization header, cuando consulta un endpoint admin, "
            + "entonces recibe 401 Unauthorized")
    void given_no_token_when_querying_admin_endpoint_then_returns_401() throws Exception {
        // Given: seed data + NO Authorization header
        seedVendor("Carla Mendez", true);

        // When
        ResponseEntity<String> resp = http.exchange(
                url("/api/v1/admin/vendors"),
                HttpMethod.GET, HttpEntity.EMPTY, String.class);

        // Then: 401 Unauthorized (canonical 'token_expired' body per SecurityConfig)
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("token_expired");
    }

    // --- PATCH BDD scenarios --------------------------------------------

    @Test
    @DisplayName("BDD: dado un cliente libre, cuando el admin lo asigna via PATCH /assign, "
            + "entonces el endpoint responde 200 y la asignacion queda persistida")
    void given_unassigned_client_when_admin_assigns_via_patch_then_persists() throws Exception {
        // Given: cliente libre + vendor activo
        Client client = seedClient("Heladeria Nueva");
        Vendor vendor = seedVendor("Lucia Activa", true);

        // When: PATCH /clients/{id}/assign con body JSON
        String body = "{\"vendorId\":\"" + vendor.getId() + "\"}";
        HttpEntity<String> req = new HttpEntity<>(body, patchJsonHeaders(adminToken()));
        ResponseEntity<String> resp = http.exchange(
                url("/api/v1/admin/clients/" + client.getId() + "/assign"),
                HttpMethod.PATCH, req, String.class);

        // Then: 200 con la asignacion correcta
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.get("clientId").asText()).isEqualTo(client.getId().toString());
        assertThat(json.get("assignedVendorId").asText()).isEqualTo(vendor.getId().toString());
        assertThat(json.get("assignmentId").asText()).isNotBlank();

        // And: la asignacion esta persistida en la DB
        var active = assignmentRepository.findActiveByClientId(client.getId());
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getVendorId()).isEqualTo(vendor.getId());
    }

    @Test
    @DisplayName("BDD: dado un cliente ya asignado a vendor A, cuando el admin lo reasigna a vendor B via PATCH, "
            + "entonces la asignacion de A se cierra y la de B queda activa")
    void given_assigned_client_when_admin_reassigns_via_patch_then_prior_closed_new_active()
            throws Exception {
        // Given: cliente asignado a vendor A
        Client client = seedClient("Kiosco Centro");
        Vendor vendorA = seedVendor("Ana Lopez", true);
        Vendor vendorB = seedVendor("Pedro Ruiz", true);
        seedAssignment(vendorA, client);

        // When: reasignar a vendor B
        String body = "{\"vendorId\":\"" + vendorB.getId() + "\"}";
        HttpEntity<String> req = new HttpEntity<>(body, patchJsonHeaders(adminToken()));
        ResponseEntity<String> resp = http.exchange(
                url("/api/v1/admin/clients/" + client.getId() + "/assign"),
                HttpMethod.PATCH, req, String.class);

        // Then: 200 OK
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(resp.getBody()).get("assignedVendorId").asText())
                .isEqualTo(vendorB.getId().toString());

        // And: A esta cerrada, B esta activa
        var allAssignments = assignmentRepository.findAll().stream()
                .filter(a -> a.getClientId().equals(client.getId()))
                .toList();
        assertThat(allAssignments).hasSize(2);
        var active = allAssignments.stream()
                .filter(com.sales.order.model.VendorClientAssignment::isActive).toList();
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getVendorId()).isEqualTo(vendorB.getId());
    }

    @Test
    @DisplayName("BDD: dado un cliente asignado, cuando el admin lo desasigna via PATCH /unassign, "
            + "entonces la asignacion queda cerrada y el cliente aparece en huerfanos")
    void given_assigned_client_when_admin_unassigns_via_patch_then_assignment_closed()
            throws Exception {
        // Given: cliente asignado
        Client client = seedClient("Almacen Oeste");
        Vendor vendor = seedVendor("Lucia Fernandez", true);
        seedAssignment(vendor, client);

        // When: PATCH /clients/{id}/unassign (sin body)
        HttpEntity<Void> req = new HttpEntity<>(patchJsonHeaders(adminToken()));
        ResponseEntity<String> resp = http.exchange(
                url("/api/v1/admin/clients/" + client.getId() + "/unassign"),
                HttpMethod.PATCH, req, String.class);

        // Then: 200 OK con assignmentId null
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
            // JSON nulls come back as NullNode (not Java null)
        assertThat(json.get("assignedVendorId").isNull()).isTrue();
        assertThat(json.get("assignmentId").isNull()).isTrue();

        // And: no quedan assignments activas para este cliente
        assertThat(assignmentRepository.findActiveByClientId(client.getId())).isEmpty();
    }
}