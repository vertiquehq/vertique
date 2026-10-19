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
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.MigrationDefinition;
import dev.vertique.db.flyway.RecordingDatabaseSupport.CountingConnector;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves the PostgreSQL safety layer of named migration against a real server: history and default
 * identities are resolved per instance and frozen, unsafe initialization is rejected before any
 * framework write, the bounded conventional-history checks and the in-place owner exception hold, and
 * everything else is left to native Flyway.
 *
 * <p>Each invocation is one scenario on its own database of a shared container, named after the schema
 * existence state and the baseline policy it exercises. Migration roles are created per invocation with
 * unique names and dropped afterwards. "No writes" is checked with a catalog snapshot: every schema,
 * relation and table row count outside the system schemas, compared before and after the run. Callback
 * effects are excluded from that statement, so the audit schema the callback fixtures write to is not
 * part of the snapshot.
 *
 * <p>Some scenarios hold the guard to its promises and fail until the guard exists; the others pin the
 * native behavior the guard must leave alone and already pass. The scenario lists say which is which.
 */
@DisplayName("Named PostgreSQL migration safety")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class FlywayNamedPostgresSafetyIT {

    private static final Logger log = LoggerFactory.getLogger(FlywayNamedPostgresSafetyIT.class);

    private static final String IMAGE = "postgres:16-alpine";
    private static final String FIXTURES = "classpath:db/it-safety/";
    private static final String CONVENTIONAL_HISTORY = "flyway_schema_history";
    private static final String AUDIT_SCHEMA = "s_audit";

    /** Inherited by every instance that sets no placeholders of its own; its value must never surface. */
    private static final String PROBE_KEY = "probe";

    private static final String PROBE_VALUE = "s3cr3t_safety_probe";

    private static final long RUN_TIMEOUT_SECONDS = 15;

    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    private static PostgreSQLContainer postgres;
    private static Vertx vertx;

    /** The database, roles and cleanup of the current invocation. */
    private Fixture db;

    @BeforeAll
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    static void startContainerAndVertx() {
        postgres = new PostgreSQLContainer(IMAGE);
        postgres.start();
        vertx = Vertx.vertx();
    }

    @AfterAll
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    static void stopContainerAndVertx() throws Exception {
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void openFixture(TestInfo invocation) {
        db = new Fixture();
        // Failsafe names invocations by index only; this line maps each index to its scenario.
        log.info("Scenario {}", invocation.getDisplayName());
    }

    @AfterEach
    void closeFixture() throws SQLException {
        db.close();
    }

    /** One scenario: its own Given, When and Then against the invocation's fixture. */
    @FunctionalInterface
    interface Scenario {
        void verify(FlywayNamedPostgresSafetyIT test) throws Exception;
    }

    @ParameterizedTest
    @MethodSource("initializationAndHistoryScenarios")
    @DisplayName("named migration guards initialization and history reuse and keeps native behavior")
    void protectsInitializationAndHistoryReuse(Scenario scenario) throws Exception {
        scenario.verify(this);
        // Every runner-owned guard or probe connection, and every native one, is closed afterwards.
        db.assertNoOpenConnections();
    }

    /**
     * Each invocation holds one scenario of the session lock. The race scenarios wait for a bounded lock for
     * a second and a half and the others run up to three named migrations, so the invocations get a longer
     * budget than the class default.
     */
    @ParameterizedTest
    @MethodSource("lockLifecycleScenarios")
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("named migration serializes wrapper runs with a live, bounded session lock")
    void serializesMigrationsAcrossHistoriesWithBoundedLock(Scenario scenario) throws Exception {
        scenario.verify(this);
        // Every guard connection is closed, and the external holder of the scenario is gone.
        db.assertNoOpenConnections();
    }

    /**
     * Each invocation runs one case in a control world, whose role has the hostile search path but whose
     * schemas hold no shadow objects, and in a hostile world with the shadow objects, and demands identical
     * outcomes. A hostile invocation runs two worlds with up to three migrations each, hence the budget.
     */
    @ParameterizedTest
    @MethodSource("hostileSearchPathScenarios")
    @Timeout(value = 40, unit = TimeUnit.SECONDS)
    @DisplayName("guard catalog checks resist hostile search paths")
    void pinsGuardCatalogResolution(Scenario scenario) throws Exception {
        scenario.verify(this);
        db.assertNoOpenConnections();
    }

    /**
     * Each invocation holds the guard and the default-resolution probes to a bounded network timeout. The
     * blackhole invocation waits out the largest accepted timeout before it calls the run unbounded, hence
     * the budget.
     */
    @ParameterizedTest
    @MethodSource("networkTimeoutScenarios")
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    @DisplayName("guard and default-resolution round trips are bounded by a network timeout")
    void boundsGuardAndProbeRoundTripsWithANetworkTimeout(Scenario scenario) throws Exception {
        scenario.verify(this);
        db.assertNoOpenConnections();
    }

    /** Each invocation runs up to three named migrations, hence the budget. */
    @ParameterizedTest
    @MethodSource("plantedConventionalHistoryScenarios")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    @DisplayName("the conventional-history check is repeated immediately before each instance")
    void rechecksConventionalHistoryBeforeEachInstance(Scenario scenario) throws Exception {
        scenario.verify(this);
        db.assertNoOpenConnections();
    }

    static Stream<Arguments> initializationAndHistoryScenarios() {
        return Stream.of(
                        identifierRuleScenarios(),
                        collisionScenarios(),
                        identityScenarios(),
                        conventionalHistoryScenarios(),
                        ownerReuseScenarios(),
                        mixedSchemaScenarios(),
                        lateMixedStateScenarios(),
                        colocatedBaselineScenarios(),
                        nativeControlScenarios(),
                        validateAndLegacyScenarios())
                .flatMap(Function.identity());
    }

    private static Arguments scenario(String name, Scenario scenario) {
        return argumentSet(name, scenario);
    }

    // =====================================================================================
    // Scenario lists
    // =====================================================================================

    /**
     * Accepted names already pass, because nothing rejects them yet. Rejected names fail until the
     * PostgreSQL rules exist, except the blank ones, which the resolver already rejects.
     */
    private static Stream<Arguments> identifierRuleScenarios() {
        Stream<Arguments> acceptedSchemas = PostgresIdentifierCases.acceptedSchemas().stream()
                .map(name -> scenario(
                        "schema " + describe(name) + " is accepted by instances() without connecting",
                        test -> test.schemaNameIsAccepted(name)));
        Stream<Arguments> rejectedSchemas = PostgresIdentifierCases.rejectedSchemas().stream()
                .map(name -> scenario(
                        "schema " + describe(name) + " is rejected by instances() before connecting",
                        test -> test.schemaNameIsRejected(name)));
        Stream<Arguments> acceptedTables = PostgresIdentifierCases.acceptedHistoryTables().stream()
                .map(name -> scenario(
                        "history table " + describe(name) + " is accepted by instances() without connecting",
                        test -> test.historyTableIsAccepted(name)));
        Stream<Arguments> rejectedTables = PostgresIdentifierCases.rejectedHistoryTables().stream()
                .map(name -> scenario(
                        "history table " + describe(name) + " is rejected by instances() before connecting",
                        test -> test.historyTableIsRejected(name)));
        Stream<Arguments> laterEntry = Stream.of(scenario(
                "a dotted schema later in an inherited managed list is rejected by instances() before connecting",
                FlywayNamedPostgresSafetyIT::dottedSchemaLaterInManagedListIsRejected));
        return Stream.of(acceptedSchemas, rejectedSchemas, acceptedTables, rejectedTables, laterEntry)
                .flatMap(Function.identity());
    }

    /** Every collision scenario fails until the derived-name and database-derived checks exist. */
    private static Stream<Arguments> collisionScenarios() {
        return Stream.of(
                scenario(
                        "history tables 'history' and 'history_pk' in one configured schema are rejected before"
                                + " connecting",
                        test -> test.configuredDerivedNameCollisionIsRejectedBeforeConnecting("history_pk")),
                scenario(
                        "history tables 'history' and 'history_s_idx' in one configured schema are rejected before"
                                + " connecting",
                        test -> test.configuredDerivedNameCollisionIsRejectedBeforeConnecting("history_s_idx")),
                scenario(
                        "two roles defaulting to one schema with 'history' and 'history_pk' are rejected at preflight"
                                + " without writes",
                        test -> test.resolvedDefaultsCollideAtPreflight("history", "history_pk")),
                scenario(
                        "two roles defaulting to one schema with the same history table are rejected at preflight"
                                + " without writes",
                        test -> test.resolvedDefaultsCollideAtPreflight("history", "history")),
                scenario(
                        "a configured schema and a role default resolving to it collide on 'history_s_idx' at"
                                + " preflight without writes",
                        FlywayNamedPostgresSafetyIT::configuredAndResolvedSchemasCollideAtPreflight));
    }

    private static Stream<Arguments> identityScenarios() {
        return Stream.of(
                // Fails until defaults are resolved per instance and frozen: the later role's history follows
                // the schema the earlier instance created.
                scenario(
                        "defaults resolve from each instance's own role and stay frozen when an earlier instance"
                                + " creates a schema in a later role's search path",
                        FlywayNamedPostgresSafetyIT::defaultsResolvePerRoleAndStayFrozen),
                // Native today; fails if defaults were resolved with the root credentials.
                scenario(
                        "two roles with different defaults may use the same history table name",
                        FlywayNamedPostgresSafetyIT::sameHistoryTableInDifferentRoleDefaultsSucceeds),
                // Fails until a missing default is rejected at preflight.
                scenario(
                        "a role whose search path resolves no schema is rejected at preflight without writes",
                        FlywayNamedPostgresSafetyIT::unresolvedDefaultIsRejectedAtPreflight));
    }

    private static Stream<Arguments> conventionalHistoryScenarios() {
        return Stream.of(
                // Fails until the conventional-history probes exist.
                scenario(
                        "conventional history in the guard connection's default schema blocks a new instance before"
                                + " any schema, history or script write",
                        FlywayNamedPostgresSafetyIT::conventionalHistoryInGuardDefaultBlocksInitialization),
                // Fails until the conventional-history probes exist.
                scenario(
                        "conventional history in the first root managed schema blocks a new instance that replaces"
                                + " that list, before any write",
                        FlywayNamedPostgresSafetyIT::conventionalHistoryInRootFirstSchemaBlocksInitialization),
                // Native today; the probes only guard missing histories.
                scenario(
                        "an instance with an existing named history keeps migrating beside a conventional history",
                        FlywayNamedPostgresSafetyIT::existingNamedHistoryIgnoresConventionalHistory),
                // Native today and afterwards: detection is bounded to the conventional name, so a custom-named
                // history is neither found nor protected.
                scenario(
                        "a custom-named single-Flyway history is outside the bounded detection and does not block a"
                                + " new instance",
                        FlywayNamedPostgresSafetyIT::customNamedHistoryIsOutsideBoundedDetection));
    }

    private static Stream<Arguments> ownerReuseScenarios() {
        return Stream.of(
                // Native today; the reuse validation must accept pending owner work.
                scenario(
                        "conventional history in the guard default schema retained by its owner: the pending owner"
                                + " migration and a new instance both run",
                        test -> test.retainedConventionalHistoryAcceptsPendingWorkAndNewInstance(false)),
                scenario(
                        "conventional history in the first root managed schema retained by its owner: the pending"
                                + " owner migration and a new instance both run",
                        test -> test.retainedConventionalHistoryAcceptsPendingWorkAndNewInstance(true)),
                // Fail until the forced reuse validation exists: native validation ignores future rows.
                scenario(
                        "another module's applied migration above the owner's latest version rejects history reuse"
                                + " before writes",
                        test -> test.foreignAppliedMigrationRejectsReuse(true)),
                scenario(
                        "with validateOnMigrate=false the reuse validation still runs and rejects another module's"
                                + " applied migration before writes",
                        test -> test.foreignAppliedMigrationRejectsReuse(false)),
                scenario(
                        "a writing owner callback runs during the rejected reuse validation while the framework"
                                + " writes nothing",
                        FlywayNamedPostgresSafetyIT::ownerCallbackRunsDuringRejectedReuseValidation));
    }

    private static Stream<Arguments> mixedSchemaScenarios() {
        // Fail until the existence-only mixed-set guard exists.
        Stream<Arguments> policies = Stream.of(BaselinePolicy.values())
                .map(policy -> scenario(
                        "mixed set (existing occupied default, missing extra), " + policy.label
                                + ": rejected before writes, native after provisioning",
                        test -> test.mixedSetIsRejectedThenNativeAfterProvisioning(policy)));
        Stream<Arguments> others = Stream.of(
                // Fails until the guard exists; also fails if the guard inspected objects instead of existence.
                scenario(
                        "mixed set (missing default, existing empty extra), baseline false: rejected before writes,"
                                + " native after provisioning",
                        FlywayNamedPostgresSafetyIT::mixedSetWithEmptyExistingSchemaIsRejected),
                // Native today and afterwards.
                scenario(
                        "all-missing set, baseline false: native creation and migration",
                        FlywayNamedPostgresSafetyIT::allMissingSetMigratesNatively),
                scenario(
                        "all-existing empty set, baseline false: native migration",
                        FlywayNamedPostgresSafetyIT::allExistingEmptySetMigratesNatively),
                scenario(
                        "existing named history with a now-missing extra schema keeps migrating natively",
                        FlywayNamedPostgresSafetyIT::existingHistoryWithMissingExtraSchemaMigratesNatively));
        return Stream.concat(policies, others);
    }

    private static Stream<Arguments> lateMixedStateScenarios() {
        return Stream.of(
                // Fails until the per-instance recheck exists.
                scenario(
                        "instances inheriting one root list with extras: the later instance fails with zero writes"
                                + " after the earlier one created the extras, and runs once its whole set is"
                                + " provisioned",
                        FlywayNamedPostgresSafetyIT::inheritedExtrasFailLaterInstanceUntilProvisioned),
                // Native today and afterwards.
                scenario(
                        "instances with disjoint extra schemas both initialize without provisioning",
                        FlywayNamedPostgresSafetyIT::disjointExtrasInitializeNatively),
                // Fails until the per-instance recheck exists.
                scenario(
                        "a schema created by an earlier instance's script after it commits fails the later instance"
                                + " with zero writes and leaves later entries not run",
                        FlywayNamedPostgresSafetyIT::schemaCreatedByEarlierScriptFailsLaterInstance));
    }

    /** Native baseline behavior on a shared schema; all pass today and must keep passing. */
    private static Stream<Arguments> colocatedBaselineScenarios() {
        return Stream.of(
                scenario(
                        "fresh shared schema, two co-located instances with root baseline true/0: both histories and"
                                + " both first scripts, a rerun applies nothing",
                        FlywayNamedPostgresSafetyIT::colocatedInstancesWithBaselineZeroBothRun),
                scenario(
                        "fresh shared schema, default baseline false: the second co-located instance fails safely"
                                + " without marking its first script applied",
                        FlywayNamedPostgresSafetyIT::colocatedSecondInstanceFailsSafelyByDefault),
                scenario(
                        "occupied shared schema, root baseline true with default version 1: the second instance skips"
                                + " its first script natively",
                        FlywayNamedPostgresSafetyIT::rootBaselineVersionOneSkipsFirstScript),
                scenario(
                        "root baseline true/0 with an instance override false: the overriding instance fails safely",
                        FlywayNamedPostgresSafetyIT::instanceBaselineFalseOverridesRootTrue));
    }

    /** Native behavior the guard must leave alone; all pass today. */
    private static Stream<Arguments> nativeControlScenarios() {
        return Stream.of(
                scenario(
                        "identical migration bytes with different placeholders create distinct histories and objects",
                        FlywayNamedPostgresSafetyIT::identicalBytesWithDifferentPlaceholdersStayDistinct),
                scenario(
                        "fallback-reference control: an unqualified reference resolves through native search-path"
                                + " fallback, which is no SQL isolation",
                        FlywayNamedPostgresSafetyIT::unqualifiedReferenceResolvesThroughNativeFallback),
                scenario(
                        "a db.schemas entry that disagrees with the explicit managed list is rejected before"
                                + " connecting",
                        FlywayNamedPostgresSafetyIT::disagreeingSchemaConfigurationIsRejectedBeforeConnecting));
    }

    /** VALIDATE and legacy runs never meet the guard; all pass today. */
    private static Stream<Arguments> validateAndLegacyScenarios() {
        return Stream.of(
                scenario(
                        "named VALIDATE against an absent history leaves the catalog unchanged",
                        FlywayNamedPostgresSafetyIT::validateAgainstAbsentHistoryWritesNothing),
                scenario(
                        "named VALIDATE with a writing callback runs it natively while the framework creates no schema"
                                + " or history",
                        FlywayNamedPostgresSafetyIT::validateRunsWritingCallbackNatively),
                scenario(
                        "named VALIDATE with a mixed set beside a conventional history keeps native validation",
                        FlywayNamedPostgresSafetyIT::validateIsFreeOfTheInitializationGuard),
                scenario(
                        "legacy migration with a mixed set opens no guard or probe connection",
                        FlywayNamedPostgresSafetyIT::legacyMigrationOpensNoGuardConnection));
    }

    // =====================================================================================
    // Identifier rules: checked by instances() before any connection
    // =====================================================================================

    private void schemaNameIsAccepted(String schema) {
        // Given: the candidate is the instance's db.schemas placement; the neutral binding accepts it as is
        CountingConnector connector = new CountingConnector();
        FlywayMigrationRunner runner =
                unconnectedRunner(connector, named(instance("alpha")), placement("alpha", schema), alpha());

        // When
        List<MigrationInstanceView> views = runner.instances();

        // Then
        assertEquals(schema, views.get(0).schema());
        assertEquals(0, connector.attempts(), "instances() must not connect");
    }

    private void schemaNameIsRejected(String schema) {
        // Given: a nonblank candidate is the db.schemas placement, which stays vendor-neutral; a blank one
        // cannot be a placement, so it reaches the runner as an explicit managed list
        CountingConnector connector = new CountingConnector();
        boolean blank = schema.isBlank();
        DbSchemas placement = blank ? DbSchemas.empty() : placement("alpha", schema);
        InstanceSettings alpha = instance("alpha");
        if (blank) {
            alpha.schemas(List.of(schema));
        }
        FlywayMigrationRunner runner = unconnectedRunner(connector, named(alpha), placement, alpha());

        // When
        MigrationRunException failure =
                assertThrows(MigrationRunException.class, runner::instances, "the schema name must be rejected");

        // Then
        assertConfigurationRejectionWithoutConnecting(failure, connector, schema);
    }

    private void historyTableIsAccepted(String table) {
        // Given
        CountingConnector connector = new CountingConnector();
        FlywayMigrationRunner runner = unconnectedRunner(
                connector, named(instance("alpha").table(table)), placement("alpha", "s_tables"), alpha());

        // When
        List<MigrationInstanceView> views = runner.instances();

        // Then
        assertEquals(table, views.get(0).table());
        assertEquals(0, connector.attempts(), "instances() must not connect");
    }

    private void historyTableIsRejected(String table) {
        // Given
        CountingConnector connector = new CountingConnector();
        FlywayMigrationRunner runner = unconnectedRunner(
                connector, named(instance("alpha").table(table)), placement("alpha", "s_tables"), alpha());

        // When
        MigrationRunException failure =
                assertThrows(MigrationRunException.class, runner::instances, "the history table must be rejected");

        // Then
        assertConfigurationRejectionWithoutConnecting(failure, connector, table);
    }

    private void dottedSchemaLaterInManagedListIsRejected() {
        // Given: the placement is valid, but the inherited root list adds a dotted extra schema
        CountingConnector connector = new CountingConnector();
        FlywayConfig config = namedRoot()
                .schemas(List.of("a.b"))
                .instances(List.of(instance("alpha").build()))
                .build();
        FlywayMigrationRunner runner = unconnectedRunner(connector, config, placement("alpha", "s_first"), alpha());

        // When
        MigrationRunException failure =
                assertThrows(MigrationRunException.class, runner::instances, "every managed schema is checked");

        // Then
        assertConfigurationRejectionWithoutConnecting(failure, connector, "a.b");
    }

    // =====================================================================================
    // History collisions
    // =====================================================================================

    private void configuredDerivedNameCollisionIsRejectedBeforeConnecting(String secondTable) throws Exception {
        // Given: two instances placed in one schema; the second history table is a relation native Flyway
        // derives from the first
        CountingConnector connector = new CountingConnector();
        FlywayMigrationRunner runner = countingRunner(
                connector,
                named(instance("alpha").table("history"), instance("beta").table(secondTable)),
                placement("alpha", "s_common", "beta", "s_common"),
                alpha(),
                beta());
        CatalogSnapshot before = db.snapshot();

        // When: the configuration is inspected
        MigrationRunException inspected =
                assertThrows(MigrationRunException.class, runner::instances, "the derived-name collision is known");

        // Then
        assertEquals("CONFIGURATION", inspected.failureCode());
        assertEquals("instances", inspected.operation());
        assertNull(inspected.report());
        assertNamesOneOf(inspected, "alpha", "beta");
        assertHygienic(inspected, "s_common", secondTable);

        // When: the batch runs
        MigrationRunException run = rejected(runner.run(vertx));

        // Then: rejected before connecting, every entry not run, nothing written
        assertEquals("CONFIGURATION", run.failureCode());
        assertEquals("run", run.operation());
        assertEquals(notRun("alpha", "beta"), outcomes(run.report()));
        db.assertCatalogUnchangedSince(before);
        assertEquals(0, connector.attempts(), "no connection may be opened for a known collision");
    }

    private void resolvedDefaultsCollideAtPreflight(String firstTable, String secondTable) throws Exception {
        // Given: two migration roles whose connections default to one shared schema, and no managed lists
        db.createSchema("s_common");
        Role beta = db.role("beta", "s_common");
        Role gamma = db.role("gamma", "s_common");
        db.grantSchema("s_common", beta, gamma);
        FlywayMigrationRunner runner = runner(
                named(
                        instance("beta").table(firstTable).credentials(beta),
                        instance("gamma").table(secondTable).credentials(gamma)),
                DbSchemas.empty(),
                beta(),
                gamma());

        // When: inspected without connecting, the defaults are still unknown
        List<MigrationInstanceView> views = runner.instances();

        // Then: nothing is decided yet
        assertEquals(Arrays.asList(null, null), schemasOf(views), "connection-free views mark unknown defaults");

        // When: the batch runs
        CatalogSnapshot before = db.snapshot();
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: the resolved identities collide before any write
        assertPreflightRejection(failure, "beta", "gamma");
        assertNamesOneOf(failure, "beta", "gamma");
        assertHygienic(failure, "s_common", "history_pk");
        db.assertCatalogUnchangedSince(before);
    }

    private void configuredAndResolvedSchemasCollideAtPreflight() throws Exception {
        // Given: one instance names the schema, the other's role defaults to it
        db.createSchema("s_common");
        Role beta = db.role("beta", "s_common");
        db.grantSchema("s_common", beta);
        FlywayMigrationRunner runner = runner(
                named(
                        instance("alpha").schemas("s_common").table("history"),
                        instance("beta").table("history_s_idx").credentials(beta)),
                DbSchemas.empty(),
                alpha(),
                beta());

        // When: inspected, only the configured identity is known
        List<MigrationInstanceView> views = runner.instances();
        assertEquals(Arrays.asList("s_common", null), schemasOf(views));

        // When: the batch runs
        CatalogSnapshot before = db.snapshot();
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then
        assertPreflightRejection(failure, "alpha", "beta");
        assertNamesOneOf(failure, "alpha", "beta");
        assertHygienic(failure, "s_common", "history_s_idx");
        db.assertCatalogUnchangedSince(before);
    }

    // =====================================================================================
    // Per-instance default identities
    // =====================================================================================

    private void defaultsResolvePerRoleAndStayFrozen() throws Exception {
        // Given: beta's role searches a schema that does not exist yet, then its home schema
        Role beta = db.role("beta", "s_beta_late", "s_beta_home");
        db.createSchemaOwnedBy("s_beta_home", beta);
        // And: gamma's role defaults elsewhere, but gamma names its schema explicitly
        Role gamma = db.role("gamma", "s_gamma_elsewhere");
        db.createSchemaOwnedBy("s_gamma_elsewhere", gamma);
        // And: alpha, running first with the pool credentials, creates the schema beta's role searches first
        FlywayMigrationRunner runner = runner(
                named(
                        instance("alpha")
                                .placeholders(Map.of("later_schema", "s_beta_late", "later_owner", beta.name())),
                        instance("beta").credentials(beta),
                        instance("gamma").schemas("s_gamma_home").credentials(gamma)),
                placement("alpha", "s_alpha"),
                declaration("alpha", "creator"),
                beta(),
                gamma());

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: each identity comes from the instance's own role or list and is the one reported
        assertEquals(
                List.of(
                        "alpha MIGRATED applied=1 current=1 schema=s_alpha",
                        "beta MIGRATED applied=1 current=1 schema=s_beta_home",
                        "gamma MIGRATED applied=1 current=1 schema=s_gamma_home"),
                outcomesWithSchema(report));
        // And: beta's history and objects stay in the default resolved before alpha ran
        assertTrue(db.schemaExists("s_beta_late"), "alpha created the schema beta's role searches first");
        assertEquals(List.of(), db.relationsIn("s_beta_late"), "nothing of beta may follow the later schema");
        assertEquals(List.of("1"), db.appliedVersions("s_beta_home", history("beta")));
        assertTrue(db.tableExists("s_beta_home", "beta_item"));
        // And: gamma's explicit list wins over its role's default
        assertEquals(List.of("1"), db.appliedVersions("s_gamma_home", history("gamma")));
        assertEquals(List.of(), db.relationsIn("s_gamma_elsewhere"));
        // And: every view names its own non-secret user and credential source
        assertEquals(
                List.of(
                        "alpha " + postgres.getUsername() + " DB_POOL",
                        "beta " + beta.name() + " INSTANCE",
                        "gamma " + gamma.name() + " INSTANCE"),
                report.instances().stream()
                        .map(r -> r.instance().name() + " " + r.instance().user() + " "
                                + r.instance().credentialSource())
                        .toList());
    }

    private void sameHistoryTableInDifferentRoleDefaultsSucceeds() throws Exception {
        // Given: two roles defaulting to their own schemas and one history table name for both
        Role beta = db.role("beta", "s_beta_home");
        Role gamma = db.role("gamma", "s_gamma_home");
        db.createSchemaOwnedBy("s_beta_home", beta);
        db.createSchemaOwnedBy("s_gamma_home", gamma);
        FlywayMigrationRunner runner = runner(
                named(
                        instance("beta").table("shared_history").credentials(beta),
                        instance("gamma").table("shared_history").credentials(gamma)),
                DbSchemas.empty(),
                beta(),
                gamma());

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: the identities differ, so both histories exist side by side
        assertEquals(
                List.of(
                        "beta MIGRATED applied=1 current=1 schema=s_beta_home",
                        "gamma MIGRATED applied=1 current=1 schema=s_gamma_home"),
                outcomesWithSchema(report));
        assertEquals(List.of("1"), db.appliedVersions("s_beta_home", "shared_history"));
        assertEquals(List.of("1"), db.appliedVersions("s_gamma_home", "shared_history"));
    }

    private void unresolvedDefaultIsRejectedAtPreflight() throws Exception {
        // Given: the role searches only a schema that does not exist, so its connection has no default
        Role beta = db.role("beta", "s_beta_nowhere");
        FlywayMigrationRunner runner = runner(named(instance("beta").credentials(beta)), DbSchemas.empty(), beta());
        CatalogSnapshot before = db.snapshot();

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then
        assertPreflightRejection(failure, "beta");
        assertNamesOneOf(failure, "beta");
        assertHygienic(failure, "s_beta_nowhere");
        db.assertCatalogUnchangedSince(before);
    }

    // =====================================================================================
    // Conventional history
    // =====================================================================================

    private void conventionalHistoryInGuardDefaultBlocksInitialization() throws Exception {
        // Given: a native single-Flyway history in the default schema of the pool credentials
        db.nativeHistory(null, "legacy-app");
        FlywayMigrationRunner runner = runner(named(instance("alpha")), placement("alpha", "s_alpha"), alpha());
        CatalogSnapshot before = db.snapshot();

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: no schema, history or first script is written
        assertPreflightRejection(failure, "alpha");
        assertHygienic(failure, "s_alpha");
        db.assertCatalogUnchangedSince(before);
    }

    private void conventionalHistoryInRootFirstSchemaBlocksInitialization() throws Exception {
        // Given: a native single-Flyway history in the first root managed schema, which the instance replaces
        db.nativeHistory("s_legacy", "legacy-app");
        FlywayConfig config = namedRoot()
                .schemas(List.of("s_legacy"))
                .instances(List.of(instance("alpha").schemas("s_alpha").build()))
                .build();
        FlywayMigrationRunner runner = runner(config, placement("alpha", "s_alpha"), alpha());
        CatalogSnapshot before = db.snapshot();

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then
        assertPreflightRejection(failure, "alpha");
        assertHygienic(failure, "s_alpha", "s_legacy");
        db.assertCatalogUnchangedSince(before);
    }

    private void customNamedHistoryIsOutsideBoundedDetection() throws Exception {
        // Given: a native single-Flyway history under a custom table name in the guard default schema
        db.nativeHistoryNamed(null, "app_history", "legacy-app");
        FlywayMigrationRunner runner = runner(named(instance("alpha")), placement("alpha", "s_alpha"), alpha());

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: the new instance initializes, and the custom history is left as it was
        assertEquals(List.of("alpha MIGRATED applied=1 current=1 schema=s_alpha"), outcomesWithSchema(report));
        assertEquals(List.of("1"), db.appliedVersions("public", "app_history"));
    }

    private void existingNamedHistoryIgnoresConventionalHistory() throws Exception {
        // Given: the instance already has its own history, and a conventional history appears later
        succeeded(runner(named(instance("alpha")), placement("alpha", "s_alpha"), declaration("alpha", "owner-v1"))
                .run(vertx));
        db.nativeHistory(null, "legacy-app");

        // When: its next release runs
        MigrationReport report = succeeded(
                runner(named(instance("alpha")), placement("alpha", "s_alpha"), declaration("alpha", "owner-v2"))
                        .run(vertx));

        // Then
        assertEquals(List.of("alpha MIGRATED applied=1 current=2"), outcomes(report));
        assertEquals(List.of("1", "2"), db.appliedVersions("s_alpha", history("alpha")));
    }

    // =====================================================================================
    // In-place owner reuse of a conventional history
    // =====================================================================================

    private void retainedConventionalHistoryAcceptsPendingWorkAndNewInstance(boolean rootFirstSchema) throws Exception {
        // Given: the owner's first release wrote a conventional history natively
        String ownerSchema = rootFirstSchema ? "s_legacy" : "public";
        db.nativeHistory(rootFirstSchema ? "s_legacy" : null, "owner-v1");
        // And: the owner retains it explicitly with a pending second migration, beside a new instance
        FlywayConfig.FlywayConfigBuilder root = namedRoot();
        if (rootFirstSchema) {
            root.schemas(List.of("s_legacy"));
        }
        FlywayConfig config = root.instances(List.of(
                        instance("fresh").schemas("s_fresh").build(),
                        instance("owner").table(CONVENTIONAL_HISTORY).build()))
                .build();
        FlywayMigrationRunner runner = runner(
                config, placement("fresh", "s_fresh"), declaration("fresh", "alpha"), declaration("owner", "owner-v2"));

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: both succeed, the owner continuing in its own history
        assertEquals(
                List.of(
                        "fresh MIGRATED applied=1 current=1 schema=s_fresh",
                        "owner MIGRATED applied=1 current=2 schema=" + ownerSchema),
                outcomesWithSchema(report));
        assertEquals(List.of("1", "2"), db.appliedVersions(ownerSchema, CONVENTIONAL_HISTORY));
        assertTrue(db.tableExists("s_fresh", "alpha_item"));
    }

    private void foreignAppliedMigrationRejectsReuse(boolean validateOnMigrate) throws Exception {
        // Given: the conventional history holds the owner's versions 1 and 2 and, above them, version 5
        // applied by another module whose scripts the owner does not have
        db.nativeHistory(null, "owner-v2", "foreign");
        assertEquals(List.of("1", "2", "5"), db.appliedVersions("public", CONVENTIONAL_HISTORY));
        FlywayConfig config = namedRoot()
                .validateOnMigrate(validateOnMigrate)
                .instances(List.of(
                        instance("fresh").schemas("s_fresh").build(),
                        instance("owner").table(CONVENTIONAL_HISTORY).build()))
                .build();
        FlywayMigrationRunner runner = runner(
                config, placement("fresh", "s_fresh"), declaration("fresh", "alpha"), declaration("owner", "owner-v2"));
        CatalogSnapshot before = db.snapshot();

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: rejected before the new instance or the owner writes anything
        assertPreflightRejection(failure, "fresh", "owner");
        assertHygienic(failure, "s_fresh");
        db.assertCatalogUnchangedSince(before);
    }

    private void ownerCallbackRunsDuringRejectedReuseValidation() throws Exception {
        // Given: the owner's resources include callbacks that write an audit row, and the history it would
        // retain holds another module's applied migration
        db.createAuditTable();
        db.nativeHistory(null, "owner-callback", "foreign");
        db.clearAudit();
        FlywayConfig config =
                named(instance("fresh").schemas("s_fresh"), instance("owner").table(CONVENTIONAL_HISTORY));
        FlywayMigrationRunner runner = runner(
                config,
                placement("fresh", "s_fresh"),
                declaration("fresh", "alpha"),
                declaration("owner", "owner-callback"));
        CatalogSnapshot before = db.snapshot();

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: the framework writes nothing
        assertPreflightRejection(failure, "fresh", "owner");
        db.assertCatalogUnchangedSince(before);
        // And: the native validation ran the owner's callback, whose effects no no-write statement covers
        assertTrue(db.auditEvents() > 0, "the owner callback runs natively during the reuse validation");
    }

    // =====================================================================================
    // Mixed existing and missing managed schemas
    // =====================================================================================

    /** Baseline policies, each set where an operator would set it. */
    private enum BaselinePolicy {
        FALSE_BY_DEFAULT("baseline false (root default)"),
        TRUE_ZERO_ON_INSTANCE("baseline true/0 (instance)"),
        TRUE_ONE_ON_ROOT("baseline true/1 (root, default version)");

        final String label;

        BaselinePolicy(String label) {
            this.label = label;
        }
    }

    private void mixedSetIsRejectedThenNativeAfterProvisioning(BaselinePolicy policy) throws Exception {
        // Given: the default schema exists and holds data, the extra managed schema is missing
        db.createSchema("s_home");
        db.occupy("s_home");
        FlywayMigrationRunner runner = runnerWithPolicy(policy);
        CatalogSnapshot before = db.snapshot();

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: rejected before any native write, whatever the baseline policy
        assertPreflightRejection(failure, "alpha");
        assertNamesOneOf(failure, "alpha");
        assertHygienic(failure, "s_home", "s_extra");
        db.assertCatalogUnchangedSince(before);

        // When: the operator provisions the missing schema and reruns with the unchanged policy
        db.createSchema("s_extra");

        // Then: the native baseline rules decide
        assertNativeBaselineOutcome(runner, policy);
    }

    /** One instance managing the default schema and an extra schema, with the policy set where it belongs. */
    private FlywayMigrationRunner runnerWithPolicy(BaselinePolicy policy) throws SQLException {
        FlywayConfig.FlywayConfigBuilder root = namedRoot();
        InstanceSettings alpha = instance("alpha").schemas("s_home", "s_extra");
        switch (policy) {
            case TRUE_ZERO_ON_INSTANCE -> alpha.baseline(true, "0");
            case TRUE_ONE_ON_ROOT -> root.baselineOnMigrate(true);
            case FALSE_BY_DEFAULT -> {
                // the root default stays false
            }
        }
        return runner(root.instances(List.of(alpha.build())).build(), placement("alpha", "s_home"), alpha());
    }

    /** Native outcome once every managed schema exists and the default schema holds data. */
    private void assertNativeBaselineOutcome(FlywayMigrationRunner runner, BaselinePolicy policy) throws Exception {
        switch (policy) {
            case FALSE_BY_DEFAULT -> {
                MigrationRunException occupied = rejected(runner.run(vertx));
                assertEquals("MIGRATION", occupied.failureCode());
                assertEquals(List.of("alpha FAILED applied=0 current=null"), outcomes(occupied.report()));
                assertEquals(List.of(), db.historyRows("s_home", history("alpha")), "nothing marked applied");
                assertFalse(db.tableExists("s_home", "alpha_item"));
            }
            case TRUE_ZERO_ON_INSTANCE -> {
                assertEquals(List.of("alpha MIGRATED applied=1 current=1"), outcomes(succeeded(runner.run(vertx))));
                assertEquals(List.of("0 BASELINE", "1 SQL"), db.historyRows("s_home", history("alpha")));
                assertTrue(db.tableExists("s_home", "alpha_item"));
            }
            case TRUE_ONE_ON_ROOT -> {
                assertEquals(List.of("alpha MIGRATED applied=0 current=1"), outcomes(succeeded(runner.run(vertx))));
                assertEquals(List.of("1 BASELINE"), db.historyRows("s_home", history("alpha")));
                assertFalse(db.tableExists("s_home", "alpha_item"), "version 1 is at the baseline and skipped");
            }
        }
        assertEquals(1, db.countRows("s_home.pre_existing"), "existing data is untouched");
    }

    private void mixedSetWithEmptyExistingSchemaIsRejected() throws Exception {
        // Given: the default schema is missing and the extra managed schema exists without any object
        db.createSchema("s_extra");
        FlywayMigrationRunner runner =
                runner(named(instance("alpha").schemas("s_home", "s_extra")), placement("alpha", "s_home"), alpha());
        CatalogSnapshot before = db.snapshot();

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: existence alone decides, not whether the existing schema holds objects
        assertPreflightRejection(failure, "alpha");
        assertNamesOneOf(failure, "alpha");
        assertHygienic(failure, "s_home", "s_extra");
        db.assertCatalogUnchangedSince(before);

        // When: the operator provisions the missing schema and reruns
        db.createSchema("s_home");
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: the all-existing empty set migrates natively
        assertEquals(List.of("alpha MIGRATED applied=1 current=1"), outcomes(report));
        assertTrue(db.tableExists("s_home", "alpha_item"));
    }

    private void allMissingSetMigratesNatively() throws Exception {
        // Given: neither managed schema exists
        FlywayMigrationRunner runner =
                runner(named(instance("alpha").schemas("s_home", "s_extra")), placement("alpha", "s_home"), alpha());

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: native Flyway creates both and migrates
        assertEquals(List.of("alpha MIGRATED applied=1 current=1 schema=s_home"), outcomesWithSchema(report));
        assertTrue(db.schemaExists("s_extra"));
        assertEquals(List.of("1"), db.appliedVersions("s_home", history("alpha")));
        assertTrue(db.tableExists("s_home", "alpha_item"));
    }

    private void allExistingEmptySetMigratesNatively() throws Exception {
        // Given: both managed schemas exist and are empty
        db.createSchema("s_home");
        db.createSchema("s_extra");
        FlywayMigrationRunner runner =
                runner(named(instance("alpha").schemas("s_home", "s_extra")), placement("alpha", "s_home"), alpha());

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then
        assertEquals(List.of("alpha MIGRATED applied=1 current=1 schema=s_home"), outcomesWithSchema(report));
        assertEquals(List.of("1"), db.appliedVersions("s_home", history("alpha")));
    }

    private void existingHistoryWithMissingExtraSchemaMigratesNatively() throws Exception {
        // Given: the instance migrated once, then its extra schema was dropped
        FlywayConfig config = named(instance("alpha").schemas("s_home", "s_extra"));
        succeeded(runner(config, placement("alpha", "s_home"), declaration("alpha", "owner-v1"))
                .run(vertx));
        db.execute("DROP SCHEMA s_extra");

        // When: its next release runs
        MigrationReport report =
                succeeded(runner(config, placement("alpha", "s_home"), declaration("alpha", "owner-v2"))
                        .run(vertx));

        // Then: an existing history needs no initialization guard
        assertEquals(List.of("alpha MIGRATED applied=1 current=2"), outcomes(report));
        assertEquals(List.of("1", "2"), db.appliedVersions("s_home", history("alpha")));
    }

    // =====================================================================================
    // Mixed state that appears while the batch runs
    // =====================================================================================

    private void inheritedExtrasFailLaterInstanceUntilProvisioned() throws Exception {
        // Given: a fresh install where alpha and beta inherit one root list with an extra schema, and gamma
        // names its own list
        FlywayConfig config = namedRoot()
                .schemas(List.of("s_shared_extra"))
                .instances(List.of(
                        instance("alpha").build(),
                        instance("beta").build(),
                        instance("gamma").schemas("s_gamma").build()))
                .build();
        DbSchemas placement = placement("alpha", "s_alpha", "beta", "s_beta", "gamma", "s_gamma");
        FlywayMigrationRunner runner = runner(config, placement, alpha(), beta(), gamma());

        // When: alpha creates the shared extra schema, so beta's set mixes it with beta's missing schema
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: alpha's result stays, beta fails without writing, gamma is not attempted
        assertEquals("PREFLIGHT", failure.failureCode(), "failure code of: " + failure.getMessage());
        assertEquals("run", failure.operation());
        assertEquals(
                List.of(
                        "alpha MIGRATED applied=1 current=1",
                        "beta FAILED applied=0 current=null",
                        "gamma NOT_RUN applied=0 current=null"),
                outcomes(failure.report()));
        assertNamesOneOf(failure, "beta");
        assertHygienic(failure, "s_beta", "s_shared_extra");
        assertTrue(db.tableExists("s_alpha", "alpha_item"));
        assertFalse(db.schemaExists("s_beta"), "beta's own schema is not created");
        assertEquals(List.of(), db.relationsIn("s_shared_extra"), "beta writes no history into the extra schema");
        assertFalse(db.schemaExists("s_gamma"), "later entries are not attempted");

        // When: the operator provisions every schema of beta's set and reruns
        db.createSchema("s_beta");
        MigrationReport retry = succeeded(runner.run(vertx));

        // Then
        assertEquals(
                List.of(
                        "alpha MIGRATED applied=0 current=1",
                        "beta MIGRATED applied=1 current=1",
                        "gamma MIGRATED applied=1 current=1"),
                outcomes(retry));
    }

    private void disjointExtrasInitializeNatively() throws Exception {
        // Given: a fresh install where each instance has its own extra schema
        FlywayMigrationRunner runner = runner(
                named(
                        instance("alpha").schemas("s_alpha", "s_alpha_extra"),
                        instance("beta").schemas("s_beta", "s_beta_extra")),
                placement("alpha", "s_alpha", "beta", "s_beta"),
                alpha(),
                beta());

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: no provisioning is needed
        assertEquals(
                List.of("alpha MIGRATED applied=1 current=1", "beta MIGRATED applied=1 current=1"), outcomes(report));
        assertTrue(db.schemaExists("s_alpha_extra"));
        assertTrue(db.schemaExists("s_beta_extra"));
    }

    private void schemaCreatedByEarlierScriptFailsLaterInstance() throws Exception {
        // Given: alpha's script creates beta's extra schema once alpha commits; beta's set is missing entirely
        // at preflight, and gamma comes after beta
        FlywayMigrationRunner runner = runner(
                named(
                        instance("alpha")
                                .placeholders(
                                        Map.of("later_schema", "s_beta_extra", "later_owner", postgres.getUsername())),
                        instance("beta").schemas("s_beta", "s_beta_extra"),
                        instance("gamma")),
                placement("alpha", "s_alpha", "beta", "s_beta", "gamma", "s_gamma"),
                declaration("alpha", "creator"),
                beta(),
                gamma());

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: alpha's commit stays, beta fails with zero writes, gamma is not run
        assertEquals("PREFLIGHT", failure.failureCode(), "failure code of: " + failure.getMessage());
        assertEquals("run", failure.operation());
        assertEquals(
                List.of(
                        "alpha MIGRATED applied=1 current=1",
                        "beta FAILED applied=0 current=null",
                        "gamma NOT_RUN applied=0 current=null"),
                outcomes(failure.report()));
        assertNamesOneOf(failure, "beta");
        assertHygienic(failure, "s_beta", "s_beta_extra");
        assertTrue(db.tableExists("s_alpha", "creator_item"));
        assertFalse(db.schemaExists("s_beta"), "beta's own schema is not created");
        assertEquals(List.of(), db.relationsIn("s_beta_extra"), "beta writes nothing into the new schema");
        assertFalse(db.schemaExists("s_gamma"), "later entries are not attempted");
    }

    // =====================================================================================
    // Co-located instances and native baseline rules
    // =====================================================================================

    private void colocatedInstancesWithBaselineZeroBothRun() throws Exception {
        // Given: two instances placed in one fresh schema, root baseline true with version 0
        FlywayConfig config = namedRoot()
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .instances(List.of(instance("alpha").build(), instance("beta").build()))
                .build();
        FlywayMigrationRunner runner =
                runner(config, placement("alpha", "s_shared", "beta", "s_shared"), alpha(), beta());

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: both histories exist and both first scripts ran
        assertEquals(
                List.of(
                        "alpha MIGRATED applied=1 current=1 schema=s_shared",
                        "beta MIGRATED applied=1 current=1 schema=s_shared"),
                outcomesWithSchema(report));
        assertEquals(List.of("1"), db.appliedVersions("s_shared", history("alpha")));
        assertEquals(List.of("1"), db.appliedVersions("s_shared", history("beta")));
        assertTrue(db.tableExists("s_shared", "alpha_item"));
        assertTrue(db.tableExists("s_shared", "beta_item"));

        // When: the batch runs again
        MigrationReport rerun = succeeded(runner.run(vertx));

        // Then
        assertEquals(
                List.of("alpha MIGRATED applied=0 current=1", "beta MIGRATED applied=0 current=1"), outcomes(rerun));
    }

    private void colocatedSecondInstanceFailsSafelyByDefault() throws Exception {
        // Given: two instances placed in one fresh schema with the default baseline policy
        FlywayMigrationRunner runner = runner(
                named(instance("alpha"), instance("beta")),
                placement("alpha", "s_shared", "beta", "s_shared"),
                alpha(),
                beta());

        // When: alpha occupies the schema, then beta finds it occupied without its own history
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: beta fails natively with the baseline guidance, and its first script is not marked applied
        assertEquals("MIGRATION", failure.failureCode());
        assertEquals(
                List.of("alpha MIGRATED applied=1 current=1", "beta FAILED applied=0 current=null"),
                outcomes(failure.report()));
        assertTrue(failure.getMessage().contains("baselineOnMigrate=true"), failure.getMessage());
        assertEquals(List.of(), db.historyRows("s_shared", history("beta")));
        assertFalse(db.tableExists("s_shared", "beta_item"));
    }

    private void rootBaselineVersionOneSkipsFirstScript() throws Exception {
        // Given: root baseline true with the default version 1, two instances in one fresh schema
        FlywayConfig config = namedRoot()
                .baselineOnMigrate(true)
                .instances(List.of(instance("alpha").build(), instance("beta").build()))
                .build();
        FlywayMigrationRunner runner =
                runner(config, placement("alpha", "s_shared", "beta", "s_shared"), alpha(), beta());

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: beta baselines at version 1 in the occupied schema and skips its first script
        assertEquals(
                List.of("alpha MIGRATED applied=1 current=1", "beta MIGRATED applied=0 current=1"), outcomes(report));
        assertEquals(List.of("1 BASELINE"), db.historyRows("s_shared", history("beta")));
        assertFalse(db.tableExists("s_shared", "beta_item"));
    }

    private void instanceBaselineFalseOverridesRootTrue() throws Exception {
        // Given: root baseline true/0, but beta overrides it with false
        FlywayConfig config = namedRoot()
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .instances(List.of(
                        instance("alpha").build(),
                        instance("beta").baseline(false, null).build()))
                .build();
        FlywayMigrationRunner runner =
                runner(config, placement("alpha", "s_shared", "beta", "s_shared"), alpha(), beta());

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then
        assertEquals("MIGRATION", failure.failureCode());
        assertEquals(
                List.of("alpha MIGRATED applied=1 current=1", "beta FAILED applied=0 current=null"),
                outcomes(failure.report()));
        assertEquals(List.of(), db.historyRows("s_shared", history("beta")));
        assertFalse(db.tableExists("s_shared", "beta_item"));
    }

    // =====================================================================================
    // Native controls
    // =====================================================================================

    private void identicalBytesWithDifferentPlaceholdersStayDistinct() throws Exception {
        // Given: two locations holding byte-identical scripts, rendered with different placeholder values
        FlywayConfig config = namedRoot()
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .instances(List.of(
                        instance("left").placeholders(Map.of("twin", "left")).build(),
                        instance("right").placeholders(Map.of("twin", "right")).build()))
                .build();
        FlywayMigrationRunner runner = runner(
                config,
                placement("left", "s_twins", "right", "s_twins"),
                declaration("left", "twin-left"),
                declaration("right", "twin-right"));

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: each instance has its own history and its own object
        assertEquals(
                List.of("left MIGRATED applied=1 current=1", "right MIGRATED applied=1 current=1"), outcomes(report));
        assertEquals(List.of("1 SQL V1__create_twin.sql"), db.appliedScripts("s_twins", history("left")));
        assertEquals(List.of("1 SQL V1__create_twin.sql"), db.appliedScripts("s_twins", history("right")));
        assertTrue(db.tableExists("s_twins", "twin_left"));
        assertTrue(db.tableExists("s_twins", "twin_right"));
    }

    private void unqualifiedReferenceResolvesThroughNativeFallback() throws Exception {
        // Given: a lookup table that exists only in the connection's original default schema
        db.execute("CREATE TABLE public.fallback_lookup (id INTEGER PRIMARY KEY);"
                + " INSERT INTO public.fallback_lookup (id) VALUES (7);");
        FlywayMigrationRunner runner =
                runner(named(instance("alpha")), placement("alpha", "s_fallback"), declaration("alpha", "fallback"));

        // When: a migration reads it without qualification
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: the reference resolves through the retained search-path fallback; new objects still land in
        // the instance's schema. This documents native resolution; it is not SQL isolation.
        assertEquals(List.of("alpha MIGRATED applied=1 current=1 schema=s_fallback"), outcomesWithSchema(report));
        assertEquals(1, db.countRows("s_fallback.fallback_copy"));
        assertFalse(db.tableExists("public", "fallback_copy"));
    }

    private void disagreeingSchemaConfigurationIsRejectedBeforeConnecting() throws Exception {
        // Given: the placement names one schema and the explicit managed list starts with another
        CountingConnector connector = new CountingConnector();
        FlywayMigrationRunner runner = countingRunner(
                connector, named(instance("alpha").schemas("s_other")), placement("alpha", "s_alpha"), alpha());
        CatalogSnapshot before = db.snapshot();

        // When / Then: inspection rejects it
        MigrationRunException inspected = assertThrows(MigrationRunException.class, runner::instances);
        assertEquals("CONFIGURATION", inspected.failureCode());
        assertEquals("instances", inspected.operation());

        // When / Then: a run rejects it before connecting or writing; the failure precedes the resolved
        // views, so it carries no report
        MigrationRunException run = rejected(runner.run(vertx));
        assertEquals("CONFIGURATION", run.failureCode());
        assertEquals("run", run.operation());
        db.assertCatalogUnchangedSince(before);
        assertEquals(0, connector.attempts());
    }

    // =====================================================================================
    // VALIDATE and legacy
    // =====================================================================================

    private void validateAgainstAbsentHistoryWritesNothing() throws Exception {
        // Given: a VALIDATE instance whose schema and history do not exist; its resources have no callbacks
        FlywayMigrationRunner runner =
                runner(named(instance("alpha").mode(FlywayMode.VALIDATE)), placement("alpha", "s_check"), alpha());
        CatalogSnapshot before = db.snapshot();

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: native validation fails on the pending script, and nothing is created
        assertEquals("VALIDATION", failure.failureCode());
        assertEquals(List.of("alpha FAILED applied=0 current=null"), outcomes(failure.report()));
        db.assertCatalogUnchangedSince(before);
        assertFalse(db.schemaExists("s_check"));
    }

    private void validateRunsWritingCallbackNatively() throws Exception {
        // Given: a VALIDATE instance whose resources include callbacks that write an audit row
        db.createAuditTable();
        FlywayMigrationRunner runner = runner(
                named(instance("alpha").mode(FlywayMode.VALIDATE)),
                placement("alpha", "s_check"),
                declaration("alpha", "owner-callback"));
        CatalogSnapshot before = db.snapshot();

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: the framework creates no schema or history
        assertEquals("VALIDATION", failure.failureCode());
        db.assertCatalogUnchangedSince(before);
        assertFalse(db.schemaExists("s_check"));
        // And: the callback ran natively
        assertTrue(db.auditEvents() > 0, "the callback runs natively during named VALIDATE");
    }

    private void validateIsFreeOfTheInitializationGuard() throws Exception {
        // Given: a conventional history, and a VALIDATE instance whose set mixes an existing and a missing schema
        db.nativeHistory(null, "legacy-app");
        db.createSchema("s_extra");
        FlywayMigrationRunner runner = runner(
                named(instance("alpha").mode(FlywayMode.VALIDATE).schemas("s_check", "s_extra")),
                placement("alpha", "s_check"),
                alpha());
        CatalogSnapshot before = db.snapshot();

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: native validation decides, not the initialization guard
        assertEquals("VALIDATION", failure.failureCode(), "failure code of: " + failure.getMessage());
        assertEquals(List.of("alpha FAILED applied=0 current=null"), outcomes(failure.report()));
        db.assertCatalogUnchangedSince(before);
    }

    private void legacyMigrationOpensNoGuardConnection() throws Exception {
        // Given: a legacy configuration whose managed set mixes a missing and an existing empty schema
        db.createSchema("s_legacy_extra");
        CountingConnector connector = new CountingConnector();
        FlywayConfig legacy = FlywayConfig.builder()
                .mode(FlywayMode.MIGRATE)
                .locations(List.of(FIXTURES + "legacy-app"))
                .schemas(List.of("s_legacy_home", "s_legacy_extra"))
                .build();
        FlywayMigrationRunner runner = new FlywayMigrationRunner(
                legacy,
                db.poolConfig(),
                Set.of(),
                DbSchemas.empty(),
                PostgresDatabaseSupport.selector(connector),
                connector);

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: the unchanged native path runs, and the runner opens no connection of its own
        assertEquals(List.of("legacy MIGRATED applied=1 current=1 schema=s_legacy_home"), outcomesWithSchema(report));
        assertTrue(db.tableExists("s_legacy_home", "legacy_widget"));
        assertEquals(0, connector.attempts(), "legacy migration opens no guard or probe connection");
    }

    // =====================================================================================
    // Session lock lifecycle
    // =====================================================================================

    /** The fixed two-integer advisory key the guard session holds. */
    private static final int LOCK_KEY_FIRST = 1448365393;

    private static final int LOCK_KEY_SECOND = 1296648018;

    /** How far past its configured timeout a refused caller may take before the bound counts as broken. */
    private static final long LOCK_WAIT_ALLOWANCE_MS = 5_000;

    /** Fails until the guard exists unless the scenario says it pins native behavior. */
    private static Stream<Arguments> lockLifecycleScenarios() {
        return Stream.of(
                // Fails until the guard takes the lock: both callers migrate natively beside the holder.
                scenario(
                        "two callers on different histories race a held lock for 1500 ms: both fail within the bound"
                                + " with every entry not run and nothing written, then both migrate once it is released",
                        test -> test.racingCallersFailWithinTheBoundThenMigrateAfterRelease(1_500)),
                scenario(
                        "the smallest valid timeout of 1 ms refuses both callers at once with every entry not run",
                        test -> test.racingCallersFailWithinTheBoundThenMigrateAfterRelease(1)),
                // Native today and afterwards: neither path may wait for the lock.
                scenario(
                        "named VALIDATE-only run succeeds while the key is held",
                        FlywayNamedPostgresSafetyIT::validateOnlyRunNeverTakesTheLock),
                scenario(
                        "legacy migration succeeds while the key is held",
                        FlywayNamedPostgresSafetyIT::legacyRunNeverTakesTheLock),
                // Fail until the guard holds the lock through the run.
                scenario(
                        "the lock is held through every instance of a successful run and released afterwards",
                        FlywayNamedPostgresSafetyIT::lockIsHeldThroughASuccessfulRun),
                scenario(
                        "the lock is held through a failing instance and released afterwards",
                        FlywayNamedPostgresSafetyIT::lockIsHeldThroughAFailingInstance),
                // Fails until the guard verifies its session before each instance.
                scenario(
                        "a guard session ended after the first instance fails the next instance closed",
                        FlywayNamedPostgresSafetyIT::lostGuardSessionFailsTheNextInstanceClosed),
                // Native today: the range check already exists.
                scenario(
                        "the timeout accepts both ends of its range without connecting",
                        FlywayNamedPostgresSafetyIT::lockTimeoutRangeEndsAreAccepted));
    }

    private void racingCallersFailWithinTheBoundThenMigrateAfterRelease(long timeoutMs) throws Exception {
        // Given: an external session holds the key, and two callers target different histories, each with a
        // disabled instance beside its active one
        TrackingConnector connector = new TrackingConnector();
        CatalogSnapshot before = db.snapshot();
        try (LockHolder holder = db.holdLock()) {
            FlywayMigrationRunner first = caller(connector, timeoutMs, "alpha", "alpha", "idle", "twin-left");
            FlywayMigrationRunner second = caller(connector, timeoutMs, "beta", "beta", "standby", "twin-right");

            // When: both start together
            List<Timed> attempts = race(first, second);

            // Then: each is refused within the bound with every entry, the disabled one included, not run
            assertBoundedCoordinationFailure(attempts.get(0), timeoutMs, "alpha", "idle");
            assertBoundedCoordinationFailure(attempts.get(1), timeoutMs, "beta", "standby");
            if (timeoutMs >= 1_000) {
                assertTrue(
                        connector.peak() >= 2,
                        "both callers must wait at the same time, but at most " + connector.peak()
                                + " guard connection(s) were ever open together");
            }
            // And: the holder keeps its lock, nothing was written, and no guard connection is left behind
            assertEquals(List.of(holder.pid()), db.lockOwnerPids(), "the callers must leave the holder's lock alone");
            db.assertCatalogUnchangedSince(before);
            assertEquals(0, connector.open(), "every guard connection is closed after the refusal");
            db.assertNoOpenConnectionsBesides(holder.pid());
        }

        // When: the holder is gone and the callers run normally
        MigrationReport firstReport =
                succeeded(caller(new TrackingConnector(), 30_000, "alpha", "alpha", "idle", "twin-left")
                        .run(vertx));
        MigrationReport secondReport =
                succeeded(caller(new TrackingConnector(), 30_000, "beta", "beta", "standby", "twin-right")
                        .run(vertx));

        // Then: each history is written once and the disabled instances stay untouched
        assertEquals(
                List.of("alpha MIGRATED applied=1 current=1", "idle DISABLED applied=0 current=null"),
                outcomes(firstReport));
        assertEquals(
                List.of("beta MIGRATED applied=1 current=1", "standby DISABLED applied=0 current=null"),
                outcomes(secondReport));
        assertEquals(List.of("1"), db.appliedVersions("s_alpha", history("alpha")));
        assertEquals(List.of("1"), db.appliedVersions("s_beta", history("beta")));
        assertTrue(db.tableExists("s_alpha", "alpha_item"));
        assertTrue(db.tableExists("s_beta", "beta_item"));
        assertTrue(db.tryLockExternally(), "the finished runs released the lock");
    }

    private void validateOnlyRunNeverTakesTheLock() throws Exception {
        // Given: alpha is migrated, and an external session holds the key
        succeeded(runner(named(instance("alpha")), placement("alpha", "s_alpha"), alpha())
                .run(vertx));
        try (LockHolder holder = db.holdLock()) {
            FlywayMigrationRunner validator = runner(
                    namedWithTimeout(300, instance("alpha").mode(FlywayMode.VALIDATE)),
                    placement("alpha", "s_alpha"),
                    alpha());

            // When
            MigrationReport report = succeeded(validator.run(vertx));

            // Then: the run neither waits for the key nor takes it
            assertEquals(List.of("alpha VALIDATED applied=0 current=1"), outcomes(report));
            assertEquals(List.of(holder.pid()), db.lockOwnerPids());
        }
    }

    private void legacyRunNeverTakesTheLock() throws Exception {
        // Given: a legacy configuration, and an external session holds the key
        FlywayConfig legacy = FlywayConfig.builder()
                .mode(FlywayMode.MIGRATE)
                .locations(List.of(FIXTURES + "legacy-app"))
                .schemas(List.of("s_legacy"))
                .coordinationLockTimeoutMs(300)
                .build();
        try (LockHolder holder = db.holdLock()) {
            FlywayMigrationRunner runner =
                    new FlywayMigrationRunner(legacy, db.poolConfig(), Set.of(), DbSchemas.empty());

            // When
            MigrationReport report = succeeded(runner.run(vertx));

            // Then
            assertEquals(List.of("legacy MIGRATED applied=1 current=1 schema=s_legacy"), outcomesWithSchema(report));
            assertEquals(List.of(holder.pid()), db.lockOwnerPids());
        }
    }

    private void lockIsHeldThroughASuccessfulRun() throws Exception {
        // Given: a run whose last instance asks the database, from its own session, whether the key is free
        db.createLockProbe();
        FlywayMigrationRunner runner = runner(
                named(instance("alpha"), instance("zeta")),
                placement("alpha", "s_alpha", "zeta", "s_zeta"),
                alpha(),
                declaration("zeta", "lock-probe"));

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: the key was held while the last instance finished, and is free once the run returns
        assertEquals(
                List.of("alpha MIGRATED applied=1 current=1", "zeta MIGRATED applied=1 current=1"), outcomes(report));
        assertEquals(List.of("after-migrate:held"), db.lockProbes());
        assertTrue(db.tryLockExternally(), "the guard releases the lock when the run ends");
    }

    private void lockIsHeldThroughAFailingInstance() throws Exception {
        // Given: the first instance and the failing one both probe the key from their own sessions; the
        // failing one probes before its script and after the failure
        db.createLockProbe();
        FlywayMigrationRunner runner = runner(
                named(instance("alpha"), instance("broken"), instance("gamma")),
                placement("alpha", "s_alpha", "broken", "s_broken", "gamma", "s_gamma"),
                declaration("alpha", "lock-probe"),
                declaration("broken", "failing-probe"),
                gamma());

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: the second instance fails natively and the third is not attempted
        assertEquals("MIGRATION", failure.failureCode(), "failure code of: " + failure.getMessage());
        assertEquals("run", failure.operation());
        assertEquals(
                List.of(
                        "alpha MIGRATED applied=1 current=1",
                        "broken FAILED applied=0 current=null",
                        "gamma NOT_RUN applied=0 current=null"),
                outcomes(failure.report()));
        assertFalse(db.schemaExists("s_gamma"), "later entries are not attempted");
        // And: the key stayed held through every outcome, failure included, and is free afterwards
        assertEquals(
                List.of("after-migrate:held", "broken-before-migrate:held", "broken-after-migrate-error:held"),
                db.lockProbes());
        assertTrue(db.tryLockExternally(), "the guard releases the lock after a failed run");
    }

    private void lostGuardSessionFailsTheNextInstanceClosed() throws Exception {
        // Given: the first instance's callback ends the backend that holds the key once the instance is done
        db.createLockProbe();
        FlywayMigrationRunner runner = runner(
                named(instance("alpha"), instance("beta"), instance("gamma")),
                placement("alpha", "s_alpha", "beta", "s_beta", "gamma", "s_gamma"),
                declaration("alpha", "guard-killer"),
                beta(),
                gamma());

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: the guard session really was ended, and the next instance fails closed
        assertEquals(List.of("guard-killed:true"), db.lockProbes(), "the callback must find and end the guard");
        assertEquals("COORDINATION", failure.failureCode(), "failure code of: " + failure.getMessage());
        assertEquals("coordination", failure.operation());
        assertEquals(
                List.of(
                        "alpha MIGRATED applied=1 current=1",
                        "beta FAILED applied=0 current=null",
                        "gamma NOT_RUN applied=0 current=null"),
                outcomes(failure.report()));
        assertHygienic(failure, "s_beta");
        // And: the first result stands, and the later instances write nothing
        assertEquals(List.of("1"), db.appliedVersions("s_alpha", history("alpha")));
        assertFalse(db.schemaExists("s_beta"), "the failed instance writes nothing");
        assertFalse(db.schemaExists("s_gamma"), "later entries are not attempted");
    }

    private void lockTimeoutRangeEndsAreAccepted() {
        for (long timeoutMs : new long[] {1, 300_000}) {
            // Given
            CountingConnector connector = new CountingConnector();
            FlywayMigrationRunner runner = unconnectedRunner(
                    connector, namedWithTimeout(timeoutMs, instance("alpha")), placement("alpha", "s_tables"), alpha());

            // When
            List<MigrationInstanceView> views = runner.instances();

            // Then
            assertEquals(1, views.size(), "timeout " + timeoutMs + " ms is accepted");
            assertEquals(0, connector.attempts(), "instances() must not connect");
        }
    }

    /** One caller: an active instance placed in {@code s_<active>} and a disabled one beside it. */
    private FlywayMigrationRunner caller(
            JdbcConnector connector,
            long timeoutMs,
            String active,
            String activeFixture,
            String dormant,
            String dormantFixture)
            throws SQLException {
        return new FlywayMigrationRunner(
                namedWithTimeout(timeoutMs, instance(active), instance(dormant).mode(FlywayMode.DISABLED)),
                db.poolConfig(),
                new LinkedHashSet<>(List.of(declaration(active, activeFixture), declaration(dormant, dormantFixture))),
                placement(active, "s_" + active),
                PostgresDatabaseSupport.selector(connector),
                connector);
    }

    private static FlywayConfig namedWithTimeout(long timeoutMs, InstanceSettings... instances) {
        return namedRoot()
                .coordinationLockTimeoutMs(timeoutMs)
                .instances(Stream.of(instances).map(InstanceSettings::build).toList())
                .build();
    }

    /** One timed run: how it ended and how long the caller waited. */
    private record Timed(MigrationRunException failure, MigrationReport report, long elapsedMs) {}

    /** Starts every runner from its own thread at the same moment and times each run. */
    private static List<Timed> race(FlywayMigrationRunner... runners) throws Exception {
        CyclicBarrier start = new CyclicBarrier(runners.length);
        ExecutorService callers = Executors.newFixedThreadPool(runners.length);
        try {
            List<CompletableFuture<Timed>> pending = new ArrayList<>();
            for (FlywayMigrationRunner runner : runners) {
                pending.add(CompletableFuture.supplyAsync(
                        () -> {
                            try {
                                return timedRun(runner, start);
                            } catch (Exception ex) {
                                throw new CompletionException(ex);
                            }
                        },
                        callers));
            }
            List<Timed> results = new ArrayList<>();
            for (CompletableFuture<Timed> one : pending) {
                try {
                    results.add(one.get(RUN_TIMEOUT_SECONDS + 5, TimeUnit.SECONDS));
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    while (cause instanceof CompletionException && cause.getCause() != null) {
                        cause = cause.getCause();
                    }
                    if (cause instanceof AssertionError assertion) {
                        throw assertion;
                    }
                    throw ex;
                }
            }
            return results;
        } finally {
            callers.shutdownNow();
        }
    }

    private static Timed timedRun(FlywayMigrationRunner runner, CyclicBarrier start) throws Exception {
        start.await(10, TimeUnit.SECONDS);
        long begun = System.nanoTime();
        try {
            MigrationReport report = runner.run(vertx)
                    .toCompletionStage()
                    .toCompletableFuture()
                    .get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return new Timed(null, report, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun));
        } catch (ExecutionException ex) {
            return new Timed(
                    assertInstanceOf(MigrationRunException.class, ex.getCause()),
                    null,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun));
        } catch (TimeoutException ex) {
            return fail(
                    "the caller was still waiting after " + RUN_TIMEOUT_SECONDS + " seconds: the wait is unbounded");
        }
    }

    /** Refused at the lock: coordination failure, every entry not run, attributed to no instance, in time. */
    private void assertBoundedCoordinationFailure(Timed attempt, long timeoutMs, String... order) {
        MigrationRunException failure = attempt.failure();
        if (failure == null) {
            fail("the run proceeded although the lock was held: " + outcomes(attempt.report()));
        }
        assertEquals("COORDINATION", failure.failureCode(), "failure code of: " + failure.getMessage());
        assertEquals("coordination", failure.operation());
        assertNotNull(failure.report(), "a coordination failure carries the ordered report");
        assertEquals(notRun(order), outcomes(failure.report()));
        assertNamesNoInstance(failure, order);
        assertHygienic(failure);
        assertTrue(
                attempt.elapsedMs() <= timeoutMs + LOCK_WAIT_ALLOWANCE_MS,
                "the caller waited " + attempt.elapsedMs() + " ms for a " + timeoutMs + " ms timeout");
        if (timeoutMs >= 1_000) {
            assertTrue(
                    attempt.elapsedMs() >= timeoutMs - 50,
                    "the caller gave up after " + attempt.elapsedMs() + " ms instead of waiting " + timeoutMs + " ms");
        }
    }

    private static void assertNamesNoInstance(MigrationRunException failure, String... instances) {
        String message = String.valueOf(failure.getMessage());
        assertFalse(message.contains("instance '"), "a coordination failure names no instance: " + message);
        for (String name : instances) {
            assertFalse(
                    Pattern.compile("\\b" + name + "\\b").matcher(message).find(),
                    "a coordination failure names no instance, but names '" + name + "': " + message);
        }
    }

    /** An external session holding the key; closing it releases the lock. */
    private static final class LockHolder implements AutoCloseable {

        private final Connection connection;
        private final int pid;

        LockHolder(Connection connection, int pid) {
            this.connection = connection;
            this.pid = pid;
        }

        int pid() {
            return pid;
        }

        @Override
        public void close() throws SQLException {
            connection.close();
        }
    }

    /**
     * Opens real connections like the default connector and counts them: how many are open now, how many
     * were open together at most, and how many were opened. Closing or aborting a connection ends its count.
     */
    private static final class TrackingConnector implements JdbcConnector {

        private final AtomicInteger open = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        @Override
        public Connection connect(String url, String user, String password) throws SQLException {
            Connection real = DriverManager.getConnection(url, user, password);
            peak.accumulateAndGet(open.incrementAndGet(), Math::max);
            AtomicBoolean released = new AtomicBoolean();
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        boolean ending = method.getName().equals("close")
                                || method.getName().equals("abort");
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException ex) {
                            throw ex.getCause();
                        } finally {
                            if (ending && released.compareAndSet(false, true)) {
                                open.decrementAndGet();
                            }
                        }
                    });
        }

        int open() {
            return open.get();
        }

        int peak() {
            return peak.get();
        }
    }

    // =====================================================================================
    // Hostile search paths
    // =====================================================================================

    /** What the shadow {@code current_schema()} claims; no real schema carries this name. */
    private static final String SHADOW_DEFAULT = "shadow_default";

    private static final String SHADOW_VERSION = "Shadow 1.0 (not PostgreSQL)";

    /**
     * Shadow catalog relations. They are empty, so an unqualified existence or lock query finds nothing. Native
     * Flyway reads the same catalog unqualified, so these can only shadow sessions that do not migrate.
     */
    private static final String SHADOW_CATALOG = """
            CREATE TABLE evil.pg_namespace (oid oid, nspname name, nspowner oid, nspacl aclitem[]);
            CREATE TABLE evil.pg_class (oid oid, relname name, relnamespace oid, relkind "char", relowner oid);
            CREATE TABLE evil.pg_locks (
                locktype text, database oid, classid oid, objid oid, objsubid smallint, pid integer, granted boolean);
            CREATE FUNCTION evil.version() RETURNS text LANGUAGE sql AS $$ SELECT 'Shadow 1.0 (not PostgreSQL)'::text $$;
            GRANT SELECT ON ALL TABLES IN SCHEMA evil TO PUBLIC;
            """;

    /** Shadow functions only the guard calls: the default schema, the session lock and the backend pid. */
    private static final String SHADOW_GUARD_FUNCTIONS = """
            CREATE FUNCTION evil.current_schema() RETURNS name LANGUAGE sql AS $$ SELECT 'shadow_default'::name $$;
            CREATE FUNCTION evil.pg_try_advisory_lock(integer, integer) RETURNS boolean LANGUAGE sql
                AS $$ SELECT true $$;
            CREATE FUNCTION evil.pg_backend_pid() RETURNS integer LANGUAGE sql AS $$ SELECT 0 $$;
            """;

    /** Which shadow objects a hostile world holds. */
    private enum Shadows {
        /** Only functions that native Flyway does not call, for worlds whose role also runs the migrations. */
        GUARD_FUNCTIONS,
        /** The guard functions plus shadow catalog relations, for worlds whose role runs only the guard. */
        GUARD_FUNCTIONS_AND_CATALOG
    }

    /**
     * The hostile role's search path. In both forms the schema {@code evil} precedes {@code pg_catalog}, which
     * is listed explicitly so that it is not implicitly first and the shadows do win.
     */
    private enum SearchPath {
        /** The shadow schema is the role's real default schema; only a probe role can live with that. */
        SHADOW_FIRST(new String[] {"evil", "pg_catalog", "public"}, "evil"),
        /** A real home schema comes first, so the real default is neither the shadow schema nor a decoy. */
        HOME_FIRST(new String[] {"s_home", "evil", "pg_catalog"}, "s_home");

        private final String[] entries;
        private final String realDefault;

        SearchPath(String[] entries, String realDefault) {
            this.entries = entries;
            this.realDefault = realDefault;
        }
    }

    /** A database with the hostile role; the control world has the same role and schemas but no shadow objects. */
    private record World(Fixture fixture, Role probeRole, boolean hostile, SearchPath searchPath, Shadows shadows) {}

    /** One observation of a world: asserts what must hold there and returns what to compare across worlds. */
    @FunctionalInterface
    private interface HostileCase {
        List<String> observe(World world) throws Exception;
    }

    private static Stream<Arguments> hostileSearchPathScenarios() {
        return Stream.of(
                // The control proof: without it nothing below could fail.
                scenario(
                        "shadow objects hijack unqualified queries of a role with the shadow schema first",
                        test -> test.sameOutcomeAsControl(
                                SearchPath.SHADOW_FIRST, Shadows.GUARD_FUNCTIONS_AND_CATALOG, world -> List.of())),
                scenario(
                        "shadow objects hijack unqualified queries of a role with its home schema first",
                        test -> test.sameOutcomeAsControl(
                                SearchPath.HOME_FIRST, Shadows.GUARD_FUNCTIONS, world -> List.of())),
                // Hostile ROOT role: the guard session connects with it. Fail until the guard exists.
                scenario(
                        "hostile root role: a conventional history in the guard's captured default blocks"
                                + " initialization as for a plain role",
                        test -> test.sameOutcomeAsControl(
                                SearchPath.SHADOW_FIRST,
                                Shadows.GUARD_FUNCTIONS_AND_CATALOG,
                                test::conventionalHistoryInCapturedDefaultBlocks)),
                scenario(
                        "hostile root role: a mixed existing and missing schema set is rejected as for a plain role",
                        test -> test.sameOutcomeAsControl(
                                SearchPath.SHADOW_FIRST,
                                Shadows.GUARD_FUNCTIONS_AND_CATALOG,
                                test::mixedSetIsRejected)),
                scenario(
                        "hostile root role: the guard holds the real lock through the run, not the shadow function's",
                        test -> test.sameOutcomeAsControl(
                                SearchPath.SHADOW_FIRST,
                                Shadows.GUARD_FUNCTIONS_AND_CATALOG,
                                test::guardHoldsTheRealLock)),
                scenario(
                        "hostile root role: a real holder of the key still blocks the guard",
                        test -> test.sameOutcomeAsControl(
                                SearchPath.SHADOW_FIRST,
                                Shadows.GUARD_FUNCTIONS_AND_CATALOG,
                                test::realHolderBlocksTheGuard)),
                // Native today: the guard must not turn the hostile session's true state into a rejection.
                scenario(
                        "hostile root role: a consistent batch migrates through every guard check as for a plain role",
                        test -> test.sameOutcomeAsControl(
                                SearchPath.SHADOW_FIRST,
                                Shadows.GUARD_FUNCTIONS_AND_CATALOG,
                                test::consistentBatchMigrates)),
                // Hostile INSTANCE role: its default is resolved through its own connection.
                scenario(
                        "hostile instance role: the default resolves to the real home schema and the history lands"
                                + " there",
                        test -> test.sameOutcomeAsControl(
                                SearchPath.HOME_FIRST,
                                Shadows.GUARD_FUNCTIONS,
                                test::instanceDefaultIsTheRealHomeSchema)),
                scenario(
                        "hostile instance role: a conventional history in its resolved default blocks initialization",
                        test -> test.sameOutcomeAsControl(
                                SearchPath.HOME_FIRST,
                                Shadows.GUARD_FUNCTIONS,
                                test::conventionalHistoryInResolvedDefaultBlocks)),
                // Native today: hardening the guard session must not leak into migration sessions.
                scenario(
                        "hostile root role inherited by an instance: migration sessions keep their native default",
                        test -> test.sameOutcomeAsControl(
                                SearchPath.HOME_FIRST,
                                Shadows.GUARD_FUNCTIONS,
                                test::inheritedRootPairKeepsNativeDefault)));
    }

    private World world(Fixture fixture, SearchPath searchPath, Shadows shadows, boolean hostile) throws SQLException {
        Role probeRole = fixture.role("probe", searchPath.entries);
        fixture.execute("CREATE SCHEMA evil");
        fixture.execute("GRANT USAGE ON SCHEMA evil TO " + probeRole.name());
        if (searchPath == SearchPath.HOME_FIRST) {
            fixture.createSchemaOwnedBy("s_home", probeRole);
            fixture.createSchemaOwnedBy(SHADOW_DEFAULT, probeRole);
        }
        if (hostile) {
            fixture.execute(SHADOW_GUARD_FUNCTIONS);
            if (shadows == Shadows.GUARD_FUNCTIONS_AND_CATALOG) {
                fixture.execute(SHADOW_CATALOG);
            }
        }
        return new World(fixture, probeRole, hostile, searchPath, shadows);
    }

    /**
     * Runs the case in a control world and in the hostile world, after proving what the shadows do to
     * unqualified queries in each, and demands identical observations.
     */
    private void sameOutcomeAsControl(SearchPath searchPath, Shadows shadows, HostileCase hostileCase)
            throws Exception {
        // Given: a control world without shadow objects
        Fixture plain = new Fixture();
        List<String> expected;
        try {
            World control = world(plain, searchPath, shadows, false);
            assertUnqualifiedNamesResolveHonestly(control);
            // When / Then: the case holds in the control world
            expected = observeIn("control", control, hostileCase);
            plain.assertNoOpenConnections();
        } finally {
            plain.close();
        }

        // And: a hostile world whose shadow objects demonstrably change what unqualified names resolve to
        World hostile = world(db, searchPath, shadows, true);
        assertShadowsHijackUnqualifiedNames(hostile);

        // When / Then: the case holds there too, with the same observations
        List<String> actual = observeIn("hostile", hostile, hostileCase);
        assertEquals(expected, actual, "the hostile session must yield the control's outcome");
    }

    private static List<String> observeIn(String label, World world, HostileCase hostileCase) throws Exception {
        try {
            return hostileCase.observe(world);
        } catch (AssertionError failure) {
            throw new AssertionError("in the " + label + " world: " + failure.getMessage(), failure);
        }
    }

    /** The test-owned control: unqualified names resolve to the shadows, qualified ones to the real catalog. */
    private void assertShadowsHijackUnqualifiedNames(World world) throws Exception {
        try (LockHolder holder = world.fixture().holdLock();
                Connection session = world.fixture().connectAs(world.probeRole())) {
            assertEquals(world.searchPath().realDefault, scalar(session, "SELECT pg_catalog.current_schema()"));
            assertEquals(SHADOW_DEFAULT, scalar(session, "SELECT current_schema()"), "unqualified current_schema()");
            assertEquals("0", scalar(session, "SELECT pg_backend_pid()"), "unqualified pg_backend_pid()");
            assertNotEquals("0", scalar(session, "SELECT pg_catalog.pg_backend_pid()"));
            assertTrue(flag(session, tryLockSql("")), "unqualified pg_try_advisory_lock claims the held key");
            assertFalse(flag(session, tryLockSql("pg_catalog.")), "the real key is held by " + holder.pid());
            if (world.shadows() == Shadows.GUARD_FUNCTIONS_AND_CATALOG) {
                assertEquals("0", scalar(session, "SELECT count(*) FROM pg_namespace"), "unqualified pg_namespace");
                assertNotEquals("0", scalar(session, "SELECT count(*) FROM pg_catalog.pg_namespace"));
                assertEquals("0", scalar(session, "SELECT count(*) FROM pg_class"), "unqualified pg_class");
                assertNotEquals("0", scalar(session, "SELECT count(*) FROM pg_catalog.pg_class"));
                assertEquals("0", scalar(session, "SELECT count(*) FROM pg_locks"), "unqualified pg_locks");
                assertNotEquals("0", scalar(session, "SELECT count(*) FROM pg_catalog.pg_locks"));
                assertEquals(SHADOW_VERSION, scalar(session, "SELECT version()"), "unqualified version()");
                assertTrue(scalar(session, "SELECT pg_catalog.version()").startsWith("PostgreSQL "));
            }
        }
    }

    /** In the control world the same unqualified and qualified names agree. */
    private void assertUnqualifiedNamesResolveHonestly(World world) throws Exception {
        try (LockHolder holder = world.fixture().holdLock();
                Connection session = world.fixture().connectAs(world.probeRole())) {
            assertEquals(world.searchPath().realDefault, scalar(session, "SELECT current_schema()"));
            assertEquals(
                    scalar(session, "SELECT count(*) FROM pg_catalog.pg_namespace"),
                    scalar(session, "SELECT count(*) FROM pg_namespace"));
            assertEquals(
                    scalar(session, "SELECT count(*) FROM pg_catalog.pg_class"),
                    scalar(session, "SELECT count(*) FROM pg_class"));
            assertNotEquals("0", scalar(session, "SELECT count(*) FROM pg_locks"));
            assertEquals(
                    scalar(session, "SELECT pg_catalog.pg_backend_pid()"), scalar(session, "SELECT pg_backend_pid()"));
            assertTrue(scalar(session, "SELECT version()").startsWith("PostgreSQL "));
            assertFalse(flag(session, tryLockSql("")), "the real key is held by " + holder.pid());
        }
    }

    private static String tryLockSql(String qualifier) {
        return "SELECT " + qualifier + "pg_try_advisory_lock(" + LOCK_KEY_FIRST + ", " + LOCK_KEY_SECOND + ")";
    }

    private static String scalar(Connection session, String sql) throws SQLException {
        try (Statement statement = session.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private static boolean flag(Connection session, String sql) throws SQLException {
        try (Statement statement = session.createStatement();
                ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getBoolean(1);
        }
    }

    private static void assertNotEquals(String unexpected, String actual) {
        assertFalse(unexpected.equals(actual), "expected a value other than " + unexpected);
    }

    /** Comparable form of a rejection: category, operation, message and the ordered outcomes. */
    private static List<String> signature(MigrationRunException failure) {
        List<String> signature = new ArrayList<>();
        signature.add(failure.failureCode() + " " + failure.operation());
        signature.add(failure.getMessage());
        signature.addAll(outcomes(failure.report()));
        return signature;
    }

    // --- Hostile root role: the guard session connects with the probe role; migrations use a plain role ---

    private List<String> conventionalHistoryInCapturedDefaultBlocks(World world) throws Exception {
        // Given: the hostile session's own default schema holds a conventional history
        Fixture fixture = world.fixture();
        Role migrator = fixture.role("migrator");
        String guardDefault = world.searchPath().realDefault;
        fixture.execute("CREATE TABLE " + guardDefault + ".flyway_schema_history (installed_rank INTEGER)");
        FlywayMigrationRunner runner = runnerIn(
                fixture.poolConfig(world.probeRole()),
                named(instance("alpha").credentials(migrator)),
                placement("alpha", "s_alpha"),
                alpha());
        CatalogSnapshot before = fixture.snapshot();

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: the guard captured the real default before hardening and probed it through pg_catalog
        assertPreflightRejection(failure, "alpha");
        assertHygienic(fixture, failure, "s_alpha", guardDefault, SHADOW_DEFAULT);
        fixture.assertCatalogUnchangedSince(before);
        return signature(failure);
    }

    private List<String> mixedSetIsRejected(World world) throws Exception {
        // Given: an existing occupied schema and a missing extra schema in one managed set
        Fixture fixture = world.fixture();
        Role migrator = fixture.role("migrator");
        fixture.createSchema("s_home");
        fixture.occupy("s_home");
        fixture.grantSchema("s_home", migrator);
        FlywayMigrationRunner runner = runnerIn(
                fixture.poolConfig(world.probeRole()),
                named(instance("alpha").schemas("s_home", "s_extra").credentials(migrator)),
                placement("alpha", "s_home"),
                alpha());
        CatalogSnapshot before = fixture.snapshot();

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: existence is read from the real catalog, so the missing schema is seen as missing
        assertPreflightRejection(failure, "alpha");
        assertHygienic(fixture, failure, "s_home", "s_extra", SHADOW_DEFAULT);
        fixture.assertCatalogUnchangedSince(before);
        return signature(failure);
    }

    private List<String> guardHoldsTheRealLock(World world) throws Exception {
        // Given: the instance asks the database, from its own session, whether the key is free
        Fixture fixture = world.fixture();
        fixture.createLockProbe();
        Role migrator = fixture.role("migrator");
        FlywayMigrationRunner runner = runnerIn(
                fixture.poolConfig(world.probeRole()),
                named(instance("alpha").credentials(migrator)),
                placement("alpha", "s_alpha"),
                declaration("alpha", "lock-probe"));

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: the key was held during the run, so the guard took the real lock and not the shadow function
        List<String> probes = fixture.lockProbes();
        assertEquals(List.of("after-migrate:held"), probes);
        assertTrue(fixture.tryLockExternally(), "the guard releases the lock when the run ends");
        List<String> observed = new ArrayList<>(outcomes(report));
        observed.addAll(probes);
        return observed;
    }

    private List<String> realHolderBlocksTheGuard(World world) throws Exception {
        // Given: an external session holds the real key, and the shadow function would say it is free
        Fixture fixture = world.fixture();
        Role migrator = fixture.role("migrator");
        FlywayMigrationRunner runner = runnerIn(
                fixture.poolConfig(world.probeRole()),
                namedWithTimeout(600, instance("alpha").credentials(migrator)),
                placement("alpha", "s_alpha"),
                alpha());
        CatalogSnapshot before = fixture.snapshot();
        try (LockHolder holder = fixture.holdLock()) {
            // When
            MigrationRunException failure = rejected(runner.run(vertx));

            // Then: the guard is refused at the real lock, writing nothing
            assertEquals("COORDINATION", failure.failureCode(), "failure code of: " + failure.getMessage());
            assertEquals("coordination", failure.operation());
            assertEquals(notRun("alpha"), outcomes(failure.report()));
            assertHygienic(fixture, failure, SHADOW_DEFAULT);
            fixture.assertCatalogUnchangedSince(before);
            assertEquals(List.of(holder.pid()), fixture.lockOwnerPids());
            return signature(failure);
        }
    }

    private List<String> consistentBatchMigrates(World world) throws Exception {
        // Given: two instances whose schemas are all missing
        Fixture fixture = world.fixture();
        Role migrator = fixture.role("migrator");
        FlywayMigrationRunner runner = runnerIn(
                fixture.poolConfig(world.probeRole()),
                named(
                        instance("alpha").schemas("s_alpha", "s_alpha_extra").credentials(migrator),
                        instance("beta").credentials(migrator)),
                placement("alpha", "s_alpha", "beta", "s_beta"),
                alpha(),
                beta());

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: every guard check through the hostile session reads the true state and lets native Flyway run
        assertEquals(
                List.of(
                        "alpha MIGRATED applied=1 current=1 schema=s_alpha",
                        "beta MIGRATED applied=1 current=1 schema=s_beta"),
                outcomesWithSchema(report));
        assertEquals(List.of("1"), fixture.appliedVersions("s_alpha", history("alpha")));
        assertEquals(List.of("1"), fixture.appliedVersions("s_beta", history("beta")));
        return outcomesWithSchema(report);
    }

    // --- Hostile instance role: its default is resolved through its own connection ---

    private List<String> instanceDefaultIsTheRealHomeSchema(World world) throws Exception {
        // Given: an instance with no schema list and no placement, migrating as the hostile role
        Fixture fixture = world.fixture();
        FlywayMigrationRunner runner = runnerIn(
                fixture.poolConfig(),
                named(instance("beta").credentials(world.probeRole())),
                DbSchemas.empty(),
                beta());

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: the frozen default is the real one, and history and objects land there, not at the decoy
        assertEquals(List.of("beta MIGRATED applied=1 current=1 schema=s_home"), outcomesWithSchema(report));
        assertEquals(List.of("1"), fixture.appliedVersions("s_home", history("beta")));
        assertTrue(fixture.tableExists("s_home", "beta_item"));
        assertEquals(List.of(), fixture.relationsIn(SHADOW_DEFAULT), "nothing may follow the shadow function's claim");
        return outcomesWithSchema(report);
    }

    private List<String> conventionalHistoryInResolvedDefaultBlocks(World world) throws Exception {
        // Given: a conventional history sits in the hostile role's real default schema
        Fixture fixture = world.fixture();
        fixture.execute("CREATE TABLE s_home.flyway_schema_history (installed_rank INTEGER);"
                + " ALTER TABLE s_home.flyway_schema_history OWNER TO "
                + world.probeRole().name());
        FlywayMigrationRunner runner = runnerIn(
                fixture.poolConfig(),
                named(instance("beta").credentials(world.probeRole())),
                DbSchemas.empty(),
                beta());
        CatalogSnapshot before = fixture.snapshot();

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: the resolved default is the real one, so the history is found before anything is written
        assertPreflightRejection(failure, "beta");
        assertHygienic(fixture, failure, "s_home", SHADOW_DEFAULT);
        fixture.assertCatalogUnchangedSince(before);
        return signature(failure);
    }

    private List<String> inheritedRootPairKeepsNativeDefault(World world) throws Exception {
        // Given: the hostile role is the root pair, and the instance inherits it with no schema list
        Fixture fixture = world.fixture();
        FlywayMigrationRunner runner =
                runnerIn(fixture.poolConfig(world.probeRole()), named(instance("gamma")), DbSchemas.empty(), gamma());

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: the guard's pinned search path stays in the guard session; the migration session's default holds
        assertEquals(List.of("gamma MIGRATED applied=1 current=1 schema=s_home"), outcomesWithSchema(report));
        assertEquals(List.of("1"), fixture.appliedVersions("s_home", history("gamma")));
        assertTrue(fixture.tableExists("s_home", "gamma_item"));
        assertFalse(fixture.tableExists("pg_catalog", "gamma_item"));
        return outcomesWithSchema(report);
    }

    private FlywayMigrationRunner runnerIn(
            DbPoolConfig pool, FlywayConfig config, DbSchemas placement, MigrationDefinition... declarations) {
        return new FlywayMigrationRunner(config, pool, new LinkedHashSet<>(List.of(declarations)), placement);
    }

    // =====================================================================================
    // Conventional history planted by an earlier instance
    // =====================================================================================

    /**
     * The negative scenarios fail until the conventional-history check is repeated before each instance:
     * today the later instance initializes beside the planted history. The owner scenarios pass today and
     * must keep passing.
     */
    private static Stream<Arguments> plantedConventionalHistoryScenarios() {
        return Stream.of(
                scenario(
                        "a conventional history an earlier script plants in the guard default schema fails the next"
                                + " initializing instance with zero writes and leaves later entries not run",
                        test -> test.plantedConventionalHistoryFailsTheNextInstance(false)),
                scenario(
                        "a conventional history an earlier script plants in the next instance's own schema fails that"
                                + " instance, even with baseline true/0, with zero writes",
                        test -> test.plantedConventionalHistoryFailsTheNextInstance(true)),
                scenario(
                        "an instance that names the history planted in the guard default schema as its own keeps"
                                + " running, and so does the instance after it",
                        test -> test.ownerOfAPlantedConventionalHistoryContinues(false)),
                scenario(
                        "an instance that names the history planted in its own schema as its own keeps running, and so"
                                + " does the instance after it",
                        test -> test.ownerOfAPlantedConventionalHistoryContinues(true)));
    }

    private void plantedConventionalHistoryFailsTheNextInstance(boolean inOwnSchema) throws Exception {
        // Given: alpha's script creates a conventional history once alpha commits, either in the
        // guard connection's default schema or in beta's own, still missing schema
        String planted = inOwnSchema ? "s_beta" : "public";
        InstanceSettings beta = instance("beta");
        if (inOwnSchema) {
            // Native Flyway would refuse the occupied schema by default; baseline true/0 lets it proceed
            beta.baseline(true, "0");
        }
        FlywayMigrationRunner runner = runner(
                named(instance("alpha").placeholders(Map.of("planted_schema", planted)), beta, instance("gamma")),
                placement("alpha", "s_alpha", "beta", "s_beta", "gamma", "s_gamma"),
                declaration("alpha", "history-planter"),
                beta(),
                gamma());
        // And: the batch-start check finds no conventional history and no schema of beta
        assertFalse(db.tableExists(planted, CONVENTIONAL_HISTORY), "the history must appear only during the run");
        assertFalse(db.schemaExists("s_beta"));

        // When
        MigrationRunException failure = rejected(runner.run(vertx));

        // Then: alpha's commit stays, beta fails with nothing applied, gamma is not run
        assertEquals("PREFLIGHT", failure.failureCode(), "failure code of: " + failure.getMessage());
        assertEquals("run", failure.operation());
        assertEquals(
                List.of(
                        "alpha MIGRATED applied=1 current=1",
                        "beta FAILED applied=0 current=null",
                        "gamma NOT_RUN applied=0 current=null"),
                outcomes(failure.report()));
        assertNamesOneOf(failure, "beta");
        assertHygienic(failure, "s_alpha", "s_beta", "s_gamma");
        assertEquals(List.of("1"), db.appliedVersions("s_alpha", history("alpha")));
        assertTrue(db.tableExists("s_alpha", "planter_item"));
        // And: the planted history is there, untouched
        assertTrue(db.tableExists(planted, CONVENTIONAL_HISTORY), "alpha must have planted the history");
        assertEquals(
                0, db.countRows("\"" + planted + "\"." + CONVENTIONAL_HISTORY), "the planted history is untouched");
        // And: beta writes no history, baseline marker or object anywhere
        assertFalse(db.tableExists("s_beta", history("beta")), "beta's history is not created");
        assertFalse(db.tableExists("s_beta", "beta_item"), "beta's script does not run");
        assertFalse(db.tableExists("public", "beta_item"), "beta's script does not run");
        if (!inOwnSchema) {
            assertFalse(db.schemaExists("s_beta"), "beta's own schema is not created");
        }
        // And: later entries are not attempted
        assertFalse(db.schemaExists("s_gamma"), "later entries are not attempted");
    }

    private void ownerOfAPlantedConventionalHistoryContinues(boolean inOwnSchema) throws Exception {
        // Given: alpha's script creates an empty conventional history once alpha commits, in the
        // guard connection's default schema or in beta's own schema, and beta names that history explicitly
        String planted = inOwnSchema ? "s_beta" : "public";
        List<String> pairs = new ArrayList<>(List.of("alpha", "s_alpha", "gamma", "s_gamma"));
        if (inOwnSchema) {
            pairs.addAll(List.of("beta", "s_beta"));
        }
        FlywayMigrationRunner runner = runner(
                named(
                        instance("alpha").placeholders(Map.of("planted_schema", planted)),
                        instance("beta").table(CONVENTIONAL_HISTORY),
                        instance("gamma")),
                placement(pairs.toArray(String[]::new)),
                declaration("alpha", "history-planter"),
                beta(),
                gamma());

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: beta continues in the history it owns, and gamma initializes beside it
        assertEquals(
                List.of(
                        "alpha MIGRATED applied=1 current=1 schema=s_alpha",
                        "beta MIGRATED applied=1 current=1 schema=" + planted,
                        "gamma MIGRATED applied=1 current=1 schema=s_gamma"),
                outcomesWithSchema(report));
        assertEquals(List.of("1"), db.appliedVersions(planted, CONVENTIONAL_HISTORY));
        assertTrue(db.tableExists(planted, "beta_item"));
        assertEquals(List.of("1"), db.appliedVersions("s_gamma", history("gamma")));
    }

    // =====================================================================================
    // Network timeouts of the guard and the default-resolution probes
    // =====================================================================================

    /** The largest network timeout accepted on the guard and probe connections. */
    private static final long MAX_NETWORK_TIMEOUT_MS = 60_000;

    /** How far past the network timeout a blackholed run may take before the bound counts as broken. */
    private static final long NETWORK_TIMEOUT_ALLOWANCE_MS = 5_000;

    /** Both fail until the guard and the probes set a network timeout on their connections. */
    private static Stream<Arguments> networkTimeoutScenarios() {
        return Stream.of(
                scenario(
                        "every statement of the guard and of each default-resolution probe runs under a network"
                                + " timeout of at most 60 seconds",
                        FlywayNamedPostgresSafetyIT::guardAndProbeStatementsRunUnderANetworkTimeout),
                scenario(
                        "a path that silently stops forwarding once a probe has connected fails the run closed at"
                                + " preflight within the network timeout instead of blocking the worker",
                        FlywayNamedPostgresSafetyIT::blackholedProbeFailsClosedWithinTheNetworkTimeout));
    }

    private void guardAndProbeStatementsRunUnderANetworkTimeout() throws Exception {
        // Given: two instances whose defaults come from their own roles, so each needs a probe connection
        Role beta = db.role("beta", "s_beta_home");
        Role gamma = db.role("gamma", "s_gamma_home");
        db.createSchemaOwnedBy("s_beta_home", beta);
        db.createSchemaOwnedBy("s_gamma_home", gamma);
        // And: every connection the support owns is recorded with the network timeout of each statement
        TimeoutRecordingConnector connector = new TimeoutRecordingConnector(handout -> {});
        FlywayMigrationRunner runner = recordingRunner(
                connector,
                db.poolConfig(),
                named(instance("beta").credentials(beta), instance("gamma").credentials(gamma)),
                beta(),
                gamma());

        // When
        MigrationReport report = succeeded(runner.run(vertx));

        // Then: the run itself is unaffected
        assertEquals(
                List.of(
                        "beta MIGRATED applied=1 current=1 schema=s_beta_home",
                        "gamma MIGRATED applied=1 current=1 schema=s_gamma_home"),
                outcomesWithSchema(report));
        // And: one guard connection with the root pair and a probe connection with each role were handed out
        assertEquals(1, connector.handoutsFor(postgres.getUsername()).size(), "guard connections: " + connector);
        assertFalse(connector.handoutsFor(beta.name()).isEmpty(), "no probe connection for beta: " + connector);
        assertFalse(connector.handoutsFor(gamma.name()).isEmpty(), "no probe connection for gamma: " + connector);
        // And: every statement on them ran under a positive network timeout no larger than the ceiling
        for (Handout handout : connector.handouts()) {
            assertFalse(handout.networkTimeouts().isEmpty(), "a connection ran no statement: " + handout);
            for (int timeoutMs : handout.networkTimeouts()) {
                assertTrue(
                        timeoutMs > 0 && timeoutMs <= MAX_NETWORK_TIMEOUT_MS,
                        "a statement ran with network timeout " + timeoutMs + " ms, outside (0, "
                                + MAX_NETWORK_TIMEOUT_MS + "]: " + handout);
            }
        }
    }

    private void blackholedProbeFailsClosedWithinTheNetworkTimeout() throws Exception {
        // Given: one instance whose default comes from its own role
        Role beta = db.role("beta", "s_beta_home");
        db.createSchemaOwnedBy("s_beta_home", beta);
        CatalogSnapshot before = db.snapshot();
        try (BlackholeProxy proxy =
                new BlackholeProxy(postgres.getHost(), postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))) {
            // And: the path to the database silently stops forwarding once the probe has connected, when the
            // guard is already open and holds the lock
            TimeoutRecordingConnector connector = new TimeoutRecordingConnector(handout -> {
                if (handout.user().equals(beta.name())) {
                    proxy.blackhole();
                }
            });
            FlywayMigrationRunner runner = recordingRunner(
                    connector, poolConfigThrough(proxy), named(instance("beta").credentials(beta)), beta());

            // When
            long begun = System.nanoTime();
            Future<MigrationReport> run = runner.run(vertx);
            MigrationRunException failure;
            try {
                MigrationReport report = run.toCompletionStage()
                        .toCompletableFuture()
                        .get(MAX_NETWORK_TIMEOUT_MS + NETWORK_TIMEOUT_ALLOWANCE_MS, TimeUnit.MILLISECONDS);
                failure = fail("the run succeeded over a blackholed path: " + outcomes(report));
            } catch (ExecutionException ex) {
                failure = assertInstanceOf(MigrationRunException.class, ex.getCause());
            } catch (TimeoutException ex) {
                failure = fail("the run was still blocked "
                        + (MAX_NETWORK_TIMEOUT_MS + NETWORK_TIMEOUT_ALLOWANCE_MS)
                        + " ms after the path went silent: a probe round trip has no network timeout");
            }
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begun);

            // Then: the probe really connected through the silent path
            assertTrue(proxy.blackholed(), "the probe never connected, so the path never went silent");
            List<Handout> probes = connector.handoutsFor(beta.name());
            assertEquals(1, probes.size(), "probe connections: " + connector);
            // And: the run failed closed at preflight with every entry not run
            assertPreflightRejection(failure, "beta");
            assertHygienic(failure, "s_beta_home");
            // And: within the network timeout of the probe's statement
            assertFalse(probes.get(0).networkTimeouts().isEmpty(), "the probe ran no statement: " + probes.get(0));
            int timeoutMs = probes.get(0).networkTimeouts().get(0);
            assertTrue(
                    timeoutMs > 0 && timeoutMs <= MAX_NETWORK_TIMEOUT_MS,
                    "the probe statement ran with network timeout " + timeoutMs + " ms");
            assertTrue(
                    elapsedMs <= timeoutMs + NETWORK_TIMEOUT_ALLOWANCE_MS,
                    "the run took " + elapsedMs + " ms for a " + timeoutMs + " ms network timeout");
        }
        // And: closing the path released every session, and nothing was written
        db.assertCatalogUnchangedSince(before);
    }

    /** A runner whose support-owned connections go through the given connector, against the given pool. */
    private static FlywayMigrationRunner recordingRunner(
            JdbcConnector connector, DbPoolConfig pool, FlywayConfig config, MigrationDefinition... declarations) {
        return new FlywayMigrationRunner(
                config,
                pool,
                new LinkedHashSet<>(List.of(declarations)),
                DbSchemas.empty(),
                PostgresDatabaseSupport.selector(connector),
                connector);
    }

    /** The invocation's database, reached through the proxy instead of directly. */
    private DbPoolConfig poolConfigThrough(BlackholeProxy proxy) throws SQLException {
        return DbPoolConfig.builder()
                .host("127.0.0.1")
                .port(proxy.port())
                .database(db.database())
                .user(postgres.getUsername())
                .password(postgres.getPassword())
                .build();
    }

    /** One connection handed out by the recording connector: its user and the network timeout of each statement. */
    private record Handout(String user, List<Integer> networkTimeouts) {

        @Override
        public String toString() {
            return "Handout[user=" + user + ", networkTimeouts=" + networkTimeouts + "]";
        }
    }

    /**
     * Opens real connections like the default connector and records, for each one, the network timeout the
     * real connection reports whenever a statement is created on it, so the first round trip is covered.
     * The hook runs after the connection is established and before it is handed out.
     */
    private static final class TimeoutRecordingConnector implements JdbcConnector {

        private final List<Handout> handouts = new CopyOnWriteArrayList<>();
        private final Consumer<Handout> onConnect;

        TimeoutRecordingConnector(Consumer<Handout> onConnect) {
            this.onConnect = onConnect;
        }

        @Override
        public Connection connect(String url, String user, String password) throws SQLException {
            Connection real = DriverManager.getConnection(url, user, password);
            Handout handout = new Handout(user, new CopyOnWriteArrayList<>());
            handouts.add(handout);
            onConnect.accept(handout);
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("createStatement")
                                || name.equals("prepareStatement")
                                || name.equals("prepareCall")) {
                            handout.networkTimeouts().add(real.getNetworkTimeout());
                        }
                        try {
                            return method.invoke(real, args);
                        } catch (InvocationTargetException ex) {
                            throw ex.getCause();
                        }
                    });
        }

        List<Handout> handouts() {
            return List.copyOf(handouts);
        }

        List<Handout> handoutsFor(String user) {
            return handouts.stream().filter(h -> h.user().equals(user)).toList();
        }

        @Override
        public String toString() {
            return String.valueOf(handouts);
        }
    }

    /**
     * A TCP proxy on the loopback address that forwards bytes both ways until it is told to go silent. From
     * then on it reads and discards every byte in both directions on every connection, old and new, without
     * closing anything, like a path that drops packets. Closing it closes every socket, which ends the
     * server sessions and any client read still waiting.
     */
    private static final class BlackholeProxy implements AutoCloseable {

        private final ServerSocket server;
        private final String upstreamHost;
        private final int upstreamPort;
        private final AtomicBoolean silent = new AtomicBoolean();
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final ExecutorService threads =
                Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());

        BlackholeProxy(String upstreamHost, int upstreamPort) throws IOException {
            this.upstreamHost = upstreamHost;
            this.upstreamPort = upstreamPort;
            this.server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            threads.execute(this::accept);
        }

        int port() {
            return server.getLocalPort();
        }

        void blackhole() {
            silent.set(true);
        }

        boolean blackholed() {
            return silent.get();
        }

        private void accept() {
            while (!server.isClosed()) {
                try {
                    Socket client = server.accept();
                    sockets.add(client);
                    Socket upstream = new Socket(upstreamHost, upstreamPort);
                    sockets.add(upstream);
                    threads.execute(() -> pump(client, upstream));
                    threads.execute(() -> pump(upstream, client));
                } catch (IOException ex) {
                    return;
                }
            }
        }

        private void pump(Socket from, Socket to) {
            byte[] buffer = new byte[8192];
            try {
                InputStream in = from.getInputStream();
                OutputStream out = to.getOutputStream();
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    if (!silent.get()) {
                        out.write(buffer, 0, read);
                        out.flush();
                    }
                }
            } catch (IOException ignored) {
                // The other side or the proxy closed the connection.
            } finally {
                closeQuietly(from);
                closeQuietly(to);
            }
        }

        private static void closeQuietly(Socket socket) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closed.
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            for (Socket socket : sockets) {
                closeQuietly(socket);
            }
            threads.shutdownNow();
        }
    }

    // =====================================================================================
    // Configuration helpers
    // =====================================================================================

    private static MigrationDefinition declaration(String name, String fixture) {
        return new MigrationDefinition(name, List.of(FIXTURES + fixture), List.of());
    }

    private static MigrationDefinition alpha() {
        return declaration("alpha", "alpha");
    }

    private static MigrationDefinition beta() {
        return declaration("beta", "beta");
    }

    private static MigrationDefinition gamma() {
        return declaration("gamma", "gamma");
    }

    private static String history(String instance) {
        return "flyway_schema_history_" + instance;
    }

    /** Named mode at the root: MIGRATE ceiling and the probe placeholder every instance inherits. */
    private static FlywayConfig.FlywayConfigBuilder namedRoot() {
        return FlywayConfig.builder().mode(FlywayMode.MIGRATE).placeholders(Map.of(PROBE_KEY, PROBE_VALUE));
    }

    private static FlywayConfig named(InstanceSettings... instances) {
        return namedRoot()
                .instances(Stream.of(instances).map(InstanceSettings::build).toList())
                .build();
    }

    private static InstanceSettings instance(String name) {
        return new InstanceSettings(name);
    }

    /** Placement pairs: instance name, schema, instance name, schema, ... */
    private static DbSchemas placement(String... pairs) {
        Map<String, String> mappings = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            mappings.put(pairs[i], pairs[i + 1]);
        }
        return new DbSchemas(mappings);
    }

    private FlywayMigrationRunner runner(FlywayConfig config, DbSchemas placement, MigrationDefinition... declarations)
            throws SQLException {
        return new FlywayMigrationRunner(
                config, db.poolConfig(), new LinkedHashSet<>(List.of(declarations)), placement);
    }

    /** A runner on the invocation's database whose own connections all go through the counting connector. */
    private FlywayMigrationRunner countingRunner(
            CountingConnector connector, FlywayConfig config, DbSchemas placement, MigrationDefinition... declarations)
            throws SQLException {
        return new FlywayMigrationRunner(
                config,
                db.poolConfig(),
                new LinkedHashSet<>(List.of(declarations)),
                placement,
                PostgresDatabaseSupport.selector(connector),
                connector);
    }

    /** A runner whose destination accepts no connection at all, for checks that must never connect. */
    private static FlywayMigrationRunner unconnectedRunner(
            CountingConnector connector,
            FlywayConfig config,
            DbSchemas placement,
            MigrationDefinition... declarations) {
        DbPoolConfig nowhere = DbPoolConfig.builder()
                .host("127.0.0.1")
                .port(1)
                .database("never_connected")
                .user("nobody")
                .password("unused")
                .build();
        return new FlywayMigrationRunner(
                config,
                nowhere,
                new LinkedHashSet<>(List.of(declarations)),
                placement,
                PostgresDatabaseSupport.selector(connector),
                connector);
    }

    /** Readable form of a fixture value for a display name. */
    private static String describe(String name) {
        if (name.length() > 8 && name.chars().allMatch(c -> c == 'x')) {
            return "of " + name.length() + " characters";
        }
        return "'" + name.replace("\t", "\\t").replace("\n", "\\n") + "'";
    }

    /** Override settings of one named instance; unset fields inherit. */
    private static final class InstanceSettings {

        private final String name;
        private FlywayMode mode;
        private List<String> schemas;
        private String table;
        private Boolean baselineOnMigrate;
        private String baselineVersion;
        private Map<String, String> placeholders;
        private String user;
        private String password;

        InstanceSettings(String name) {
            this.name = name;
        }

        InstanceSettings mode(FlywayMode value) {
            this.mode = value;
            return this;
        }

        InstanceSettings schemas(String... values) {
            return schemas(List.of(values));
        }

        InstanceSettings schemas(List<String> values) {
            this.schemas = values;
            return this;
        }

        InstanceSettings table(String value) {
            this.table = value;
            return this;
        }

        InstanceSettings baseline(boolean onMigrate, String version) {
            this.baselineOnMigrate = onMigrate;
            this.baselineVersion = version;
            return this;
        }

        InstanceSettings placeholders(Map<String, String> values) {
            this.placeholders = values;
            return this;
        }

        InstanceSettings credentials(Role role) {
            this.user = role.name();
            this.password = role.password();
            return this;
        }

        FlywayInstanceConfig build() {
            return new FlywayInstanceConfig(
                    name,
                    mode,
                    null,
                    schemas,
                    table,
                    null,
                    null,
                    baselineOnMigrate,
                    baselineVersion,
                    null,
                    placeholders,
                    user,
                    password);
        }
    }

    // =====================================================================================
    // Reports, failures and futures
    // =====================================================================================

    private static MigrationReport succeeded(Future<MigrationReport> future) throws Exception {
        try {
            return future.toCompletionStage().toCompletableFuture().get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException ex) {
            Throwable cause = ex.getCause();
            String report = cause instanceof MigrationRunException run && run.report() != null
                    ? " " + outcomes(run.report())
                    : "";
            return fail("the run was expected to succeed but failed: " + cause.getMessage() + report);
        }
    }

    private static MigrationRunException rejected(Future<?> future) {
        ExecutionException failure;
        try {
            future.toCompletionStage().toCompletableFuture().get(RUN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return fail("the run was expected to be rejected but succeeded: " + describeSuccess(future));
        } catch (ExecutionException ex) {
            failure = ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return fail("interrupted while waiting for the run");
        } catch (TimeoutException ex) {
            return fail("the run did not finish within " + RUN_TIMEOUT_SECONDS + " seconds");
        }
        return assertInstanceOf(MigrationRunException.class, failure.getCause());
    }

    private static String describeSuccess(Future<?> future) {
        return future.result() instanceof MigrationReport report
                ? outcomes(report).toString()
                : String.valueOf(future.result());
    }

    /** Ordered outcomes, one line per entry: name, status, applied count and current version. */
    private static List<String> outcomes(MigrationReport report) {
        return report.instances().stream()
                .map(r -> r.instance().name() + " " + r.status() + " applied=" + r.migrationsApplied() + " current="
                        + r.currentVersion())
                .toList();
    }

    /** Ordered outcomes including the history schema each entry reports. */
    private static List<String> outcomesWithSchema(MigrationReport report) {
        return report.instances().stream()
                .map(r -> r.instance().name() + " " + r.status() + " applied=" + r.migrationsApplied() + " current="
                        + r.currentVersion() + " schema=" + r.instance().schema())
                .toList();
    }

    private static List<String> notRun(String... names) {
        return Stream.of(names)
                .map(name -> name + " NOT_RUN applied=0 current=null")
                .toList();
    }

    private static List<String> schemasOf(List<MigrationInstanceView> views) {
        return views.stream().map(MigrationInstanceView::schema).toList();
    }

    /** A batch rejected at preflight: safe category, the run operation, every entry not run. */
    private static void assertPreflightRejection(MigrationRunException failure, String... order) {
        assertEquals("PREFLIGHT", failure.failureCode(), "failure code of: " + failure.getMessage());
        assertEquals("run", failure.operation());
        assertNotNull(failure.report(), "a run failure carries the ordered report");
        assertEquals(notRun(order), outcomes(failure.report()));
    }

    /** The configuration is rejected by instances() before the runner opens any connection. */
    private void assertConfigurationRejectionWithoutConnecting(
            MigrationRunException failure, CountingConnector connector, String offendingValue) {
        assertEquals("CONFIGURATION", failure.failureCode(), "failure code of: " + failure.getMessage());
        assertEquals("instances", failure.operation());
        assertNull(failure.report(), "instances() failures carry no report");
        assertNamesOneOf(failure, "alpha");
        if (isDistinctive(offendingValue)) {
            assertHygienic(failure, offendingValue);
        } else {
            assertHygienic(failure);
        }
        assertEquals(0, connector.attempts(), "the rule is applied before connecting");
    }

    /** Values that cannot occur in a fixed message by accident, so their presence means they were echoed. */
    private static boolean isDistinctive(String value) {
        return value.strip().length() >= 4 && !PostgresIdentifierCases.REJECTED_SCHEMA_NAMES.contains(value);
    }

    private static void assertNamesOneOf(MigrationRunException failure, String... instances) {
        String message = String.valueOf(failure.getMessage());
        assertTrue(
                Stream.of(instances).anyMatch(message::contains),
                "the message names none of " + List.of(instances) + ": " + message);
    }

    /**
     * No native throwable is retained, and neither configured values, the placeholder value, a JDBC URL nor
     * a password appear in the failure.
     */
    private void assertHygienic(MigrationRunException failure, String... configuredValues) {
        assertHygienic(db, failure, configuredValues);
    }

    /** As {@link #assertHygienic(MigrationRunException, String...)}, for the roles and database of another world. */
    private void assertHygienic(Fixture world, MigrationRunException failure, String... configuredValues) {
        assertNull(failure.getCause(), "no original throwable may be retained as the cause");
        assertEquals(0, failure.getSuppressed().length, "no throwable may be retained as suppressed");
        String message = String.valueOf(failure.getMessage());
        for (String value : configuredValues) {
            assertFalse(message.contains(value), "a configured value appears in the message: " + message);
        }
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        String everything = trace + " " + failure.report();
        assertFalse(everything.contains(PROBE_VALUE), "the placeholder value leaked into the failure");
        assertFalse(everything.contains("jdbc:"), "a JDBC URL leaked into the failure");
        for (Role role : world.roles) {
            assertFalse(everything.contains(role.password()), "a password leaked into the failure");
        }
        if (world.database != null) {
            assertFalse(message.contains(world.database), "the database name appears in the message: " + message);
        }
    }

    // =====================================================================================
    // PostgreSQL fixture
    // =====================================================================================

    /** A migration role; its rendering never includes the password. */
    private record Role(String name, String password) {

        @Override
        public String toString() {
            return "Role[" + name + "]";
        }
    }

    /** Every schema, relation and table row count outside the system and audit schemas. */
    private record CatalogSnapshot(List<String> entries) {

        @Override
        public String toString() {
            return String.join("\n", entries);
        }
    }

    /**
     * The invocation's database, created on first use, and its migration roles. Closing drops what the
     * roles own, the database and the roles.
     */
    private final class Fixture implements AutoCloseable {

        private final int id = SEQUENCE.incrementAndGet();
        private final List<Role> roles = new ArrayList<>();
        private String database;

        String database() throws SQLException {
            if (database == null) {
                String name = "flyway_safety_it_" + id;
                executeOnServer("CREATE DATABASE " + name);
                database = name;
            }
            return database;
        }

        DbPoolConfig poolConfig() throws SQLException {
            return DbPoolConfig.builder()
                    .host(postgres.getHost())
                    .port(postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
                    .database(database())
                    .user(postgres.getUsername())
                    .password(postgres.getPassword())
                    .build();
        }

        String jdbcUrl() throws SQLException {
            return "jdbc:postgresql://" + postgres.getHost() + ":"
                    + postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT) + "/" + database();
        }

        Connection connect() throws SQLException {
            return DriverManager.getConnection(jdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        void execute(String sql) throws SQLException {
            try (Connection connection = connect();
                    Statement statement = connection.createStatement()) {
                statement.execute(sql);
            }
        }

        /**
         * Creates a login role allowed to create schemas in this database. A search path, when given, is
         * set for this database before the role's first connection.
         */
        Role role(String label, String... searchPath) throws SQLException {
            Role role = new Role(
                    "safety_" + label + "_" + id,
                    "pw_" + UUID.randomUUID().toString().replace("-", ""));
            executeOnServer("CREATE ROLE " + role.name() + " LOGIN PASSWORD '" + role.password() + "'");
            roles.add(role);
            executeOnServer("GRANT CREATE ON DATABASE " + database() + " TO " + role.name());
            if (searchPath.length > 0) {
                executeOnServer("ALTER ROLE " + role.name() + " IN DATABASE " + database() + " SET search_path = "
                        + String.join(", ", searchPath));
            }
            return role;
        }

        void createSchema(String schema) throws SQLException {
            execute("CREATE SCHEMA " + schema);
        }

        void createSchemaOwnedBy(String schema, Role owner) throws SQLException {
            execute("CREATE SCHEMA " + schema + " AUTHORIZATION " + owner.name());
        }

        void grantSchema(String schema, Role... grantees) throws SQLException {
            for (Role grantee : grantees) {
                execute("GRANT USAGE, CREATE ON SCHEMA " + schema + " TO " + grantee.name());
            }
        }

        /** Puts a table with one row into an existing schema. */
        void occupy(String schema) throws SQLException {
            execute("CREATE TABLE " + schema + ".pre_existing (id INTEGER PRIMARY KEY);" + " INSERT INTO " + schema
                    + ".pre_existing (id) VALUES (1);");
        }

        /**
         * Writes a conventional history with native single Flyway and the pool credentials, in the given
         * managed schema or, when {@code null}, the connection's default schema.
         */
        void nativeHistory(String schema, String... fixtures) throws SQLException {
            nativeHistoryNamed(schema, CONVENTIONAL_HISTORY, fixtures);
        }

        /** Writes a single-Flyway history like {@link #nativeHistory(String, String...)} under the given table name. */
        void nativeHistoryNamed(String schema, String table, String... fixtures) throws SQLException {
            FluentConfiguration configuration = Flyway.configure()
                    .table(table)
                    .dataSource(jdbcUrl(), postgres.getUsername(), postgres.getPassword())
                    .locations(Stream.of(fixtures).map(f -> FIXTURES + f).toArray(String[]::new));
            if (schema != null) {
                configuration.schemas(schema);
            }
            configuration.load().migrate();
        }

        void createAuditTable() throws SQLException {
            execute("CREATE SCHEMA " + AUDIT_SCHEMA + "; CREATE TABLE " + AUDIT_SCHEMA
                    + ".callback_event (event TEXT NOT NULL)");
        }

        void clearAudit() throws SQLException {
            execute("DELETE FROM " + AUDIT_SCHEMA + ".callback_event");
        }

        int auditEvents() throws SQLException {
            return countRows(AUDIT_SCHEMA + ".callback_event");
        }

        boolean schemaExists(String schema) throws SQLException {
            return count("SELECT count(*) FROM pg_catalog.pg_namespace WHERE nspname = ?", schema) > 0;
        }

        boolean tableExists(String schema, String table) throws SQLException {
            return count(
                            "SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n"
                                    + " ON n.oid = c.relnamespace WHERE n.nspname = ? AND c.relname = ?"
                                    + " AND c.relkind IN ('r', 'p')",
                            schema,
                            table)
                    > 0;
        }

        /** Every relation of a schema, as {@code name:kind}; empty when the schema holds none or is missing. */
        List<String> relationsIn(String schema) throws SQLException {
            return strings(
                    "SELECT c.relname || ':' || c.relkind::text FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n"
                            + " ON n.oid = c.relnamespace WHERE n.nspname = ? ORDER BY 1",
                    schema);
        }

        int countRows(String qualifiedTable) throws SQLException {
            return count("SELECT count(*) FROM " + qualifiedTable);
        }

        /** Versions of the successful SQL migrations in a history table; empty when it does not exist. */
        List<String> appliedVersions(String schema, String table) throws SQLException {
            return historyQuery(schema, table, "version", "success AND type = 'SQL'");
        }

        /** Every history row as {@code version type}; empty when the history does not exist. */
        List<String> historyRows(String schema, String table) throws SQLException {
            return historyQuery(schema, table, "coalesce(version, '-') || ' ' || type", "true");
        }

        /** Successful SQL migrations as {@code version type script}; empty when the history does not exist. */
        List<String> appliedScripts(String schema, String table) throws SQLException {
            return historyQuery(schema, table, "version || ' ' || type || ' ' || script", "success AND type = 'SQL'");
        }

        private List<String> historyQuery(String schema, String table, String column, String filter)
                throws SQLException {
            if (!tableExists(schema, table)) {
                return List.of();
            }
            return strings("SELECT " + column + " FROM \"" + schema + "\".\"" + table + "\" WHERE " + filter
                    + " ORDER BY installed_rank");
        }

        DbPoolConfig poolConfig(Role role) throws SQLException {
            return DbPoolConfig.builder()
                    .host(postgres.getHost())
                    .port(postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT))
                    .database(database())
                    .user(role.name())
                    .password(role.password())
                    .build();
        }

        Connection connectAs(Role role) throws SQLException {
            return DriverManager.getConnection(jdbcUrl(), role.name(), role.password());
        }

        /** Takes the fixed advisory key on a session of this database and keeps the session open. */
        LockHolder holdLock() throws SQLException {
            Connection connection = connect();
            try {
                scalar(
                        connection,
                        "SELECT pg_catalog.pg_advisory_lock(" + LOCK_KEY_FIRST + ", " + LOCK_KEY_SECOND + ")");
                return new LockHolder(
                        connection, Integer.parseInt(scalar(connection, "SELECT pg_catalog.pg_backend_pid()")));
            } catch (SQLException | RuntimeException ex) {
                connection.close();
                throw ex;
            }
        }

        /** Tries the fixed key from a fresh session and gives it straight back; {@code true} when it was free. */
        boolean tryLockExternally() throws SQLException {
            try (Connection connection = connect()) {
                boolean free = flag(connection, tryLockSql("pg_catalog."));
                if (free) {
                    scalar(
                            connection,
                            "SELECT pg_catalog.pg_advisory_unlock(" + LOCK_KEY_FIRST + ", " + LOCK_KEY_SECOND + ")");
                }
                return free;
            }
        }

        /** Backends of this database holding the fixed key. */
        List<Integer> lockOwnerPids() throws SQLException {
            return strings("SELECT l.pid FROM pg_catalog.pg_locks l WHERE l.locktype = 'advisory'"
                            + " AND l.classid = " + LOCK_KEY_FIRST + "::oid AND l.objid = " + LOCK_KEY_SECOND
                            + "::oid AND l.objsubid = 2 AND l.granted AND l.database = (SELECT d.oid FROM"
                            + " pg_catalog.pg_database d WHERE d.datname = pg_catalog.current_database())"
                            + " ORDER BY l.pid")
                    .stream()
                    .map(Integer::valueOf)
                    .toList();
        }

        /**
         * Creates the audit-schema probes migrations call from their own sessions: one records whether the
         * fixed key is free for that session, the other ends the backend that holds it. Both are qualified
         * throughout, so a hostile search path cannot redirect them.
         */
        void createLockProbe() throws SQLException {
            execute("""
                    CREATE SCHEMA IF NOT EXISTS s_audit;
                    GRANT USAGE ON SCHEMA s_audit TO PUBLIC;
                    CREATE TABLE s_audit.lock_probe (seq SERIAL, probe TEXT NOT NULL);
                    CREATE FUNCTION s_audit.record_lock_probe(label TEXT) RETURNS VOID LANGUAGE plpgsql
                        SECURITY DEFINER SET search_path = pg_catalog AS $$
                    DECLARE free BOOLEAN;
                    BEGIN
                        free := pg_catalog.pg_try_advisory_lock(1448365393, 1296648018);
                        IF free THEN
                            PERFORM pg_catalog.pg_advisory_unlock(1448365393, 1296648018);
                        END IF;
                        INSERT INTO s_audit.lock_probe (probe)
                            VALUES (label || CASE WHEN free THEN ':free' ELSE ':held' END);
                    END $$;
                    CREATE FUNCTION s_audit.terminate_lock_holder() RETURNS VOID LANGUAGE plpgsql
                        SECURITY DEFINER SET search_path = pg_catalog AS $$
                    DECLARE victim INTEGER;
                    BEGIN
                        SELECT l.pid INTO victim FROM pg_catalog.pg_locks l
                         WHERE l.locktype = 'advisory' AND l.classid = 1448365393::oid
                           AND l.objid = 1296648018::oid AND l.objsubid = 2 AND l.granted
                           AND l.database = (SELECT d.oid FROM pg_catalog.pg_database d
                                              WHERE d.datname = pg_catalog.current_database())
                           AND l.pid <> pg_catalog.pg_backend_pid();
                        IF victim IS NOT NULL THEN
                            PERFORM pg_catalog.pg_terminate_backend(victim);
                            FOR attempt IN 1..400 LOOP
                                EXIT WHEN NOT EXISTS (SELECT 1 FROM pg_catalog.pg_locks l WHERE l.pid = victim);
                                PERFORM pg_catalog.pg_sleep(0.025);
                            END LOOP;
                        END IF;
                        INSERT INTO s_audit.lock_probe (probe)
                            VALUES ('guard-killed:' || (victim IS NOT NULL)::text);
                    END $$;
                    """);
        }

        /** What the migrations' probes saw, in order, as {@code label:held} or {@code label:free}. */
        List<String> lockProbes() throws SQLException {
            return strings("SELECT probe FROM " + AUDIT_SCHEMA + ".lock_probe ORDER BY seq");
        }

        /** Takes the catalog snapshot the no-write assertions compare. */
        CatalogSnapshot snapshot() throws SQLException {
            List<String> relations = new ArrayList<>();
            Set<String> schemas = new LinkedHashSet<>();
            try (Connection connection = connect();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT n.nspname, c.relname, c.relkind FROM pg_catalog.pg_namespace n"
                                    + " LEFT JOIN pg_catalog.pg_class c ON c.relnamespace = n.oid"
                                    + " WHERE n.nspname NOT LIKE 'pg\\_%' AND n.nspname <> 'information_schema'"
                                    + " AND n.nspname <> ? ORDER BY n.nspname, c.relname")) {
                statement.setString(1, AUDIT_SCHEMA);
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        String schema = rows.getString(1);
                        String relation = rows.getString(2);
                        String kind = rows.getString(3);
                        schemas.add("schema " + schema);
                        if (relation != null) {
                            relations.add(schema + "." + relation + " kind=" + kind);
                        }
                    }
                }
            }
            List<String> entries = new ArrayList<>(schemas);
            for (String relation : relations) {
                if (relation.endsWith(" kind=r") || relation.endsWith(" kind=p")) {
                    String name = relation.substring(0, relation.indexOf(" kind="));
                    int dot = name.indexOf('.');
                    String quoted = "\"" + name.substring(0, dot) + "\".\"" + name.substring(dot + 1) + "\"";
                    entries.add("table " + name + " rows=" + count("SELECT count(*) FROM " + quoted));
                } else {
                    entries.add("relation " + relation);
                }
            }
            return new CatalogSnapshot(List.copyOf(entries));
        }

        void assertCatalogUnchangedSince(CatalogSnapshot before) throws SQLException {
            assertEquals(
                    before.toString(),
                    snapshot().toString(),
                    "the framework wrote a schema, relation or row it must not write");
        }

        /**
         * Asserts that no session of the database is left open. Backends exit a moment after the client
         * closes, so this polls briefly; the remaining sessions are listed when some stay.
         */
        void assertNoOpenConnections() throws Exception {
            assertNoOpenConnectionsBesides();
        }

        /** Like {@link #assertNoOpenConnections()}, but sessions with the given backend pids may stay open. */
        void assertNoOpenConnectionsBesides(int... allowedPids) throws Exception {
            if (database == null) {
                return;
            }
            String query = "SELECT pid, usename, application_name, state FROM pg_catalog.pg_stat_activity"
                    + " WHERE datname = ? AND pid <> pg_catalog.pg_backend_pid()";
            Set<Integer> allowed = new LinkedHashSet<>();
            for (int pid : allowedPids) {
                allowed.add(pid);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (true) {
                List<String> sessions = new ArrayList<>();
                try (Connection admin = DriverManager.getConnection(
                                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                        PreparedStatement statement = admin.prepareStatement(query)) {
                    statement.setString(1, database);
                    try (ResultSet rows = statement.executeQuery()) {
                        while (rows.next()) {
                            if (allowed.contains(rows.getInt(1))) {
                                continue;
                            }
                            sessions.add("pid=" + rows.getInt(1) + " user=" + rows.getString(2) + " application="
                                    + rows.getString(3) + " state=" + rows.getString(4));
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

        @Override
        public void close() throws SQLException {
            try {
                if (database != null) {
                    for (Role role : roles) {
                        execute("DROP OWNED BY " + role.name());
                    }
                    executeOnServer("DROP DATABASE " + database + " WITH (FORCE)");
                }
            } finally {
                for (Role role : roles) {
                    executeOnServer("DROP ROLE IF EXISTS " + role.name());
                }
            }
        }

        private int count(String sql, String... parameters) throws SQLException {
            return Integer.parseInt(strings(sql, parameters).get(0));
        }

        private List<String> strings(String sql, String... parameters) throws SQLException {
            List<String> values = new ArrayList<>();
            try (Connection connection = connect();
                    PreparedStatement statement = connection.prepareStatement(sql)) {
                for (int i = 0; i < parameters.length; i++) {
                    statement.setString(i + 1, parameters[i]);
                }
                try (ResultSet rows = statement.executeQuery()) {
                    while (rows.next()) {
                        values.add(rows.getString(1));
                    }
                }
            }
            return values;
        }

        private void executeOnServer(String sql) throws SQLException {
            try (Connection admin = DriverManager.getConnection(
                            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
                    Statement statement = admin.createStatement()) {
                statement.execute(sql);
            }
        }
    }
}
