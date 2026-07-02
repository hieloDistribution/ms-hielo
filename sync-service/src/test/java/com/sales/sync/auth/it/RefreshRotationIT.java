package com.sales.sync.auth.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sales.sync.auth.dto.LoginRequest;
import com.sales.sync.auth.dto.RefreshRequest;
import com.sales.sync.auth.model.RefreshToken;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.RefreshTokenRepository;
import com.sales.sync.auth.repository.UserRepository;
import com.sales.sync.auth.security.RefreshTokenCodec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = com.sales.sync.SyncServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class RefreshRotationIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository tokens;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestRestTemplate http;
    @Autowired RefreshTokenCodec refreshTokenCodec;

    @BeforeEach
    void configureHttp() {
        var apache = new HttpComponentsClientHttpRequestFactory();
        var buffered = new BufferingClientHttpRequestFactory(apache);
        http.getRestTemplate().setRequestFactory(buffered);
        tokens.deleteAll();
        users.deleteAll();
    }

    @AfterEach
    void clean() {
        tokens.deleteAll();
        users.deleteAll();
    }

    private void seedUser(String email) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode("correctPassword123"));
        users.save(u);
    }

    private JsonNode post(String path, Object body) {
        ResponseEntity<String> resp = http.postForEntity(
                "http://localhost:" + port + path, body, String.class);
        assertThat(resp.getStatusCode()).isIn(HttpStatus.OK, HttpStatus.UNAUTHORIZED, HttpStatus.LOCKED, HttpStatus.BAD_REQUEST);
        return readTree(resp.getBody());
    }

    @Test
    void refresh_happy_path_rotates_token_and_revokes_old_one() {
        seedUser("alice@example.com");
        var login = post("/api/v1/auth/login",
                new LoginRequest("alice@example.com", "correctPassword123"));
        String originalRefresh = login.get("refresh_token").asText();
        UUID originalRowId = tokens.findAll().get(0).getId();

        var refreshResp = post("/api/v1/auth/refresh",
                new RefreshRequest(originalRefresh));

        assertThat(refreshResp.get("access_token").asText()).isNotBlank();
        assertThat(refreshResp.get("refresh_token").asText())
                .isNotEqualTo(originalRefresh);

        var rows = tokens.findAll();
        assertThat(rows).hasSize(2);
        var oldRow = rows.stream().filter(r -> r.getId().equals(originalRowId)).findFirst().orElseThrow();
        var newRow = rows.stream().filter(r -> !r.getId().equals(originalRowId)).findFirst().orElseThrow();
        assertThat(oldRow.isRevoked()).isTrue();
        assertThat(newRow.isRevoked()).isFalse();
        assertThat(oldRow.getTokenFamily()).isEqualTo(newRow.getTokenFamily());
    }

    @Test
    void refresh_replay_burns_the_entire_family() {
        seedUser("alice@example.com");
        var login = post("/api/v1/auth/login",
                new LoginRequest("alice@example.com", "correctPassword123"));
        String refresh = login.get("refresh_token").asText();

        // First refresh: rotates the token.
        post("/api/v1/auth/refresh", new RefreshRequest(refresh));

        // Replay the SAME presented refresh token after it has been rotated.
        ResponseEntity<String> resp = http.postForEntity(
                "http://localhost:" + port + "/api/v1/auth/refresh",
                new RefreshRequest(refresh),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(readTree(resp.getBody()).get("error").asText())
                .isEqualTo("token_revoked");

        // After burn, every row in the family is revoked.
        assertThat(tokens.findAll()).allMatch(RefreshToken::isRevoked);
    }

    @Test
    void refresh_expired_token_returns_401_and_does_not_burn_family() {
        seedUser("alice@example.com");
        seedExpiredRow("alice@example.com", UUID.randomUUID());

        String expiredPlaintext = UUID.randomUUID().toString();
        String expiredHash = RefreshTokenCodec.sha256Hex(expiredPlaintext);

        // Replace the seeded row's hash with a known hash so we can present it.
        var row = tokens.findAll().get(0);
        row.setTokenHash(expiredHash);
        tokens.save(row);

        ResponseEntity<String> resp = http.postForEntity(
                "http://localhost:" + port + "/api/v1/auth/refresh",
                new RefreshRequest(expiredPlaintext),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(readTree(resp.getBody()).get("error").asText())
                .isEqualTo("token_expired");

        // Family NOT burned — the row stays revoked=false so a future login
        // can keep using the same family.
        assertThat(tokens.findAll().get(0).isRevoked()).isFalse();
    }

    private void seedExpiredRow(String email, UUID family) {
        User u = users.findByEmail(email).orElseThrow();
        RefreshToken rt = new RefreshToken();
        rt.setUserId(u.getId());
        rt.setTokenFamily(family);
        rt.setTokenHash("0".repeat(64));
        rt.setExpiresAt(java.time.Instant.now().minusSeconds(60));
        rt.setRevoked(false);
        tokens.save(rt);
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
