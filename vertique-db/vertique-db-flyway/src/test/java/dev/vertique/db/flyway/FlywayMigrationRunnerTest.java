// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies {@link FlywayMigrationRunner} behavior for DISABLED mode (fast path) and the
 * SSL-aware JDBC URL fallback logic exercised via {@code buildFallbackJdbcUrl}.
 */
@ExtendWith(VertxExtension.class)
class FlywayMigrationRunnerTest {

    // --- DISABLED mode ---

    @Nested
    @DisplayName("DISABLED mode")
    class DisabledMode {

        @Test
        @DisplayName("should return zero migrations immediately without a Flyway connection")
        void disabledModeShouldReturnImmediately(Vertx vertx, VertxTestContext ctx) {
            FlywayConfig flywayConfig =
                    FlywayConfig.builder().mode(FlywayMode.DISABLED).build();
            DbPoolConfig dbPoolConfig = DbPoolConfig.builder().build();

            FlywayMigrationRunner runner = new FlywayMigrationRunner(flywayConfig, dbPoolConfig);
            runner.migrate(vertx).onComplete(ctx.succeeding(result -> {
                ctx.verify(() -> {
                    assertEquals(0, result.migrationsApplied());
                    assertNull(result.targetVersion());
                });
                ctx.completeNow();
            }));
        }
    }

    // --- JDBC URL fallback ---

    @Nested
    @DisplayName("buildFallbackJdbcUrl — SSL-aware JDBC URL construction")
    class JdbcUrlFallback {

        private FlywayMigrationRunner runnerWith(DbPoolConfig poolConfig) {
            FlywayConfig flywayConfig = FlywayConfig.builder().build();
            return new FlywayMigrationRunner(flywayConfig, poolConfig);
        }

        @Test
        @DisplayName("should build plain URL when sslMode is DISABLE")
        void shouldBuildPlainUrlWhenSslDisabled() {
            DbPoolConfig pool = DbPoolConfig.builder()
                    .host("db.example.com")
                    .port(5432)
                    .database("mydb")
                    .sslMode("DISABLE")
                    .build();
            FlywayMigrationRunner runner = runnerWith(pool);

            String url = runner.buildFallbackJdbcUrl("db.example.com", 5432, "mydb");

            assertEquals("jdbc:postgresql://db.example.com:5432/mydb", url);
        }

        @Test
        @DisplayName("should append sslmode parameter when sslMode is REQUIRE")
        void shouldAppendSslModeWhenRequired() {
            DbPoolConfig pool = DbPoolConfig.builder()
                    .host("db.example.com")
                    .port(5432)
                    .database("mydb")
                    .sslMode("REQUIRE")
                    .build();
            FlywayMigrationRunner runner = runnerWith(pool);

            String url = runner.buildFallbackJdbcUrl("db.example.com", 5432, "mydb");

            assertEquals("jdbc:postgresql://db.example.com:5432/mydb?sslmode=require", url);
        }

        @Test
        @DisplayName("should append sslrootcert when trustStorePath is set")
        void shouldAppendSslRootCertWhenTrustStorePathSet() {
            DbPoolConfig pool = DbPoolConfig.builder()
                    .host("db.example.com")
                    .port(5432)
                    .database("mydb")
                    .sslMode("VERIFY_CA")
                    .trustStorePath("/etc/ssl/ca.pem")
                    .build();
            FlywayMigrationRunner runner = runnerWith(pool);

            String url = runner.buildFallbackJdbcUrl("db.example.com", 5432, "mydb");

            assertEquals(
                    "jdbc:postgresql://db.example.com:5432/mydb?sslmode=verify_ca&sslrootcert=/etc/ssl/ca.pem", url);
        }

        @Test
        @DisplayName("should append sslcert and sslkey when certPath and keyPath are set")
        void shouldAppendClientCertAndKeyWhenBothSet() {
            DbPoolConfig pool = DbPoolConfig.builder()
                    .host("db.example.com")
                    .port(5432)
                    .database("mydb")
                    .sslMode("VERIFY_FULL")
                    .certPath("/etc/ssl/client.crt")
                    .keyPath("/etc/ssl/client.key")
                    .build();
            FlywayMigrationRunner runner = runnerWith(pool);

            String url = runner.buildFallbackJdbcUrl("db.example.com", 5432, "mydb");

            assertEquals(
                    "jdbc:postgresql://db.example.com:5432/mydb?sslmode=verify_full"
                            + "&sslcert=/etc/ssl/client.crt&sslkey=/etc/ssl/client.key",
                    url);
        }

        @Test
        @DisplayName("should combine all SSL params when all are set")
        void shouldCombineAllSslParams() {
            DbPoolConfig pool = DbPoolConfig.builder()
                    .host("db.example.com")
                    .port(5432)
                    .database("mydb")
                    .sslMode("VERIFY_FULL")
                    .trustStorePath("/etc/ssl/ca.pem")
                    .certPath("/etc/ssl/client.crt")
                    .keyPath("/etc/ssl/client.key")
                    .build();
            FlywayMigrationRunner runner = runnerWith(pool);

            String url = runner.buildFallbackJdbcUrl("db.example.com", 5432, "mydb");

            assertTrue(url.contains("?sslmode=verify_full"), "should contain sslmode");
            assertTrue(url.contains("&sslrootcert=/etc/ssl/ca.pem"), "should contain sslrootcert");
            assertTrue(url.contains("&sslcert=/etc/ssl/client.crt"), "should contain sslcert");
            assertTrue(url.contains("&sslkey=/etc/ssl/client.key"), "should contain sslkey");
        }

        @Test
        @DisplayName("should lowercase the sslMode value in URL parameter")
        void shouldLowercaseSslModeInUrl() {
            DbPoolConfig pool = DbPoolConfig.builder()
                    .host("localhost")
                    .port(5432)
                    .database("testdb")
                    .sslMode("PREFER")
                    .build();
            FlywayMigrationRunner runner = runnerWith(pool);

            String url = runner.buildFallbackJdbcUrl("localhost", 5432, "testdb");

            assertTrue(url.contains("sslmode=prefer"), "sslMode should be lowercased");
        }
    }
}
