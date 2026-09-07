// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.MigrationException;
import dev.vertique.db.MigrationResult;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the real Flyway path against a PostgreSQL container: MIGRATE applies and re-applies, VALIDATE
 * passes after MIGRATE and fails before it, the credential and URL fallback to {@link DbPoolConfig}
 * works, and every {@link FlywayException} is wrapped as a {@link MigrationException}.
 *
 * <p>Each test provisions its own database on the shared container so scenarios cannot bleed into
 * each other.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
@DisplayName("FlywayMigrationRunner against PostgreSQL")
class FlywayMigrationRunnerIT {

    private static final String IMAGE = "postgres:16-alpine";
    private static final String GOOD = "classpath:db/it-migration";
    private static final String BROKEN = "classpath:db/it-broken";

    private static PostgreSQLContainer postgres;
    private static int databaseCounter;

    @BeforeAll
    static void startContainer() {
        postgres = new PostgreSQLContainer(IMAGE);
        postgres.start();
    }

    @AfterAll
    static void stopContainer() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    // --- fixtures ---

    /** Creates a fresh database on the shared server and returns its name. */
    private static String freshDatabase() throws Exception {
        String name = "flyway_it_" + (++databaseCounter);
        try (Connection admin = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return name;
    }

    private static String jdbcUrl(String database) {
        return "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
    }

    private static DbPoolConfig poolConfig(String database) {
        return DbPoolConfig.builder()
                .host(postgres.getHost())
                .port(postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
                .database(database)
                .user(postgres.getUsername())
                .password(postgres.getPassword())
                .build();
    }

    private static FlywayMigrationRunner runner(FlywayConfig flywayConfig, String database) {
        return new FlywayMigrationRunner(flywayConfig, poolConfig(database));
    }

    /** A pool config that cannot authenticate, so a run can only succeed through the Flyway-level values. */
    private static DbPoolConfig unusablePoolConfig(String database) {
        return DbPoolConfig.builder()
                .host(postgres.getHost())
                .port(postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
                .database(database)
                .user(postgres.getUsername())
                .password("wrong-" + postgres.getPassword())
                .build();
    }

    private static int countRows(String database, String table) throws Exception {
        try (Connection connection =
                        DriverManager.getConnection(jdbcUrl(database), postgres.getUsername(), postgres.getPassword());
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    // --- MIGRATE ---

    @Test
    @DisplayName(
            "MIGRATE with Flyway-level credentials applies once and wins over the pool config; a re-run is a no-op")
    void migrate_appliesOnceAndReportsVersion(Vertx vertx, VertxTestContext ctx) throws Exception {
        String database = freshDatabase();
        FlywayConfig config = FlywayConfig.builder()
                .mode(FlywayMode.MIGRATE)
                .jdbcUrl(jdbcUrl(database))
                .user(postgres.getUsername())
                .password(postgres.getPassword())
                .locations(List.of(GOOD))
                .build();
        // The pool config cannot authenticate, so success proves the Flyway-level values take precedence.
        FlywayMigrationRunner runner = new FlywayMigrationRunner(config, unusablePoolConfig(database));

        runner.migrate(vertx)
                .compose(first -> {
                    ctx.verify(() -> {
                        assertEquals(new MigrationResult(1, "1"), first);
                        assertEquals(1, countRows(database, "flyway_it_marker"));
                    });
                    return runner.migrate(vertx);
                })
                .onComplete(ctx.succeeding(second -> ctx.verify(() -> {
                    assertEquals(0, second.migrationsApplied(), "a second MIGRATE applies nothing");
                    assertNull(
                            second.targetVersion(), "Flyway reports a target version only when it applied something");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("MIGRATE falls back to the pool config for URL, user, and password")
    void migrate_fallsBackToPoolConfig(Vertx vertx, VertxTestContext ctx) throws Exception {
        String database = freshDatabase();
        FlywayConfig config = FlywayConfig.builder()
                .mode(FlywayMode.MIGRATE)
                .locations(List.of(GOOD))
                .build(); // no jdbcUrl, user, or password

        runner(config, database)
                .migrate(vertx)
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertEquals(1, result.migrationsApplied());
                    assertEquals(1, countRows(database, "flyway_it_marker"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("a failing migration is wrapped as MigrationException with the FlywayException cause")
    void migrate_wrapsFlywayFailure(Vertx vertx, VertxTestContext ctx) throws Exception {
        String database = freshDatabase();
        FlywayConfig config = FlywayConfig.builder()
                .mode(FlywayMode.MIGRATE)
                .locations(List.of(BROKEN))
                .build();

        runner(config, database)
                .migrate(vertx)
                .onComplete(ctx.failing(failure -> ctx.verify(() -> {
                    MigrationException wrapped = assertInstanceOf(MigrationException.class, failure);
                    assertTrue(wrapped.getMessage().startsWith("Migration failed: "), wrapped.getMessage());
                    assertInstanceOf(FlywayException.class, wrapped.getCause());
                    ctx.completeNow();
                })));
    }

    // --- VALIDATE ---

    @Test
    @DisplayName("VALIDATE passes after MIGRATE and applies nothing")
    void validate_passesAfterMigrate(Vertx vertx, VertxTestContext ctx) throws Exception {
        String database = freshDatabase();
        FlywayConfig migrate = FlywayConfig.builder()
                .mode(FlywayMode.MIGRATE)
                .locations(List.of(GOOD))
                .build();
        FlywayConfig validate = FlywayConfig.builder()
                .mode(FlywayMode.VALIDATE)
                .locations(List.of(GOOD))
                .build();

        runner(migrate, database)
                .migrate(vertx)
                .compose(ignored -> runner(validate, database).migrate(vertx))
                .onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                    assertEquals(0, result.migrationsApplied());
                    assertNull(result.targetVersion());
                    assertEquals(1, countRows(database, "flyway_it_marker"), "VALIDATE never applies");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("VALIDATE on an unmigrated database fails as MigrationException with the FlywayException cause")
    void validate_failsBeforeMigrate(Vertx vertx, VertxTestContext ctx) throws Exception {
        String database = freshDatabase();
        FlywayConfig validate = FlywayConfig.builder()
                .mode(FlywayMode.VALIDATE)
                .locations(List.of(GOOD))
                .build();

        runner(validate, database)
                .migrate(vertx)
                .onComplete(ctx.failing(failure -> ctx.verify(() -> {
                    MigrationException wrapped = assertInstanceOf(MigrationException.class, failure);
                    assertTrue(wrapped.getMessage().startsWith("Schema validation failed: "), wrapped.getMessage());
                    assertInstanceOf(FlywayException.class, wrapped.getCause());
                    ctx.completeNow();
                })));
    }
}
