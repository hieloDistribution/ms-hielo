package com.sales.order;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest(classes = OrderServiceApplication.class)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "jwt.secret=test-secret-test-secret-test-secret-32+bytes",
        "jwt.issuer=hielo-sync",
        "jwt.audience=hielo-order"
})
class ApplicationContextSmokeIT {

    @Test
    void context_loads_with_auth_foundation_classes() {
        // Spring Boot will fail to start the context if any required bean
        // (JwtProperties, JwtSecretValidator, JwtService, JwtAuthenticationFilter,
        // SecurityConfig, VendorContext) is missing or mis-wired.
    }
}
