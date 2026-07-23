// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.MigrationException;
import dev.vertique.db.MigrationResult;
import dev.vertique.db.MigrationRunner;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.Locale;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.output.MigrateResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@link MigrationRunner} implementation backed by Flyway.
 *
 * <p>Behavior depends on the configured {@link FlywayMode}:
 *
 * <ul>
 *   <li>{@link FlywayMode#MIGRATE} — runs {@code flyway.migrate()}
 *   <li>{@link FlywayMode#VALIDATE} — runs {@code flyway.validate()}, fails on mismatch
 *   <li>{@link FlywayMode#DISABLED} — returns immediately with zero migrations
 * </ul>
 *
 * <p>User/password resolution: uses {@link FlywayConfig#user()} if set, otherwise falls back to
 * {@link DbPoolConfig#user()}.
 *
 * <p>JDBC URL resolution: uses {@link FlywayConfig#jdbcUrl()} if set, otherwise constructs one
 * from the pool config's host, port, and database fields. When SSL is active (i.e.,
 * {@link DbPoolConfig#sslMode()} is not {@code DISABLE}), the SSL mode and certificate paths are
 * appended as query parameters.
 *
 * <p>Any {@link FlywayException} thrown during migration or validation is wrapped in a
 * {@link MigrationException} to prevent vendor types from leaking into calling code.
 */
@Singleton
public class FlywayMigrationRunner implements MigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationRunner.class);

    private final FlywayConfig flywayConfig;
    private final DbPoolConfig dbPoolConfig;

    /**
     * Creates a new Flyway migration runner.
     *
     * @param flywayConfig the Flyway configuration
     * @param dbPoolConfig the database pool configuration (for credential and JDBC URL fallback)
     */
    @Inject
    public FlywayMigrationRunner(FlywayConfig flywayConfig, DbPoolConfig dbPoolConfig) {
        this.flywayConfig = flywayConfig;
        this.dbPoolConfig = dbPoolConfig;
    }

    /**
     * Runs schema migrations according to the configured {@link FlywayMode}.
     *
     * <p>Flyway is JDBC-based (blocking), so this method uses {@link Vertx#executeBlocking} to
     * avoid blocking the event loop. Any {@link FlywayException} is wrapped in a
     * {@link MigrationException}.
     *
     * @param vertx the Vert.x instance used for {@code executeBlocking}
     * @return a future with the migration result; completes immediately if mode is
     *         {@link FlywayMode#DISABLED}
     */
    @Override
    public Future<MigrationResult> migrate(Vertx vertx) {
        if (flywayConfig.mode() == FlywayMode.DISABLED) {
            log.info("Flyway migration disabled");
            return Future.succeededFuture(new MigrationResult(0, null));
        }

        return vertx.executeBlocking(() -> {
            Flyway flyway = buildFlyway();

            return switch (flywayConfig.mode()) {
                case MIGRATE -> {
                    log.info("Running Flyway migration");
                    MigrateResult result = runMigrate(flyway);
                    log.info(
                            "Flyway migration complete: {} migrations applied, target version: {}",
                            result.migrationsExecuted,
                            result.targetSchemaVersion);
                    yield new MigrationResult(result.migrationsExecuted, result.targetSchemaVersion);
                }
                case VALIDATE -> {
                    log.info("Validating Flyway schema");
                    runValidate(flyway);
                    log.info("Flyway schema validation passed");
                    yield new MigrationResult(0, null);
                }
                case DISABLED -> new MigrationResult(0, null);
            };
        });
    }

    // --- Flyway execution ---

    /**
     * Runs {@code flyway.migrate()} and wraps any {@link FlywayException} in a
     * {@link MigrationException}.
     *
     * @param flyway the configured Flyway instance
     * @return the result of the migration
     * @throws MigrationException if Flyway throws a {@link FlywayException}
     */
    private MigrateResult runMigrate(Flyway flyway) {
        try {
            return flyway.migrate();
        } catch (FlywayException e) {
            throw new MigrationException("Migration failed: " + e.getMessage(), e);
        }
    }

    /**
     * Runs {@code flyway.validate()} and wraps any {@link FlywayException} in a
     * {@link MigrationException}.
     *
     * @param flyway the configured Flyway instance
     * @throws MigrationException if Flyway throws a {@link FlywayException}
     */
    private void runValidate(Flyway flyway) {
        try {
            flyway.validate();
        } catch (FlywayException e) {
            throw new MigrationException("Schema validation failed: " + e.getMessage(), e);
        }
    }

    // --- Flyway builder ---

    /**
     * Builds a configured {@link Flyway} instance. Resolves credentials from {@link FlywayConfig},
     * falling back to {@link DbPoolConfig} when not set. Wires all expanded config fields including
     * {@code locations}, {@code schemas}, {@code placeholders}, {@code target}, {@code outOfOrder},
     * and {@code cleanDisabled}.
     *
     * @return the configured Flyway instance
     */
    private Flyway buildFlyway() {
        String user = flywayConfig.user() != null ? flywayConfig.user() : dbPoolConfig.user();
        String password = flywayConfig.password() != null ? flywayConfig.password() : dbPoolConfig.password();
        String jdbcUrl = resolveJdbcUrl();

        FluentConfiguration cfg = Flyway.configure()
                .dataSource(jdbcUrl, user, password)
                .locations(flywayConfig.locations().toArray(String[]::new))
                .outOfOrder(flywayConfig.outOfOrder())
                .cleanDisabled(flywayConfig.cleanDisabled())
                .baselineOnMigrate(flywayConfig.baselineOnMigrate())
                .baselineVersion(flywayConfig.baselineVersion())
                .validateOnMigrate(flywayConfig.validateOnMigrate());

        if (flywayConfig.target() != null) {
            cfg.target(flywayConfig.target());
        }
        if (!flywayConfig.schemas().isEmpty()) {
            cfg.schemas(flywayConfig.schemas().toArray(String[]::new));
        }
        if (!flywayConfig.placeholders().isEmpty()) {
            cfg.placeholders(flywayConfig.placeholders());
        }

        return cfg.load();
    }

    // --- JDBC URL resolution ---

    /**
     * Resolves the JDBC URL from Flyway config, falling back to constructing one from the pool
     * config's host, port, and database fields. When SSL is active, appends SSL query parameters.
     *
     * @return the JDBC URL for the migration connection
     * @throws IllegalStateException if neither {@link FlywayConfig#jdbcUrl()} nor
     *                               host + database are configured
     */
    private String resolveJdbcUrl() {
        if (flywayConfig.jdbcUrl() != null && !flywayConfig.jdbcUrl().isBlank()) {
            return flywayConfig.jdbcUrl();
        }
        String host = dbPoolConfig.host();
        String database = dbPoolConfig.database();
        if (host == null || host.isBlank() || database == null || database.isBlank()) {
            throw new IllegalStateException(
                    "Either flyway.jdbcUrl or db.host + db.database must be configured for Flyway migrations");
        }
        int port = dbPoolConfig.port() > 0 ? dbPoolConfig.port() : 5432;
        return buildFallbackJdbcUrl(host, port, database);
    }

    /**
     * Constructs a PostgreSQL JDBC URL from host, port, and database, appending SSL query
     * parameters when the SSL mode is not {@code DISABLE}.
     *
     * @param host     the database host
     * @param port     the database port
     * @param database the database name
     * @return a fully-formed JDBC URL with optional SSL parameters
     */
    String buildFallbackJdbcUrl(String host, int port, String database) {
        StringBuilder url = new StringBuilder("jdbc:postgresql://")
                .append(host)
                .append(":")
                .append(port)
                .append("/")
                .append(database);

        String sslMode = dbPoolConfig.sslMode();
        if (sslMode != null && !"DISABLE".equalsIgnoreCase(sslMode)) {
            url.append("?sslmode=").append(sslMode.toLowerCase(Locale.ROOT));
            if (dbPoolConfig.trustStorePath() != null) {
                url.append("&sslrootcert=").append(dbPoolConfig.trustStorePath());
            }
            if (dbPoolConfig.certPath() != null) {
                url.append("&sslcert=").append(dbPoolConfig.certPath());
            }
            if (dbPoolConfig.keyPath() != null) {
                url.append("&sslkey=").append(dbPoolConfig.keyPath());
            }
        }
        return url.toString();
    }
}
