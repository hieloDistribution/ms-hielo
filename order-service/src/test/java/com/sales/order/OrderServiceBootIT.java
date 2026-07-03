package com.sales.order;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
class OrderServiceBootIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;

    private static final String TEST_SECRET = "test-secret-test-secret-test-secret-32+bytes"; // 41 bytes

    private void configureHttp() {
        var apache = new HttpComponentsClientHttpRequestFactory();
        http.getRestTemplate().setRequestFactory(new BufferingClientHttpRequestFactory(apache));
    }

    @Test
    void health_endpoint_is_public() {
        configureHttp();
        ResponseEntity<String> resp = http.getForEntity(
                "http://localhost:" + port + "/actuator/health", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("UP");
    }

    @Test
    void protected_endpoint_without_token_returns_401_token_expired() {
        configureHttp();
        ResponseEntity<String> resp = http.getForEntity(
                "http://localhost:" + port + "/api/v1/orders/catalog", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).contains("token_expired");
    }

    @Test
    void protected_endpoint_with_valid_token_passes_filter() {
        configureHttp();
        UUID userId = UUID.randomUUID();
        UUID vendorId = UUID.randomUUID();
        String token = mintToken(userId, vendorId);

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(token);
        ResponseEntity<String> resp = http.exchange(
                "http://localhost:" + port + "/api/v1/orders/catalog",
                HttpMethod.GET, new HttpEntity<>(h), String.class);

        // What matters: not 401. The legacy pre-existing controller may
        // return 200 (empty list) or 500 if its schema is not bootstrapped
        // yet; both prove the JWT filter accepted the bearer.
        assertThat(resp.getStatusCode()).isNotEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void protected_endpoint_with_tampered_token_returns_401() {
        configureHttp();
        String tampered = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4eHgiLCJleHAiOjk5OTk5OTk5OTl9.bogus_signature";

        HttpHeaders h = new HttpHeaders();
        h.setBearerAuth(tampered);
        ResponseEntity<String> resp = http.exchange(
                "http://localhost:" + port + "/api/v1/orders/catalog",
                HttpMethod.GET, new HttpEntity<>(h), String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String mintToken(UUID userId, UUID vendorId) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(userId.toString())
                    .issuer("hielo-sync")
                    .audience("hielo-order")
                    .issueTime(new Date(System.currentTimeMillis() - 1000))
                    .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                    .jwtID(UUID.randomUUID().toString())
                    .claim("vendor_id", vendorId.toString())
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.HS256).build(),
                    claims);
            jwt.sign(new MACSigner(TEST_SECRET.getBytes(StandardCharsets.UTF_8)));
            return jwt.serialize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
