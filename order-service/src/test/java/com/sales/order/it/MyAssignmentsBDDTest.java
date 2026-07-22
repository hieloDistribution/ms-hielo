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
import com.sales.order.model.VendorClientAssignment;
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
 * BDD integration tests for the preventista self-service endpoint
 * {@code GET /api/v1/me/clients}.
 *
 * <p>Unlike the admin assignment endpoints (which require {@code ROLE_ADMIN}),
 * this endpoint is available to any authenticated user. The preventista's
 * {@code userId} is read from the JWT, the order-service looks up the
 * matching vendor, and returns its active cartera.
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
class MyAssignmentsBDDTest {

    private static final String TEST_SECRET = "test-secret-test-secret-test-secret-32+bytes"; // 41 bytes

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper objectMapper;
    @Autowired VendorRepository vendorRepository;
    @Autowired ClientRepository clientRepository;
    @Autowired VendorClientAssignmentRepository assignmentRepository;
    @MockBean SyncAuthClient syncAuthClient;

    @BeforeEach
    void setupFixtures() {
        var apache = new HttpComponentsClientHttpRequestFactory();
        http.getRestTemplate().setRequestFactory(new BufferingClientHttpRequestFactory(apache));
        when(syncAuthClient.getUserById(any(UUID.class)))
                .thenReturn(java.util.Optional.of(new RemoteUser(
                        UUID.randomUUID(), "v@example.com", false, null)));
        assignmentRepository.deleteAll();
        vendorRepository.deleteAll();
        clientRepository.deleteAll();
    }

    // --- fixtures -------------------------------------------------------

    private Vendor seedVendorWithUserId(UUID userId) {
        Vendor v = new Vendor();
        v.setUserId(userId);
        v.setDisplayName("Preventista " + userId.toString().substring(0, 4));
        v.setEmployeeCode("EMP-" + userId.toString().substring(0, 4));
        v.setActive(true);
        v.setDeletedAt(null);
        return ((com.sales.order.repository.VendorRepositoryCustom) vendorRepository).save(v);
    }

    private Client seedClient(String name) {
        Client c = new Client();
        c.setName(name);
        c.setTaxId("TAX-" + UUID.randomUUID().toString().substring(0, 8));
        c.setAddress("addr");
        c.setPhone("+5491100000000");
        c.setActive(true);
        c.setDeletedAt(null);
        return clientRepository.save(c);
    }

    private void seedAssignment(Vendor vendor, Client client) {
        VendorClientAssignment a = new VendorClientAssignment();
        a.setVendorId(vendor.getId());
        a.setClientId(client.getId());
        a.activateFrom(Instant.now());
        assignmentRepository.save(a);
    }

