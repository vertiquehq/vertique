// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.MigrationDefinition;
import dev.vertique.db.MigrationException;
import dev.vertique.db.MigrationResult;
import dev.vertique.db.MigrationRunner;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.sql.DriverManager;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
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
 *
 * <h2>Named instances</h2>
 *
 * <p>When {@link FlywayConfig#instances()} is non-null the runner works in named mode: every wired
 * {@link MigrationDefinition} is one instance with its own locations, history table and version
 * sequence. {@link #instances()} resolves and validates them without opening a database connection;
 * {@link #run(Vertx)} validates them again on a Vert.x worker and runs every enabled instance through
 * native Flyway in dependency order (ties broken by name), stopping at the first failure and reporting
 * every outcome in a {@link MigrationReport}. {@link #migrate(Vertx)} runs the same operation and
 * aggregates the report. Earlier successful instances are never rolled back; a later run continues
 * from each instance's own history.
 *
 * <p>An instance's default and history schema is its {@code db.schemas} entry when the supplied
 * {@link DbSchemas} has one, otherwise the first entry of its managed schema list, otherwise the
 * default schema of a connection opened with the instance's own credential pair. A run resolves that
 * default once, before any instance runs, freezes it for the whole run, binds it as native Flyway's
 * default schema and reports it. Named-mode failures are {@link MigrationRunException}s whose messages
 * never contain SQL, JDBC URLs, credentials or placeholder values and which retain no native cause.
 *
 * <p>Named execution currently supports PostgreSQL destinations only. A named run that migrates first
 * takes a database-wide coordination lock on a dedicated direct connection, waiting at most
 * {@link FlywayConfig#coordinationLockTimeoutMs()}, and holds it until the run ends; a VALIDATE-only
 * run takes no lock. Before any instance writes, the run rejects history collisions in the resolved
 * schemas, a history-less instance whose managed schemas are only partly provisioned, and a
 * conventional single-Flyway history that no instance explicitly keeps as its own. An instance that
 * keeps such a history must first pass native validation against its own migrations. The
 * single-Flyway path takes no lock and runs none of these checks.
 */
@Singleton
public class FlywayMigrationRunner implements MigrationRunner {

    private static final Logger log = LoggerFactory.getLogger(FlywayMigrationRunner.class);

    /** Opens runner-owned connections through the JDBC driver manager. */
    private static final JdbcConnector DRIVER_MANAGER = DriverManager::getConnection;

    private final FlywayConfig flywayConfig;
    private final DbPoolConfig dbPoolConfig;
    private final NamedInstanceResolver resolver;

    /**
     * Retained connector of the package-private constructor. The runner opens no connection with it
     * directly: the database support opens the connections it owns through the connector its selector
     * was created with.
     */
    @SuppressWarnings("unused")
    private final JdbcConnector jdbcConnector;

    /**
     * Creates a new Flyway migration runner.
     *
     * @param flywayConfig the Flyway configuration
     * @param dbPoolConfig the database pool configuration (for credential and JDBC URL fallback)
     */
    @Inject
    public FlywayMigrationRunner(FlywayConfig flywayConfig, DbPoolConfig dbPoolConfig) {
        this(flywayConfig, dbPoolConfig, Set.of(), DbSchemas.empty());
    }

    /**
     * Creates a runner that can execute named instances.
     *
     * <p>The constructor only retains its inputs; declarations and configuration are validated by
     * {@link #instances()}, {@link #run(Vertx)} and {@link #migrate(Vertx)}. When
     * {@link FlywayConfig#instances()} is {@code null} the declarations and the placement are ignored
     * and the runner behaves exactly like {@link #FlywayMigrationRunner(FlywayConfig, DbPoolConfig)}.
     *
     * @param flywayConfig the Flyway configuration
     * @param dbPoolConfig the database pool configuration (for credential and JDBC URL fallback)
     * @param definitions the wired migration declarations, one per named instance
     * @param schemas the neutral schema placement read for each instance's default schema
     */
    public FlywayMigrationRunner(
            FlywayConfig flywayConfig,
            DbPoolConfig dbPoolConfig,
            Set<MigrationDefinition> definitions,
            DbSchemas schemas) {
        this(
                flywayConfig,
                dbPoolConfig,
                definitions,
                schemas,
                PostgresDatabaseSupport.selector(DRIVER_MANAGER),
                DRIVER_MANAGER);
    }

    /**
     * Creates a runner with an explicit database-support selector and connector.
     *
     * @param flywayConfig the Flyway configuration
     * @param dbPoolConfig the database pool configuration
     * @param definitions the wired migration declarations
     * @param schemas the neutral schema placement
     * @param selector chooses the database support for the root JDBC destination
     * @param jdbcConnector opens the connections the runner owns itself
     */
    FlywayMigrationRunner(
            FlywayConfig flywayConfig,
            DbPoolConfig dbPoolConfig,
            Set<MigrationDefinition> definitions,
            DbSchemas schemas,
            DatabaseSupportSelector selector,
            JdbcConnector jdbcConnector) {
        this.flywayConfig = flywayConfig;
        this.dbPoolConfig = dbPoolConfig;
        this.jdbcConnector = jdbcConnector;
        this.resolver = new NamedInstanceResolver(
                flywayConfig,
                dbPoolConfig,
                List.copyOf(Objects.requireNonNull(definitions, "definitions must not be null")),
                Objects.requireNonNull(schemas, "schemas must not be null"),
                Objects.requireNonNull(selector, "selector must not be null"),
                this::resolveJdbcUrl);
    }

    // --- Named instances ---

    /**
     * Resolves and validates the named instances without opening a database connection.
     *
     * <p>Checks names, the dependency graph, the root mode ceiling, credential pairs, the agreement
     * of each instance's schema list with its {@code db.schemas} entry, location syntax and known
     * history collisions, then, unless the root mode is {@link FlywayMode#DISABLED}, confirms that
     * every location exists and asks the database support for the root JDBC destination to apply its
     * identifier rules. For PostgreSQL every managed schema must be a single identifier of letters,
     * digits and underscores of at most 63 characters, not reserved for system schemas, every history
     * table the same syntax of at most 57 characters, and no history table may collide with another or
     * with the key and index relations native Flyway derives from it in the same known schema. Classpath
     * locations are looked up on the calling thread's context class loader; this lookup and the
     * filesystem checks may block, so call this method off the event loop. Every call validates
     * afresh.
     *
     * @return the resolved instances in execution order, immutable and free of secret values; empty
     *     when {@link FlywayConfig#instances()} is {@code null}, because the single-Flyway default
     *     schema may only be known after connecting
     * @throws MigrationRunException with operation {@code instances} and no report when the named
     *     configuration or the declarations are invalid
     */
    public List<MigrationInstanceView> instances() {
        if (!isNamed()) {
            return List.of();
        }
        ClassLoader loader = callerClassLoader();
        try {
            return resolver.resolve(loader).views();
        } catch (NamedMigrationFailure failure) {
            throw failure.toException(NamedMigrationFailure.OP_INSTANCES, null);
        }
    }

    /**
     * Runs the configured migrations and reports every outcome. This is the operation the startup
     * step invokes through {@link #migrate(Vertx)}.
     *
     * <p>In named mode the instances are validated again on a Vert.x worker before any connection is
     * opened, then every enabled instance runs in order; per-instance {@link FlywayMode#DISABLED}
     * instances never connect, and a root {@link FlywayMode#DISABLED} run connects to nothing. The
     * returned future fails with a {@link MigrationRunException} whose report lists completed,
     * failed and not-run instances in order.
     *
     * <p>Without named instances the single Flyway runs exactly as {@link #migrate(Vertx)} runs it
     * and the report holds one entry named {@code legacy} with the history schema and table actually
     * used; failures keep the {@link MigrationException} behavior of {@link #migrate(Vertx)}.
     *
     * @param vertx the Vert.x instance used for {@code executeBlocking}; the caller owns it
     * @return a future with the ordered report
     */
    public Future<MigrationReport> run(Vertx vertx) {
        if (!isNamed()) {
            return runLegacy(vertx);
        }
        ClassLoader loader = callerClassLoader();
        return vertx.executeBlocking(() -> executeNamed(loader).report());
    }

    private boolean isNamed() {
        return flywayConfig.instances() != null;
    }

    private static ClassLoader callerClassLoader() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        return loader != null ? loader : FlywayMigrationRunner.class.getClassLoader();
    }

    private NamedMigrationExecution.Outcome executeNamed(ClassLoader loader) {
        NamedPlan plan;
        try {
            plan = resolver.resolve(loader);
        } catch (NamedMigrationFailure failure) {
            MigrationReport report = failure.views() == null ? null : NamedMigrationExecution.notRun(failure.views());
            throw failure.toException(NamedMigrationFailure.OP_RUN, report);
        }
        return new NamedMigrationExecution(plan, flywayConfig, loader).execute();
    }

    private Future<MigrationResult> migrateNamed(Vertx vertx) {
        ClassLoader loader = callerClassLoader();
        return vertx.executeBlocking(() -> {
            NamedMigrationExecution.Outcome outcome = executeNamed(loader);
            int applied = outcome.report().instances().stream()
                    .mapToInt(MigrationInstanceResult::migrationsApplied)
                    .sum();
            return new MigrationResult(applied, outcome.targetVersion());
        });
    }

    // --- Legacy report ---

    private Future<MigrationReport> runLegacy(Vertx vertx) {
        if (flywayConfig.mode() == FlywayMode.DISABLED) {
            log.info("Flyway migration disabled");
            MigrationInstanceView view = legacyView(firstConfiguredSchema(), "flyway_schema_history");
            return Future.succeededFuture(
                    new MigrationReport(List.of(new MigrationInstanceResult(view, MigrationStatus.DISABLED, 0, null))));
        }
        return vertx.executeBlocking(() -> {
            Flyway flyway = buildFlyway();
            int applied = 0;
            MigrationStatus status;
            if (flywayConfig.mode() == FlywayMode.MIGRATE) {
                log.info("Running Flyway migration");
                MigrateResult result = runMigrate(flyway);
                log.info(
                        "Flyway migration complete: {} migrations applied, target version: {}",
                        result.migrationsExecuted,
                        result.targetSchemaVersion);
                applied = result.migrationsExecuted;
                status = MigrationStatus.MIGRATED;
            } else {
                log.info("Validating Flyway schema");
                runValidate(flyway);
                log.info("Flyway schema validation passed");
                status = MigrationStatus.VALIDATED;
            }
            String currentVersion;
            String schema;
            try {
                currentVersion = NamedMigrationExecution.currentVersion(flyway);
                schema = firstConfiguredSchema() != null
                        ? firstConfiguredSchema()
                        : NamedMigrationExecution.connectionSchema(flyway);
            } catch (FlywayException e) {
                throw new MigrationException("Migration status could not be read: " + e.getMessage(), e);
            }
            MigrationInstanceView view =
                    legacyView(schema, flyway.getConfiguration().getTable());
            return new MigrationReport(List.of(new MigrationInstanceResult(view, status, applied, currentVersion)));
        });
    }

    private String firstConfiguredSchema() {
        List<String> schemas = flywayConfig.schemas();
        return schemas == null || schemas.isEmpty() ? null : schemas.get(0);
    }

    /** The single entry of a legacy report; the credential source reflects where the user came from. */
    private MigrationInstanceView legacyView(String schema, String table) {
        boolean flywayUser = flywayConfig.user() != null;
        return new MigrationInstanceView(
                NamedInstanceResolver.LEGACY_NAME,
                schema,
                flywayConfig.schemas() == null ? List.of() : flywayConfig.schemas(),
                table,
                flywayConfig.locations() == null ? List.of() : flywayConfig.locations(),
                flywayConfig.mode(),
                flywayConfig.target(),
                flywayConfig.outOfOrder(),
                flywayConfig.baselineOnMigrate(),
                flywayConfig.baselineVersion(),
                flywayConfig.validateOnMigrate(),
                flywayUser ? flywayConfig.user() : dbPoolConfig.user(),
                flywayUser ? CredentialSource.ROOT_FLYWAY : CredentialSource.DB_POOL,
                flywayConfig.placeholders() == null
                        ? Set.of()
                        : flywayConfig.placeholders().keySet());
    }

    /**
     * Runs schema migrations according to the configured {@link FlywayMode}.
     *
     * <p>Flyway is JDBC-based (blocking), so this method uses {@link Vertx#executeBlocking} to
     * avoid blocking the event loop. Any {@link FlywayException} is wrapped in a
     * {@link MigrationException}.
     *
     * <p>In named mode this runs the same operation as {@link #run(Vertx)} and aggregates its report:
     * the applied counts are summed, and the target version is the native one of the only entry when
     * the report has exactly one entry, otherwise {@code null}. Named failures are
     * {@link MigrationRunException}s carrying the ordered report.
     *
     * @param vertx the Vert.x instance used for {@code executeBlocking}
     * @return a future with the migration result; completes immediately if mode is
     *         {@link FlywayMode#DISABLED} and no named instances are configured
     */
    @Override
    public Future<MigrationResult> migrate(Vertx vertx) {
        if (isNamed()) {
            return migrateNamed(vertx);
        }
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
