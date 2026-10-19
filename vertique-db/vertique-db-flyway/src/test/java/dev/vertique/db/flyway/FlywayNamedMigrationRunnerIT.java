// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.db.flyway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.MigrationDefinition;
import dev.vertique.db.MigrationResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the named-instance path of {@link FlywayMigrationRunner} against a real PostgreSQL container
 * and proves its execution contract: instances run in dependency order, the first failure stops the
 * batch without rolling earlier instances back, the failure reaches the caller as a framework-authored
 * exception that retains no native throwable or placeholder value, every connection is released, and
 * a retry finishes exactly the remaining work.
 *
 * <p>The scenarios are phases of one test because the retained proof command selects that single
 * method. Each phase provisions its own database on the shared container so none can bleed into
 * another.
 */
@DisplayName("FlywayMigrationRunner named instances against PostgreSQL")
class FlywayNamedMigrationRunnerIT {

    private static final String IMAGE = "postgres:16-alpine";

    // --- Declarations: names are chosen so that the dependency order differs from alphabetical order ---

    private static final String LEDGER = "classpath:db/it-named/ledger";
    private static final String AUDIT = "classpath:db/it-named/audit";
    private static final String PAIR = "classpath:db/it-named/pair";
    private static final String SHOP = "classpath:db/it-named/shop";

    /** Where each declaration lives at runtime and during migration. */
    private static final DbSchemas PLACEMENT = new DbSchemas(Map.of(
            "ledger", "it_ledger",
            "billing", "it_billing",
            "audit", "it_audit",
            "catalog", "it_catalog",
            "shop", "it_shop"));

    private static String historyTable(String instance) {
        return "flyway_schema_history_" + instance;
    }

    // --- The failing migration: its placeholder value must never surface in the failure ---

    private static final String PROBE_KEY = "probe";
    private static final String PROBE_VALUE = "s3cr3t_probe";
    private static final String BILLING_SCRIPT = "V1__create_invoice.sql";

    private static final String FAILING_BILLING_SQL = """
            CREATE TABLE invoice (id INTEGER PRIMARY KEY);
            INSERT INTO missing_${probe} (id) VALUES (1);
            """;

    private static final String FIXED_BILLING_SQL = """
            CREATE TABLE invoice (id INTEGER PRIMARY KEY);
            INSERT INTO invoice (id) VALUES (1);
            """;

    /** The text the operator must be given when a schema is occupied but has no history. */
    private static final Pattern BASELINE_VERSION_ZERO = Pattern.compile("baselineVersion\\W{0,6}0\\b");

    private static PostgreSQLContainer postgres;
    private static Vertx vertx;
    private static int databaseCounter;

    @TempDir
    Path tmp;