    private String mintToken(UUID userId, String role) throws Exception {
        JWTClaimsSet c = new JWTClaimsSet.Builder()
                .subject(userId.toString())
                .issuer("hielo-sync")
                .audience("hielo-order")
                .issueTime(new Date())
                .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                .claim("roles", List.of(role))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), c);
        jwt.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
        return jwt.serialize();
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    // --- BDD scenarios --------------------------------------------------

    @Test
    @DisplayName("BDD: dado un preventista con 2 clientes asignados, "
            + "cuando llama a GET /me/clients, entonces ve su cartera de 2 clientes")
    void given_preventista_with_two_clients_when_calls_me_clients_then_returns_two() throws Exception {
        // Given: preventista + 2 clientes asignados
        UUID userId = UUID.randomUUID();
        Vendor vendor = seedVendorWithUserId(userId);
        Client c1 = seedClient("Heladería del Sur");
        Client c2 = seedClient("Kiosco Centro");
        seedAssignment(vendor, c1);
        seedAssignment(vendor, c2);

        // When: el preventista hace GET /me/clients
        String token = mintToken(userId, "repartidor");
        HttpEntity<Void> req = new HttpEntity<>(bearer(token));
        ResponseEntity<String> resp = http.exchange(
                url("/api/v1/me/clients"),
                HttpMethod.GET, req, String.class);

        // Then: 200 con la cartera
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.isArray()).isTrue();
        assertThat(json).hasSize(2);
        var clientIds = new java.util.HashSet<String>();
        for (JsonNode n : json) {
            assertThat(n.get("assignedVendorId").asText()).isEqualTo(vendor.getId().toString());
            assertThat(n.get("assignmentId").asText()).isNotBlank();
            clientIds.add(n.get("clientId").asText());
        }
        assertThat(clientIds).containsExactlyInAnyOrder(
                c1.getId().toString(), c2.getId().toString());
    }

    @Test
    @DisplayName("BDD: dado un usuario que NO es preventista, "
            + "cuando llama a GET /me/clients, entonces recibe lista vacia (200)")
    void given_non_preventista_user_when_calls_me_clients_then_returns_empty_list() throws Exception {
        // Given: un userId que NO tiene vendor en order_db
        UUID userId = UUID.randomUUID();

        // When
        String token = mintToken(userId, "repartidor");
        HttpEntity<Void> req = new HttpEntity<>(bearer(token));
        ResponseEntity<String> resp = http.exchange(
                url("/api/v1/me/clients"),
                HttpMethod.GET, req, String.class);

        // Then: 200 con lista vacia (no 403 ni 404 — el endpoint es
        // self-service y no distingue roles)
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json.isArray()).isTrue();
        assertThat(json).isEmpty();
    }

    @Test
    @DisplayName("BDD: dado un preventista con 0 clientes asignados, "
            + "cuando llama a GET /me/clients, entonces ve lista vacia")
    void given_preventista_without_clients_when_calls_me_clients_then_returns_empty() throws Exception {
        // Given: preventista sin assignments
        UUID userId = UUID.randomUUID();
        seedVendorWithUserId(userId);

        // When
        String token = mintToken(userId, "repartidor");
        HttpEntity<Void> req = new HttpEntity<>(bearer(token));
        ResponseEntity<String> resp = http.exchange(
                url("/api/v1/me/clients"),
                HttpMethod.GET, req, String.class);

        // Then
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(objectMapper.readTree(resp.getBody())).isEmpty();
    }

    @Test
    @DisplayName("BDD: sin Authorization header, cuando llama a GET /me/clients, "
            + "entonces recibe 401 Unauthorized (canonico 'token_expired')")
    void given_no_token_when_calls_me_clients_then_returns_401() {
        // When
        ResponseEntity<String> resp = http.exchange(
                url("/api/v1/me/clients"),
                HttpMethod.GET, HttpEntity.EMPTY, String.class);

        // Then
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("token_expired");
    }

    @Test
    @DisplayName("BDD: con token valido de OTRO preventista (userId diferente), "
            + "cuando llama a GET /me/clients, entonces ve SOLO su cartera, no la del otro")
    void given_other_user_token_when_calls_me_clients_then_returns_only_own_cartera() throws Exception {
        // Given: 2 preventistas con sus respectivas carteras
        UUID juanUserId = UUID.randomUUID();
        UUID pedroUserId = UUID.randomUUID();
        Vendor juan = seedVendorWithUserId(juanUserId);
        Vendor pedro = seedVendorWithUserId(pedroUserId);
        Client clienteJuan = seedClient("Cliente de Juan");
        Client clientePedro = seedClient("Cliente de Pedro");
        seedAssignment(juan, clienteJuan);
        seedAssignment(pedro, clientePedro);

        // When: Juan llama a /me/clients
        String token = mintToken(juanUserId, "repartidor");
        HttpEntity<Void> req = new HttpEntity<>(bearer(token));
        ResponseEntity<String> resp = http.exchange(
                url("/api/v1/me/clients"),
                HttpMethod.GET, req, String.class);

        // Then: 200 con SOLO su cliente
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode json = objectMapper.readTree(resp.getBody());
        assertThat(json).hasSize(1);
        assertThat(json.get(0).get("clientId").asText()).isEqualTo(clienteJuan.getId().toString());
        assertThat(json.get(0).get("assignedVendorId").asText()).isEqualTo(juan.getId().toString());
    }
}