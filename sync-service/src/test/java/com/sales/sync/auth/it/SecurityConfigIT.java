package com.sales.sync.auth.it;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sales.sync.auth.dto.LoginRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class SecurityConfigIT extends AbstractPostgresIT {

    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired ObjectMapper objectMapper;

    @Test
    void actuator_health_returns_200_with_up_status() {
        ResponseEntity<String> resp = http.getForEntity(
                "http://localhost:" + port + "/actuator/health", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("\"status\"");
    }

    @Test
    void protected_endpoint_without_token_returns_401_token_expired_json() throws Exception {
        ResponseEntity<String> resp = http.getForEntity(
                "http://localhost:" + port + "/api/v1/not-a-real-path", String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(resp.getBody()).isEqualTo("{\"error\":\"token_expired\"}");
    }

    @Test
    void auth_login_endpoint_is_permitAll_and_returns_400_when_body_invalid() {
        ResponseEntity<String> resp = http.postForEntity(
                "http://localhost:" + port + "/api/v1/auth/login",
                new LoginRequest("", ""), String.class);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).contains("invalid_request");
    }
}