    @BeforeAll
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    static void startContainerAndVertx() {
        postgres = new PostgreSQLContainer(IMAGE);
        postgres.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopContainerAndVertx() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    // The single method runs several database scenarios and two migrations per scenario, so it is allowed
    // more than the default 20 seconds; the container start-up is bounded separately above.
    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a failed named run reports ordered outcomes, retains no native detail and leaves no connection open")
    void reportsOrderedFailuresAndClosesConnections() throws Exception {
        failedRunStopsTheBatchAndRetryFinishesTheRest();
        compatibilityMigrateAggregatesEveryDeclaredEntry();
        occupiedSchemaWithoutHistoryGetsActionableGuidance();
    }

    // =====================================================================================
    // Phase 1: ordered failure, then retry
    // =====================================================================================

    private void failedRunStopsTheBatchAndRetryFinishesTheRest() throws Exception {
        // Given: ledger, then billing (its middle migration fails), then audit, which has valid SQL
        String database = freshDatabase();
        Path billing = billingLocationWith(FAILING_BILLING_SQL);
        FlywayMigrationRunner runner = runner(database, orderedDeclarations(billing), namedConfig());

        // When: the batch runs and billing fails natively
        Throwable failure = failureOf(runner.run(vertx));

        // Then: the failure is framework-authored and carries the ordered report
        MigrationRunException failed = assertInstanceOf(MigrationRunException.class, failure);
        assertEquals("MIGRATION", failed.failureCode());
        assertEquals("run", failed.operation());
        assertFrameworkMessageNames(failed, "billing");
        assertRetainsNoNativeDetail(failed);

        MigrationReport report = failed.report();
        assertNotNull(report, "a named run failure carries the ordered report");
        assertEquals(List.of("ledger", "billing", "audit"), namesOf(report), "dependency order, not alphabetical");
        assertEquals(
                List.of(MigrationStatus.MIGRATED, MigrationStatus.FAILED, MigrationStatus.NOT_RUN), statusesOf(report));
        MigrationInstanceResult ledgerResult = report.instances().get(0);
        assertEquals(1, ledgerResult.migrationsApplied());
        assertEquals("1", ledgerResult.currentVersion());
        assertEquals("it_ledger", ledgerResult.instance().schema());
        assertEquals(historyTable("ledger"), ledgerResult.instance().table());
        for (MigrationInstanceResult unfinished : report.instances().subList(1, 3)) {
            assertEquals(
                    0, unfinished.migrationsApplied(), unfinished.instance().name());
            assertNull(unfinished.currentVersion(), unfinished.instance().name());
        }
        assertEquals("it_billing", report.instances().get(1).instance().schema());

        // And: no batch atomicity: the earlier instance stays committed, the failing migration rolled
        // back, and the later instance has not been touched
        assertEquals(List.of("1"), appliedVersions(database, "it_ledger", historyTable("ledger")));
        assertEquals(1, countRows(database, "it_ledger.ledger_entry"));
        assertFalse(tableExists(database, "it_billing", "invoice"), "the failing migration is rolled back");
        assertEquals(List.of(), appliedVersions(database, "it_billing", historyTable("billing")));
        assertFalse(tableExists(database, "it_audit", historyTable("audit")), "the later instance is untouched");
        assertNoOpenConnections(database);

        // When: the failing script is replaced by valid SQL and the batch runs again
        replaceBillingScript(billing, FIXED_BILLING_SQL);
        MigrationReport retry = succeeded(runner.run(vertx));

        // Then: every instance is migrated; only the unfinished work is applied
        assertEquals(List.of("ledger", "billing", "audit"), namesOf(retry));
        assertEquals(
                List.of(MigrationStatus.MIGRATED, MigrationStatus.MIGRATED, MigrationStatus.MIGRATED),
                statusesOf(retry));
        assertEquals(
                List.of(0, 1, 1),
                retry.instances().stream()
                        .map(MigrationInstanceResult::migrationsApplied)
                        .toList(),
                "the committed instance applies nothing; the failed and the unattempted ones exactly one");
        for (MigrationInstanceResult result : retry.instances()) {
            assertEquals("1", result.currentVersion(), result.instance().name());
        }

        // And: committed business data is unchanged and nothing was duplicated
        assertEquals(1, countRows(database, "it_ledger.ledger_entry"));
        assertEquals(1, countRows(database, "it_billing.invoice"));
        assertEquals(1, countRows(database, "it_audit.audit_trail"));
        assertEquals(List.of("1"), appliedVersions(database, "it_ledger", historyTable("ledger")));
        assertEquals(List.of("1"), appliedVersions(database, "it_billing", historyTable("billing")));
        assertEquals(List.of("1"), appliedVersions(database, "it_audit", historyTable("audit")));
        assertNoOpenConnections(database);
    }

    // =====================================================================================
    // Phase 2: the compatibility migrate(Vertx) aggregation
    // =====================================================================================

    private void compatibilityMigrateAggregatesEveryDeclaredEntry() throws Exception {
        // Given: one MIGRATE entry (native version 1) and one DISABLED entry
        String mixed = freshDatabase();
        FlywayMigrationRunner oneMigrateOneDisabled = runner(
                mixed,
                declarations(declaration("ledger", LEDGER), declaration("audit", AUDIT)),
                namedConfig(override("audit", FlywayMode.DISABLED, null, null)));

        // When
        MigrationResult aggregated = succeeded(oneMigrateOneDisabled.migrate(vertx));

        // Then: the migration count is summed, and a report with several entries has no single target version
        assertEquals(new MigrationResult(1, null), aggregated);
        assertEquals(1, countRows(mixed, "it_ledger.ledger_entry"));
        assertFalse(tableExists(mixed, "it_audit", historyTable("audit")), "a disabled entry never connects");

        // Given: a sole MIGRATE entry whose invocation applies versions 1 and 2
        String sole = freshDatabase();
        FlywayMigrationRunner soleMigrate = runner(sole, declarations(declaration("catalog", PAIR)), namedConfig());

        // When / Then: the native invocation version is returned, then none once nothing is pending
        assertEquals(new MigrationResult(2, "2"), succeeded(soleMigrate.migrate(vertx)));
        assertEquals(new MigrationResult(0, null), succeeded(soleMigrate.migrate(vertx)));

        // Given: a sole DISABLED entry
        String disabled = freshDatabase();
        FlywayMigrationRunner soleDisabled = runner(
                disabled,
                declarations(declaration("audit", AUDIT)),
                namedConfig(override("audit", FlywayMode.DISABLED, null, null)));

        // When / Then: nothing applied and no target version
        assertEquals(new MigrationResult(0, null), succeeded(soleDisabled.migrate(vertx)));
        assertFalse(tableExists(disabled, "it_audit", historyTable("audit")), "a disabled entry never connects");

        assertNoOpenConnections(mixed);
        assertNoOpenConnections(sole);
        assertNoOpenConnections(disabled);
    }

    // =====================================================================================
    // Phase 3: an occupied schema without history
    // =====================================================================================

    private void occupiedSchemaWithoutHistoryGetsActionableGuidance() throws Exception {
        // Given: the instance's schema already holds a table but has no migration history
        String database = freshDatabase();
        execute(
                database,
                "CREATE SCHEMA it_shop; CREATE TABLE it_shop.legacy_stock (id INTEGER PRIMARY KEY);"
                        + " INSERT INTO it_shop.legacy_stock (id) VALUES (1);");
        FlywayMigrationRunner strict = runner(database, declarations(declaration("shop", SHOP)), namedConfig());

        // When: the default policy runs
        Throwable failure = failureOf(strict.run(vertx));

        // Then: the failure names the instance and the two ways out, and says nothing native
        MigrationRunException failed = assertInstanceOf(MigrationRunException.class, failure);
        assertEquals("run", failed.operation());
        assertFrameworkMessageNames(failed, "shop");
        String message = failed.getMessage();
        assertTrue(message.contains("db.schemas"), "names the separate-schema option: " + message);
        assertTrue(message.contains("baselineOnMigrate=true"), "names the baseline option: " + message);
        assertTrue(
                BASELINE_VERSION_ZERO.matcher(message).find(),
                "names baselineVersion 0 for the baseline option: " + message);
        assertFalse(message.contains("Found non-empty"), "no raw native message: " + message);
        assertRetainsNoNativeDetail(failed);
        assertEquals(List.of(MigrationStatus.FAILED), statusesOf(failed.report()));

        // And: nothing was marked applied or created, and the existing data is intact
        assertFalse(tableExists(database, "it_shop", "shop_item"), "V1 must not run, nor be marked applied");
        assertEquals(List.of(), appliedVersions(database, "it_shop", historyTable("shop")));
        assertEquals(1, countRows(database, "it_shop.legacy_stock"));
        assertNoOpenConnections(database);

        // When: the advertised baseline option is chosen explicitly
        FlywayMigrationRunner baselined = runner(
                database,
                declarations(declaration("shop", SHOP)),
                namedConfig(override("shop", null, Boolean.TRUE, "0")));
        MigrationReport report = succeeded(baselined.run(vertx));

        // Then: the baseline marker is recorded natively and V1 runs
        MigrationInstanceResult shop = report.instances().get(0);
        assertEquals(MigrationStatus.MIGRATED, shop.status());
        assertEquals(1, shop.migrationsApplied());
        assertEquals("1", shop.currentVersion());
        assertTrue(tableExists(database, "it_shop", "shop_item"));
        assertEquals(List.of("1"), appliedVersions(database, "it_shop", historyTable("shop")));
        assertEquals(1, countRows(database, "it_shop.legacy_stock"));
        assertNoOpenConnections(database);
    }

    // =====================================================================================
    // Fixtures: declarations and configuration
    // =====================================================================================

    private static MigrationDefinition declaration(String name, String location, String... dependsOn) {
        return new MigrationDefinition(name, List.of(location), List.of(dependsOn));
    }

    private static Set<MigrationDefinition> declarations(MigrationDefinition... definitions) {
        return new LinkedHashSet<>(List.of(definitions));
    }

    /** Dependency chain ledger, billing, audit, contributed in an order that is neither of the two. */
    private static Set<MigrationDefinition> orderedDeclarations(Path billingDirectory) {
        return declarations(
                declaration("audit", AUDIT, "billing"),
                declaration("ledger", LEDGER),
                declaration("billing", "filesystem:" + billingDirectory, "ledger"));
    }

    /** Named mode over the given overrides; the placeholder is inherited by every instance. */
    private static FlywayConfig namedConfig(FlywayInstanceConfig... overrides) {
        return FlywayConfig.builder()
                .mode(FlywayMode.MIGRATE)
                .placeholders(Map.of(PROBE_KEY, PROBE_VALUE))
                .instances(List.of(overrides))
                .build();
    }

    private static FlywayInstanceConfig override(
            String name, FlywayMode mode, Boolean baselineOnMigrate, String baselineVersion) {
        return new FlywayInstanceConfig(
                name, mode, null, null, null, null, null, baselineOnMigrate, baselineVersion, null, null, null, null);
    }

    private static FlywayMigrationRunner runner(
            String database, Set<MigrationDefinition> declarations, FlywayConfig config) {
        return new FlywayMigrationRunner(config, poolConfig(database), declarations, PLACEMENT);
    }

    /** A real filesystem directory, so its script can be rewritten between two runs. */
    private Path billingLocationWith(String sql) throws IOException {
        Path directory = Files.createDirectories(tmp.toRealPath().resolve("billing"));
        Files.writeString(directory.resolve(BILLING_SCRIPT), sql);
        return directory;
    }

    private static void replaceBillingScript(Path directory, String sql) throws IOException {
        Files.writeString(directory.resolve(BILLING_SCRIPT), sql);
    }

    // =====================================================================================
    // Fixtures: PostgreSQL
    // =====================================================================================

    /** Creates a fresh database on the shared server and returns its name. */
    private static String freshDatabase() throws SQLException {
        String name = "flyway_named_it_" + (++databaseCounter);
        try (Connection admin = DriverManager.getConnection(
                        postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                Statement statement = admin.createStatement()) {
            statement.execute("CREATE DATABASE " + name);
        }
        return name;
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

    private static Connection connect(String database) throws SQLException {
        String url = "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database;
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    private static void execute(String database, String sql) throws SQLException {
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int countRows(String database, String qualifiedTable) throws SQLException {
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + qualifiedTable)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static boolean tableExists(String database, String schema, String table) throws SQLException {
        try (Connection connection = connect(database);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = ? AND table_name = ?")) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) > 0;
            }
        }
    }

    /** Versions of the successfully applied SQL migrations in a history table; empty when it does not exist. */
    private static List<String> appliedVersions(String database, String schema, String table) throws SQLException {
        if (!tableExists(database, schema, table)) {
            return List.of();
        }
        List<String> versions = new ArrayList<>();
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT version FROM \"" + schema + "\".\"" + table
                        + "\" WHERE success AND type = 'SQL' ORDER BY installed_rank")) {
            while (rows.next()) {
                versions.add(rows.getString(1));
            }
        }
        return versions;
    }

    /**
     * Asserts that no session of the database is left open once a run has finished. Backends exit a moment
     * after the client closes, so this polls briefly instead of reading once; the sessions are dumped when
     * some remain, which names who held them.
     */
    private static void assertNoOpenConnections(String database) throws Exception {
        String query = "SELECT pid, application_name, state, backend_start FROM pg_catalog.pg_stat_activity"
                + " WHERE datname = ? AND pid <> pg_catalog.pg_backend_pid()";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (true) {
            List<String> sessions = new ArrayList<>();
            try (Connection admin = DriverManager.getConnection(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    PreparedStatement statement = admin.prepareStatement(query)) {
                statement.setString(1, database);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        sessions.add("pid=" + rows.getInt(1) + " application=" + rows.getString(2) + " state="
                                + rows.getString(3) + " since=" + rows.getTimestamp(4));
                    }
                }
            }
            if (sessions.isEmpty()) {
                return;
            }
            if (System.nanoTime() > deadline) {
                fail("connections still open on " + database + ": " + sessions);
            }
            Thread.sleep(100);
        }
    }

    // =====================================================================================
    // Assertions and futures
    // =====================================================================================

    private static <T> T succeeded(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(Future<?> future) {
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS));
        return failure.getCause();
    }

