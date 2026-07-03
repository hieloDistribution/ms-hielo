package com.sales.sync.auth.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.BufferingClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test for S-12: the full Spring Boot context boots with all beans
 * wired, the embedded server is reachable, and {@code GET /actuator/health}
 * returns 200 with status UP.
 */
@SpringBootTest(
        classes = com.sales.sync.SyncServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class FullAppSmokeIT extends AbstractPostgresIT {

    @Autowired TestRestTemplate http;
    @LocalServerPort int port;

    @Test
    void context_loads_and_health_endpoint_responds_200() {
        var apache = new HttpComponentsClientHttpRequestFactory();
        http.getRestTemplate().setRequestFactory(new BufferingClientHttpRequestFactory(apache));

        ResponseEntity<String> resp = http.getForEntity(
                "http://localhost:" + port + "/actuator/health", String.class);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).contains("UP");
    }
}
