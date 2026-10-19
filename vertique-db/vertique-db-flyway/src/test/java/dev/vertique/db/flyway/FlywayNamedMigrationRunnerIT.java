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
import dev.vertique.db.MigrationException;
import dev.vertique.db.MigrationResult;
import dev.vertique.db.flyway.MigrationRunException.Stage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.io.IOException;
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
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Runs the named-instance path of {@link FlywayMigrationRunner} against a real PostgreSQL container
 * and proves its execution contract: instances run in dependency order with their own histories in their
 * own schemas, the first failure stops the batch without rolling earlier instances back, the failure
 * reaches the caller as a framework-authored exception that keeps the native Flyway cause and no
 * placeholder value in its message, and two runners started together apply every migration exactly once.
 *
 * <p>Each test provisions its own database on the shared container so none can bleed into another.
 */
@DisplayName("FlywayMigrationRunner named instances against PostgreSQL")
class FlywayNamedMigrationRunnerIT {

    private static final String IMAGE = "postgres:16-alpine";

    // --- Declarations: names are chosen so that the dependency order differs from alphabetical order ---

    private static final String LEDGER = "classpath:db/it-named/ledger";
    private static final String AUDIT = "classpath:db/it-named/audit";
    private static final String PAIR = "classpath:db/it-named/pair";
    private static final String SHOP = "classpath:db/it-named/shop";
    private static final String GATE = "classpath:db/it-gate";
    private static final String ABSENT = "classpath:db/it-named/absent";

    /** Where each declaration lives at runtime and during migration. */
    private static final Map<String, String> PLACEMENT = Map.of(
            "ledger", "it_ledger",
            "billing", "it_billing",
            "audit", "it_audit",
            "catalog", "it_catalog",
            "shop", "it_shop",
            "gated", "it_gated");

    private static String historyTable(String instance) {
        return "flyway_schema_history_" + instance;
    }

    // --- The failing migration: its placeholder value must never surface in the failure message ---

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

    /** The advisory lock the first gate script waits for; it must match the script. */
    private static final long GATE_KEY = 7_770_001L;

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

    // =====================================================================================
    // Order, histories and placement
    // =====================================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("instances run in dependency order with distinct histories in their own schemas")
    void runsInDependencyOrderIntoTheirOwnSchemas() throws Exception {
        // Given: catalog after audit after ledger, contributed in an order that is neither of the two
        String database = freshDatabase();
        FlywayMigrationRunner runner = runner(
                database,
                declarations(
                        declaration("catalog", PAIR, "audit"),
                        declaration("ledger", LEDGER),
                        declaration("audit", AUDIT, "ledger")),
                namedConfig());

        // When: the batch runs on a fresh database
        MigrationReport first = succeeded(runner.run(vertx));

        // Then: dependency order, not alphabetical order, with every migration applied once
        assertEquals(List.of("ledger", "audit", "catalog"), namesOf(first));
        assertEquals(
                List.of(MigrationStatus.MIGRATED, MigrationStatus.MIGRATED, MigrationStatus.MIGRATED),
                statusesOf(first));
        assertEquals(List.of(1, 1, 2), appliedOf(first));
        assertEquals(List.of("1", "1", "2"), versionsOf(first));

        // And: each instance has its own history table inside its own schema, and nothing is in public
        for (MigrationInstanceResult result : first.instances()) {
            String name = result.instance().name();
            assertEquals("it_" + name, result.instance().schema(), name);
            assertEquals(historyTable(name), result.instance().table(), name);
            assertTrue(tableExists(database, "it_" + name, historyTable(name)), name + " history");
        }
        assertEquals(List.of("1"), appliedVersions(database, "it_ledger", historyTable("ledger")));
        assertEquals(List.of("1", "2"), appliedVersions(database, "it_catalog", historyTable("catalog")));
        assertTrue(tableExists(database, "it_ledger", "ledger_entry"));
        assertTrue(tableExists(database, "it_audit", "audit_trail"));
        assertTrue(tableExists(database, "it_catalog", "catalog_item"));
        assertFalse(tableExists(database, "public", "ledger_entry"));
        assertFalse(tableExists(database, "public", historyTable("ledger")));

        // When: the batch runs again
        MigrationReport second = succeeded(runner.run(vertx));

        // Then: nothing is applied and the current versions are reported
        assertEquals(List.of(0, 0, 0), appliedOf(second));
        assertEquals(List.of("1", "1", "2"), versionsOf(second));
        assertEquals(1, countRows(database, "it_ledger.ledger_entry"));
        assertNoOpenConnections(database);
    }

