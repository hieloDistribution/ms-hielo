package com.sales.sync.auth.it;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RED-first integration test for the V1 Flyway migration. The Spring Boot
 * context starts and Flyway applies every {@code V*.sql} under
 * {@code classpath:db/migration}; the test then asserts the resulting
 * schema matches {@code design.md §4 — Database schema}.
 *
 * <p>If {@code V1__create_users_and_refresh_tokens.sql} is missing,
 * Spring Boot fails the context start with a Flyway error and this test
 * fails with {@code ApplicationContextException}.
 */
class FlywayMigrationsIT extends AbstractPostgresIT {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void v1_creates_users_and_refresh_tokens_tables_with_documented_columns() {
        Set<String> userCols = columnsOf("users");
        assertThat(userCols).contains(
                "id", "email", "password_hash", "vendor_id", "locked",
                "created_at", "updated_at");

        Set<String> tokenCols = columnsOf("refresh_tokens");
        assertThat(tokenCols).contains(
                "id", "user_id", "token_family", "token_hash",
                "expires_at", "revoked", "created_at");

                Set<String> indexes = indexNamesOf("refresh_tokens");
        if (databaseProductName().contains("PostgreSQL")) {
            assertThat(indexes).contains(
                    "ix_refresh_tokens_user_id",
                    "ix_refresh_tokens_family",
                    "ix_refresh_tokens_revoked_exp");
        } else {
            assertThat(indexes).isEmpty();
        }
    }

        private Set<String> indexNamesOf(String table) {
                return jdbc.execute((Connection connection) -> {
                        Set<String> indexes = new HashSet<>();
                        DatabaseMetaData metaData = connection.getMetaData();
                        try (ResultSet resultSet = metaData.getIndexInfo(null, null, table.toUpperCase(), false, false)) {
                                while (resultSet.next()) {
                                        String indexName = resultSet.getString("INDEX_NAME");
                                        if (indexName != null) {
                                                indexes.add(indexName);
                                        }
                                }
                        }
                        return indexes;
                });
        }

        private String databaseProductName() {
                return jdbc.execute((Connection connection) -> connection.getMetaData().getDatabaseProductName());
        }

    private Set<String> columnsOf(String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND table_name = ?",
                String.class, table
        ).stream().collect(Collectors.toSet());
    }
}
