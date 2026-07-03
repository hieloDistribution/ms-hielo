package com.sales.sync.auth.it;

import com.sales.sync.SyncServiceApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Base for integration tests against the local PostgreSQL brought up via
 * {@code docker-compose.yml}. The {@code application-test.yml} profile
 * points to {@code jdbc:postgresql://localhost:5432/sync_db} by default,
 * overridable through {@code DB_HOST}/{@code DB_PORT}/{@code TEST_DB_NAME}
 * environment variables.
 *
 * <p>Test isolation between {@code *IT} classes is achieved by Spring's
 * application context cache combined with explicit {@code flyway.clean()}
 * in {@code @AfterEach} where needed; this slice keeps the boilerplate
 * minimal because all current {@code *IT} classes can share the same
 * already-migrated schema.
 */
@SpringBootTest(
        classes = SyncServiceApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@ActiveProfiles("test")
public abstract class AbstractPostgresIT {
}