    private static List<String> namesOf(MigrationReport report) {
        return report.instances().stream().map(r -> r.instance().name()).toList();
    }

    private static List<MigrationStatus> statusesOf(MigrationReport report) {
        return report.instances().stream().map(MigrationInstanceResult::status).toList();
    }

    /** The message is written by the framework: it identifies the instance and the operation. */
    private static void assertFrameworkMessageNames(MigrationRunException failure, String instance) {
        String message = String.valueOf(failure.getMessage());
        assertTrue(message.contains(instance), "message does not name the instance " + instance + ": " + message);
        assertTrue(
                Pattern.compile("\\b" + failure.operation() + "\\b")
                        .matcher(message)
                        .find(),
                "message does not name the operation " + failure.operation() + ": " + message);
    }

    /** No native throwable in the chain, and neither the placeholder value, the SQL nor a JDBC URL anywhere. */
    private static void assertRetainsNoNativeDetail(MigrationRunException failure) {
        assertNull(failure.getCause(), "no original throwable may be retained as the cause");
        assertEquals(0, failure.getSuppressed().length, "no throwable may be retained as suppressed");
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        String everything = trace + " " + failure.report();
        assertFalse(everything.contains(PROBE_VALUE), "the placeholder value leaked into the failure");
        assertFalse(everything.contains("INSERT INTO"), "raw SQL leaked into the failure");
        assertFalse(everything.contains("jdbc:"), "a JDBC URL leaked into the failure");
    }
}