    // =====================================================================================
    // Failure mid-batch, then retry
    // =====================================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a failed instance stops the batch, keeps the native cause and a retry finishes the rest")
    void failedRunStopsTheBatchAndRetryFinishesTheRest() throws Exception {
        // Given: ledger, then billing (its middle migration fails), then audit, which has valid SQL
        String database = freshDatabase();
        Path billing = billingLocationWith(FAILING_BILLING_SQL);
        FlywayMigrationRunner runner = runner(
                database,
                declarations(
                        declaration("audit", AUDIT, "billing"),
                        declaration("ledger", LEDGER),
                        declaration("billing", "filesystem:" + billing, "ledger")),
                namedConfig());

        // When: the batch runs and billing fails natively
        MigrationRunException failed = assertInstanceOf(MigrationRunException.class, failureOf(runner.run(vertx)));

        // Then: the stage, the instance and the native cause are visible, the message carries no value
        assertEquals(Stage.MIGRATION, failed.stage());
        assertTrue(failed.getMessage().contains("billing"), failed.getMessage());
        assertInstanceOf(FlywayException.class, failed.getCause(), "the native Flyway failure is the cause");
        assertMessageAndReportAreValueFree(failed);

        // And: the report lists the earlier success, the failure and the instance never attempted
        MigrationReport report = failed.report();
        assertNotNull(report);
        assertEquals(List.of("ledger", "billing", "audit"), namesOf(report));
        assertEquals(
                List.of(MigrationStatus.MIGRATED, MigrationStatus.FAILED, MigrationStatus.NOT_RUN), statusesOf(report));
        assertEquals(List.of(1, 0, 0), appliedOf(report));
        assertEquals("1", report.instances().get(0).currentVersion());
        assertNull(report.instances().get(1).currentVersion());
        assertNull(report.instances().get(2).currentVersion());

        // And: no batch atomicity: ledger stays committed, the failing migration rolled back, audit untouched
        assertEquals(List.of("1"), appliedVersions(database, "it_ledger", historyTable("ledger")));
        assertFalse(tableExists(database, "it_billing", "invoice"), "the failing migration is rolled back");
        assertEquals(List.of(), appliedVersions(database, "it_billing", historyTable("billing")));
        assertFalse(tableExists(database, "it_audit", historyTable("audit")), "the later instance is untouched");
        assertNoOpenConnections(database);

        // When: the failing script is replaced by valid SQL and the batch runs again
        Files.writeString(billing.resolve(BILLING_SCRIPT), FIXED_BILLING_SQL);
        MigrationReport retry = succeeded(runner.run(vertx));

        // Then: only the unfinished work is applied and nothing was duplicated
        assertEquals(List.of(0, 1, 1), appliedOf(retry));
        assertEquals(List.of("1", "1", "1"), versionsOf(retry));
        assertEquals(1, countRows(database, "it_ledger.ledger_entry"));
        assertEquals(1, countRows(database, "it_billing.invoice"));
        assertEquals(1, countRows(database, "it_audit.audit_trail"));
        assertNoOpenConnections(database);
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("migrate aggregates the applied counts and the native target version of a sole entry")
    void migrateAggregatesEveryDeclaredEntry() throws Exception {
        // Given: one MIGRATE entry (native version 1) and one DISABLED entry
        String mixed = freshDatabase();
        FlywayMigrationRunner oneMigrateOneDisabled = runner(
                mixed,
                declarations(declaration("ledger", LEDGER), declaration("audit", AUDIT)),
                namedConfig(override("audit", FlywayMode.DISABLED)));

        // When / Then: the count is summed, and several entries have no single target version
        assertEquals(new MigrationResult(1, null), succeeded(oneMigrateOneDisabled.migrate(vertx)));
        assertFalse(tableExists(mixed, "it_audit", historyTable("audit")), "a disabled entry never connects");

        // Given: a sole MIGRATE entry whose invocation applies versions 1 and 2
        String sole = freshDatabase();
        FlywayMigrationRunner soleMigrate = runner(sole, declarations(declaration("catalog", PAIR)), namedConfig());

        // When / Then: the native invocation version is returned, then none once nothing is pending
        assertEquals(new MigrationResult(2, "2"), succeeded(soleMigrate.migrate(vertx)));
        assertEquals(new MigrationResult(0, null), succeeded(soleMigrate.migrate(vertx)));
    }

    // =====================================================================================
    // Per-instance modes
    // =====================================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("DISABLED never connects and VALIDATE fails loudly against an absent history under a MIGRATE root")
    void perInstanceDisabledAndValidateUnderAMigrateRoot() throws Exception {
        // Given: ledger migrates, audit is DISABLED, catalog only validates and has no history yet
        String database = freshDatabase();
        Set<MigrationDefinition> declared = declarations(
                declaration("ledger", LEDGER),
                declaration("audit", AUDIT, "ledger"),
                declaration("catalog", PAIR, "audit"));
        FlywayConfig config =
                namedConfig(override("audit", FlywayMode.DISABLED), override("catalog", FlywayMode.VALIDATE));

        // When: the batch runs
        MigrationRunException failed = assertInstanceOf(
                MigrationRunException.class,
                failureOf(runner(database, declared, config).run(vertx)));

        // Then: the validation failure is loud and staged, and the report shows what each mode did
        assertEquals(Stage.VALIDATION, failed.stage());
        assertInstanceOf(FlywayException.class, failed.getCause());
        assertEquals(
                List.of(MigrationStatus.MIGRATED, MigrationStatus.DISABLED, MigrationStatus.FAILED),
                statusesOf(failed.report()));
        assertEquals(List.of("ledger", "audit", "catalog"), namesOf(failed.report()));

        // And: validation wrote nothing, and the DISABLED instance never even created its schema
        assertEquals(List.of("1"), appliedVersions(database, "it_ledger", historyTable("ledger")));
        assertFalse(tableExists(database, "it_catalog", historyTable("catalog")));
        assertFalse(tableExists(database, "it_catalog", "catalog_item"));
        assertFalse(schemaExists(database, "it_audit"), "a disabled instance never connects");

        // When: catalog is migrated by a runner that allows it, and the original batch runs again
        succeeded(runner(database, declarations(declaration("catalog", PAIR)), namedConfig())
                .run(vertx));
        MigrationReport report = succeeded(runner(database, declared, config).run(vertx));

        // Then: VALIDATE passes without applying anything and reports the version it found
        assertEquals(
                List.of(MigrationStatus.MIGRATED, MigrationStatus.DISABLED, MigrationStatus.VALIDATED),
                statusesOf(report));
        assertEquals(List.of(0, 0, 0), appliedOf(report));
        assertEquals("2", report.instances().get(2).currentVersion());
        assertNoOpenConnections(database);
    }

    // =====================================================================================
    // Missing migration location
    // =====================================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a missing migration location fails its instance instead of migrating nothing")
    void missingLocationFailsTheInstance() throws Exception {
        List<String> missing = List.of(ABSENT, "filesystem:" + tmp.toRealPath().resolve("never-created"));
        for (String location : missing) {
            // Given: ledger is fine and audit declares a location that does not exist
            String database = freshDatabase();
            FlywayMigrationRunner runner = runner(
                    database,
                    declarations(declaration("ledger", LEDGER), declaration("audit", location, "ledger")),
                    namedConfig());

            // When: the batch runs
            MigrationRunException failed = assertInstanceOf(MigrationRunException.class, failureOf(runner.run(vertx)));

            // Then: audit fails at the migration stage with the native cause, and ledger stays committed
            assertEquals(Stage.MIGRATION, failed.stage(), location);
            assertTrue(failed.getMessage().contains("audit"), failed.getMessage());
            assertInstanceOf(FlywayException.class, failed.getCause(), location);
            assertEquals(List.of(MigrationStatus.MIGRATED, MigrationStatus.FAILED), statusesOf(failed.report()));
            assertEquals(List.of("1"), appliedVersions(database, "it_ledger", historyTable("ledger")));
            assertFalse(tableExists(database, "it_audit", historyTable("audit")), location);
        }
    }

    // =====================================================================================
    // Instances sharing a schema: native baseline rules
    // =====================================================================================

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("an occupied schema without history gets actionable guidance, and the baseline option works")
    void occupiedSchemaWithoutHistoryGetsActionableGuidance() throws Exception {
        // Given: the instance's schema already holds a table but has no migration history
        String database = freshDatabase();
        execute(
                database,
                "CREATE SCHEMA it_shop; CREATE TABLE it_shop.legacy_stock (id INTEGER PRIMARY KEY);"
                        + " INSERT INTO it_shop.legacy_stock (id) VALUES (1);");

        // When: the default policy runs
        MigrationRunException failed = assertInstanceOf(
                MigrationRunException.class,
                failureOf(runner(database, declarations(declaration("shop", SHOP)), namedConfig())
                        .run(vertx)));

        // Then: the failure names the instance and the two ways out, and nothing was written
        assertBaselineGuidance(failed, "shop");
        assertEquals(List.of(MigrationStatus.FAILED), statusesOf(failed.report()));
        assertFalse(tableExists(database, "it_shop", "shop_item"), "V1 must not run, nor be marked applied");
        assertEquals(List.of(), appliedVersions(database, "it_shop", historyTable("shop")));
        assertEquals(1, countRows(database, "it_shop.legacy_stock"));

        // When: the advertised baseline option is chosen explicitly
        MigrationReport report = succeeded(runner(
                        database,
                        declarations(declaration("shop", SHOP)),
                        namedConfig(baseline("shop", Boolean.TRUE, "0")))
                .run(vertx));

        // Then: the baseline marker is recorded natively, V1 runs and the existing data is intact
        assertEquals(List.of(MigrationStatus.MIGRATED), statusesOf(report));
        assertEquals(List.of(1), appliedOf(report));
        assertEquals(List.of("1"), appliedVersions(database, "it_shop", historyTable("shop")));
        assertEquals(1, countRows(database, "it_shop.legacy_stock"));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("two instances in one schema fail the second by default and both run with baseline version 0")
    void instancesInOneSchemaNeedTheBaselineOptionForTheSecond() throws Exception {
        Map<String, String> shared = Map.of("ledger", "it_shared", "audit", "it_shared");
        Set<MigrationDefinition> declared =
                declarations(declaration("ledger", LEDGER), declaration("audit", AUDIT, "ledger"));

        // Given: two instances placed in one fresh schema with the default baseline policy
        String strict = freshDatabase();

        // When: ledger occupies the schema, then audit finds it occupied without a history of its own
        MigrationRunException failed = assertInstanceOf(
                MigrationRunException.class,
                failureOf(runner(strict, declared, namedConfig(), shared).run(vertx)));

        // Then: audit fails with the guidance, and its first script is not marked applied
        assertBaselineGuidance(failed, "audit");
        assertEquals(List.of(MigrationStatus.MIGRATED, MigrationStatus.FAILED), statusesOf(failed.report()));
        assertEquals(List.of("1"), appliedVersions(strict, "it_shared", historyTable("ledger")));
        assertEquals(List.of(), appliedVersions(strict, "it_shared", historyTable("audit")));
        assertFalse(tableExists(strict, "it_shared", "audit_trail"));

        // Given: the same placement with baselineOnMigrate and baselineVersion 0 at the root
        String baselined = freshDatabase();
        FlywayConfig config = FlywayConfig.builder()
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .instances(List.of())
                .build();
        FlywayMigrationRunner runner = runner(baselined, declared, config, shared);

        // When / Then: both first scripts run and each instance has its own history in the shared schema
        MigrationReport report = succeeded(runner.run(vertx));
        assertEquals(List.of(1, 1), appliedOf(report));
        assertEquals(List.of("1"), appliedVersions(baselined, "it_shared", historyTable("ledger")));
        assertEquals(List.of("1"), appliedVersions(baselined, "it_shared", historyTable("audit")));
        assertTrue(tableExists(baselined, "it_shared", "ledger_entry"));
        assertTrue(tableExists(baselined, "it_shared", "audit_trail"));

        // And: the batch is repeatable
        assertEquals(List.of(0, 0), appliedOf(succeeded(runner.run(vertx))));
    }

    // =====================================================================================
    // Two runners started together
    // =====================================================================================

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("two runners on one database apply every migration exactly once and both succeed")
    void concurrentRunnersApplyEachMigrationExactlyOnce() throws Exception {
        // Given: a first migration that stays inside its transaction until the test opens the gate
        String database = freshDatabase();
        FlywayConfig config = namedConfig();
        Future<MigrationReport> first;
        Future<MigrationReport> second;
        try (Connection gate = closedGate(database)) {
            first = gatedRunner(database, config).run(vertx);
            awaitBlockedInGate(database);

            // When: a second runner with the same configuration starts while the first holds Flyway's lock
            second = gatedRunner(database, config).run(vertx);
            Thread.sleep(2_500);
            assertFalse(second.isComplete(), "the second runner waits for the first instead of racing it");
            openGate(gate);
        }

        // Then: both succeed and between them applied each of the three scripts exactly once
        MigrationReport reportOfFirst = succeeded(first);
        MigrationReport reportOfSecond = succeeded(second);
        assertEquals(List.of(MigrationStatus.MIGRATED), statusesOf(reportOfFirst));
        assertEquals(List.of(MigrationStatus.MIGRATED), statusesOf(reportOfSecond));
        assertEquals(
                3, appliedOf(reportOfFirst).get(0) + appliedOf(reportOfSecond).get(0));
        assertEquals(List.of("3"), versionsOf(reportOfFirst));
        assertEquals(List.of("3"), versionsOf(reportOfSecond));

        // And: one history row per version, none failed, and the data was written once
        assertEquals(List.of("1", "2", "3"), appliedVersions(database, "it_gated", historyTable("gated")));
        assertEquals(0, countRows(database, "it_gated." + historyTable("gated") + " WHERE NOT success"));
        assertEquals(2, countRows(database, "it_gated.gate_item"));
        assertNoOpenConnections(database);
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("a runner that exceeds its lock retry count fails loudly while the first one finishes")
    void aRunnerThatCannotGetTheLockFailsLoudly() throws Exception {
        // Given: a root that waits long, an instance that does not wait at all, and a first run blocked in V1
        String database = freshDatabase();
        FlywayConfig config = FlywayConfig.builder()
                .lockRetryCount(60)
                .instances(List.of(retries("gated", 0)))
                .build();
        Future<MigrationReport> first;
        try (Connection gate = closedGate(database)) {
            first = gatedRunner(database, namedConfig()).run(vertx);
            awaitBlockedInGate(database);

            // When: a second runner starts and the lock is not released
            MigrationRunException failed = assertInstanceOf(
                    MigrationRunException.class,
                    failureOfWithin(gatedRunner(database, config).run(vertx), 20));

            // Then: it fails at the migration stage with the native cause, not silently, while the first still runs
            assertEquals(Stage.MIGRATION, failed.stage());
            assertInstanceOf(FlywayException.class, failed.getCause());
            assertEquals(List.of(MigrationStatus.FAILED), statusesOf(failed.report()));
            assertFalse(first.isComplete(), "the first runner is still inside its migration");
            openGate(gate);
        }

        // And: the first run completes everything and the failed one wrote nothing
        MigrationReport finished = succeeded(first);
        assertEquals(List.of(3), appliedOf(finished));
        assertEquals(List.of("1", "2", "3"), appliedVersions(database, "it_gated", historyTable("gated")));
        assertEquals(0, countRows(database, "it_gated." + historyTable("gated") + " WHERE NOT success"));
        assertNoOpenConnections(database);
    }

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("a legacy runner that exceeds its lock retry count fails loudly while the first one finishes")
    void aLegacyRunnerThatCannotGetTheLockFailsLoudly() throws Exception {
        // Given: a legacy run blocked in V1, and a second legacy configuration that does not wait for the lock
        String database = freshDatabase();
        FlywayConfig waiting = FlywayConfig.builder().locations(List.of(GATE)).build();
        FlywayConfig impatient = FlywayConfig.builder()
                .locations(List.of(GATE))
                .lockRetryCount(0)
                .build();
        Future<MigrationReport> first;
        try (Connection gate = closedGate(database)) {
            first = new FlywayMigrationRunner(waiting, poolConfig(database)).run(vertx);
            awaitBlockedInGate(database);

            // When: the second runner starts and the lock is not released
            Throwable failure =
                    failureOfWithin(new FlywayMigrationRunner(impatient, poolConfig(database)).run(vertx), 20);

            // Then: it fails with the native Flyway cause, not silently, while the first still runs
            assertInstanceOf(MigrationException.class, failure);
            assertInstanceOf(FlywayException.class, failure.getCause());
            assertFalse(first.isComplete(), "the first runner is still inside its migration");
            openGate(gate);
        }

        // And: the first run completes everything
        assertEquals(List.of(3), appliedOf(succeeded(first)));
        assertEquals(List.of("1", "2", "3"), appliedVersions(database, "public", "flyway_schema_history"));
        assertNoOpenConnections(database);
    }

    private FlywayMigrationRunner gatedRunner(String database, FlywayConfig config) {
        return runner(database, declarations(declaration("gated", GATE)), config);
    }

    /** Holds the gate lock on its own session, so the first migration blocks until {@link #openGate}. */
    private static Connection closedGate(String database) throws SQLException {
        Connection connection = connect(database);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT pg_advisory_lock(" + GATE_KEY + ")");
        }
        return connection;
    }

    private static void openGate(Connection gate) throws SQLException {
        try (Statement statement = gate.createStatement()) {
            statement.execute("SELECT pg_advisory_unlock(" + GATE_KEY + ")");
        }
    }

    /** Waits until some session of the database is blocked in the first gate script. */
    private static void awaitBlockedInGate(String database) throws Exception {
        String query = "SELECT count(*) FROM pg_catalog.pg_stat_activity WHERE datname = ? AND state = 'active'"
                + " AND wait_event_type = 'Lock' AND query LIKE '%pg_advisory_lock(" + GATE_KEY + ")%'"
                + " AND pid <> pg_catalog.pg_backend_pid()";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (true) {
            try (Connection admin = DriverManager.getConnection(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    PreparedStatement statement = admin.prepareStatement(query)) {
                statement.setString(1, database);
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) > 0) {
                        return;
                    }
                }
            }
            if (System.nanoTime() > deadline) {
                fail("the first migration never reached the gate on " + database);
            }
            Thread.sleep(50);
        }
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

    /** Named mode over the given overrides; the placeholder is inherited by every instance. */
    private static FlywayConfig namedConfig(FlywayInstanceConfig... overrides) {
        return FlywayConfig.builder()
                .mode(FlywayMode.MIGRATE)
                .placeholders(Map.of(PROBE_KEY, PROBE_VALUE))
                .instances(List.of(overrides))
                .build();
    }

    private static FlywayInstanceConfig override(String name, FlywayMode mode) {
        return new FlywayInstanceConfig(
                name, mode, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static FlywayInstanceConfig baseline(String name, Boolean baselineOnMigrate, String baselineVersion) {
        return new FlywayInstanceConfig(
                name,
                null,
                null,
                null,
                null,
                null,
                null,
                baselineOnMigrate,
                baselineVersion,
                null,
                null,
                null,
                null,
                null);
    }

    private static FlywayInstanceConfig retries(String name, Integer lockRetryCount) {
        return new FlywayInstanceConfig(
                name, null, null, null, null, null, null, null, null, null, null, null, null, lockRetryCount);
    }

    private static FlywayMigrationRunner runner(
            String database, Set<MigrationDefinition> declarations, FlywayConfig config) {
        return runner(database, declarations, config, PLACEMENT);
    }

    private static FlywayMigrationRunner runner(
            String database,
            Set<MigrationDefinition> declarations,
            FlywayConfig config,
            Map<String, String> placement) {
        return new FlywayMigrationRunner(config, poolConfig(database), declarations, new DbSchemas(placement));
    }

    /** A real filesystem directory, so its script can be rewritten between two runs. */
    private Path billingLocationWith(String sql) throws IOException {
        Path directory = Files.createDirectories(tmp.toRealPath().resolve("billing"));
        Files.writeString(directory.resolve(BILLING_SCRIPT), sql);
        return directory;
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

    /** Counts the rows of a qualified table; anything after the table name is a WHERE clause. */
    private static int countRows(String database, String tableAndFilter) throws SQLException {
        try (Connection connection = connect(database);
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + tableAndFilter)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static boolean schemaExists(String database, String schema) throws SQLException {
        try (Connection connection = connect(database);
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT count(*) FROM information_schema.schemata WHERE schema_name = ?")) {
            statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                return rows.getInt(1) > 0;
            }
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
        return future.toCompletionStage().toCompletableFuture().get(60, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(Future<?> future) {
        return failureOfWithin(future, 30);
    }

    private static Throwable failureOfWithin(Future<?> future, int seconds) {
        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> future.toCompletionStage().toCompletableFuture().get(seconds, TimeUnit.SECONDS),
                "the run was expected to fail within " + seconds + " seconds");
        return failure.getCause();
    }

    private static List<String> namesOf(MigrationReport report) {
        return report.instances().stream().map(r -> r.instance().name()).toList();
    }

    private static List<MigrationStatus> statusesOf(MigrationReport report) {
        return report.instances().stream().map(MigrationInstanceResult::status).toList();
    }

    private static List<Integer> appliedOf(MigrationReport report) {
        return report.instances().stream()
                .map(MigrationInstanceResult::migrationsApplied)
                .toList();
    }

    private static List<String> versionsOf(MigrationReport report) {
        return report.instances().stream()
                .map(MigrationInstanceResult::currentVersion)
                .toList();
    }

    /**
     * The message and the report are written by the framework: neither carries a placeholder value, raw
     * SQL or a JDBC URL. The native cause is kept for diagnosis and is deliberately not scanned.
     */
    private static void assertMessageAndReportAreValueFree(MigrationRunException failure) {
        String visible = failure.getMessage() + " " + failure.report();
        assertFalse(visible.contains(PROBE_VALUE), "the placeholder value leaked into the failure: " + visible);
        assertFalse(visible.contains("INSERT INTO"), "raw SQL leaked into the failure: " + visible);
        assertFalse(visible.contains("jdbc:"), "a JDBC URL leaked into the failure: " + visible);
    }

    /** The occupied-schema failure names the instance and both ways out, in the framework's words. */
    private static void assertBaselineGuidance(MigrationRunException failure, String instance) {
        String message = failure.getMessage();
        assertEquals(Stage.MIGRATION, failure.stage());
        assertTrue(message.contains(instance), "names the instance: " + message);
        assertTrue(message.contains("db.schemas"), "names the separate-schema option: " + message);
        assertTrue(message.contains("baselineOnMigrate=true"), "names the baseline option: " + message);
        assertTrue(BASELINE_VERSION_ZERO.matcher(message).find(), "names baselineVersion 0: " + message);
        assertFalse(message.contains("Found non-empty"), "no raw native message: " + message);
        assertInstanceOf(FlywayException.class, failure.getCause(), "the native failure stays the cause");
    }
}
