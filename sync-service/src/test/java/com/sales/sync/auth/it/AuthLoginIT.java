package com.sales.sync.auth.it;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sales.sync.auth.dto.LoginRequest;
import com.sales.sync.auth.model.RefreshToken;
import com.sales.sync.auth.model.User;
import com.sales.sync.auth.repository.RefreshTokenRepository;
import com.sales.sync.auth.repository.UserRepository;
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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = com.sales.sync.SyncServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class AuthLoginIT extends AbstractPostgresIT {

    @Autowired
    void configureHttp(TestRestTemplate httpTemplate) {
        // Use Apache HttpClient 5 to avoid the JDK HttpClient's auth-retry
        // behaviour on 401 responses.
        var apache = new HttpComponentsClientHttpRequestFactory();
        var buffered = new BufferingClientHttpRequestFactory(apache);
        httpTemplate.getRestTemplate().setRequestFactory(buffered);
    }

    @LocalServerPort int port;
    @Autowired UserRepository users;
    @Autowired RefreshTokenRepository tokens;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired ObjectMapper objectMapper;
    @Autowired TestRestTemplate http;

    @BeforeEach
    void setUp() {
        tokens.deleteAll();
        users.deleteAll();
    }

    @AfterEach
    void clean() {
        tokens.deleteAll();
        users.deleteAll();
    }

    private void seedUser(String email, String rawPassword, boolean locked) {
        User u = new User();
        u.setEmail(email);
        u.setPasswordHash(passwordEncoder.encode(rawPassword));
        u.setLocked(locked);
        users.save(u);
    }

    @Test
    void login_happy_path_returns_200_with_tokens_and_persists_one_refresh_row() {
        seedUser("alice@example.com", "correctPassword123", false);

        ResponseEntity<String> resp = http.postForEntity(
                "http://localhost:" + port + "/api/v1/auth/login",
                new LoginRequest("alice@example.com", "correctPassword123"),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode body = readTree(resp.getBody());
        assertThat(body.get("access_token").asText()).isNotBlank();
        assertThat(body.get("refresh_token").asText()).isNotBlank();
        assertThat(body.get("expires_in").asLong()).isEqualTo(900L);

        List<RefreshToken> rows = tokens.findAll();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).isRevoked()).isFalse();
        assertThat(rows.get(0).getTokenFamily()).isNotNull();
    }

    @Test
    void login_wrong_password_returns_401_invalid_credentials_with_no_refresh_row() {
        seedUser("alice@example.com", "correctPassword123", false);

        ResponseEntity<String> resp = http.postForEntity(
                "http://localhost:" + port + "/api/v1/auth/login",
                new LoginRequest("alice@example.com", "wrongPassword!!"),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(readTree(resp.getBody()).get("error").asText())
                .isEqualTo("invalid_credentials");
        assertThat(tokens.findAll()).isEmpty();
    }

    @Test
    void login_locked_account_returns_423_account_locked() {
        seedUser("bob@example.com", "correctPassword123", true);

        ResponseEntity<String> resp = http.postForEntity(
                "http://localhost:" + port + "/api/v1/auth/login",
                new LoginRequest("bob@example.com", "correctPassword123"),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(readTree(resp.getBody()).get("error").asText())
                .isEqualTo("account_locked");
        assertThat(tokens.findAll()).isEmpty();
    }

    @Test
    void login_bad_email_format_returns_400_invalid_request() {
        ResponseEntity<String> resp = http.postForEntity(
                "http://localhost:" + port + "/api/v1/auth/login",
                Map.of("email", "not-an-email", "password", "correctPassword123"),
                String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(readTree(resp.getBody()).get("error").asText())
                .isEqualTo("invalid_request");
    }

    @Test
    void login_subsequent_login_joins_active_family() {
        seedUser("alice@example.com", "correctPassword123", false);

        ResponseEntity<String> first = http.postForEntity(
                "http://localhost:" + port + "/api/v1/auth/login",
                new LoginRequest("alice@example.com", "correctPassword123"),
                String.class);
        var firstFamily = tokens.findAll().get(0).getTokenFamily();

        ResponseEntity<String> second = http.postForEntity(
                "http://localhost:" + port + "/api/v1/auth/login",
                new LoginRequest("alice@example.com", "correctPassword123"),
                String.class);

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        var families = tokens.findAll().stream().map(RefreshToken::getTokenFamily).toList();
        assertThat(families).hasSize(2);
        assertThat(families).containsOnly(firstFamily);
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            throw new RuntimeException("Cannot parse JSON: " + body, e);
        }
    }
}
