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

import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.MigrationDefinition;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Proves that named migration orchestration only dispatches to the selected database support and
 * never assumes PostgreSQL. No scenario needs a database.
 *
 * <p>The supported backend of most scenarios is an internal alternative whose destination is a JDBC
 * URL that native Flyway cannot handle, so a run reaches validation, session opening, preflight, the
 * per-instance guard and a native failure without any database. It applies identifier rules of its
 * own, deliberately different from PostgreSQL's, and records every call in order. The unsupported
 * named destination, the non-PostgreSQL legacy destination and the real PostgreSQL selector each get
 * their own scenarios.
 */
class FlywayDatabaseBoundaryTest {

    private static final String ALT_URL = "jdbc:altdb://alt-host/alt";
    private static final String POSTGRES_URL = "jdbc:postgresql://127.0.0.1:1/none";

    private static final String ROOT_USER = "root_ddl";
    private static final String ROOT_PASSWORD = "root-secret";
    private static final long LOCK_TIMEOUT_MS = 1234L;

    /** Text that only a backend or native layer could produce; it must never reach a public failure. */
    private static final String BACKEND_TEXT = "backend-secret-text";

    private static final String ALPHA = "classpath:db/named-config/alpha";
    private static final String BETA = "classpath:db/named-config/beta";
    private static final String GAMMA = "classpath:db/named-config/gamma";

    private static final Set<String> FAILURE_CODES =
            Set.of("CONFIGURATION", "RESOURCE", "COORDINATION", "PREFLIGHT", "MIGRATION", "VALIDATION");

    private static final DbPoolConfig POOL = DbPoolConfig.builder()
            .host("localhost")
            .port(5432)
            .database("app")
            .user("pool_user")
            .password("pool-secret")
            .build();

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    @FunctionalInterface
    private interface Scenario {
        void check() throws Exception;
    }

    static Stream<Arguments> scenarios() {
        return Stream.of(
                scenario(
                        "supported backend: validation, session, preflight and guard run in order with the"
                                + " frozen view",
                        FlywayDatabaseBoundaryTest::supportedBackendReceivesEveryOperationInOrder),
                scenario(
                        "supported backend: lock timeout and root-first schema pass through a recording double",
                        FlywayDatabaseBoundaryTest::sessionParametersPassThrough),
                scenario(
                        "supported backend: a history owner reported by preflight is validated natively before"
                                + " any instance",
                        FlywayDatabaseBoundaryTest::historyOwnersAreValidatedBeforeAnyInstance),
                scenario(
                        "supported backend: identifier rules belong to the backend, not to orchestration",
                        FlywayDatabaseBoundaryTest::identifierRulesBelongToTheBackend),
                scenario(
                        "backend rejects the configuration: instances() fails before any session",
                        FlywayDatabaseBoundaryTest::validationRejectionFailsInstances),
                scenario(
                        "backend rejects the configuration: run() reports every entry not run",
                        FlywayDatabaseBoundaryTest::validationRejectionFailsRun),
                scenario(
                        "backend validation breaks its contract: no backend text escapes",
                        FlywayDatabaseBoundaryTest::validationContractBreakIsSafe),
                scenario(
                        "backend cannot open its session: coordination failure, every entry not run",
                        FlywayDatabaseBoundaryTest::sessionOpenFailureIsCoordination),
                scenario(
                        "backend lock acquisition is rejected: coordination failure, every entry not run",
                        FlywayDatabaseBoundaryTest::sessionLockRejectionIsCoordination),
                scenario(
                        "backend preflight rejects the batch: every entry not run and the session closes",
                        FlywayDatabaseBoundaryTest::preflightRejectionLeavesEveryEntryNotRun),
                scenario(
                        "backend preflight breaks its contract: no backend text escapes",
                        FlywayDatabaseBoundaryTest::preflightContractBreakIsSafe),
                scenario(
                        "backend guard rejects the first instance: it fails with nothing applied",
                        FlywayDatabaseBoundaryTest::guardRejectionFailsTheInstance),
                scenario(
                        "backend guard reports a lost lock: coordination failure at the instance",
                        FlywayDatabaseBoundaryTest::guardLostLockIsCoordination),
                scenario(
                        "backend guard breaks its contract: no backend text escapes",
                        FlywayDatabaseBoundaryTest::guardContractBreakIsSafe),
                scenario(
                        "unsupported named destination: configuration failure before any session or connection",
                        FlywayDatabaseBoundaryTest::unsupportedNamedDestinationFailsBeforeWrites),
                scenario(
                        "non-PostgreSQL legacy destination: native failure path, no selection, session or probe",
                        FlywayDatabaseBoundaryTest::legacyNonPostgresDestinationKeepsTheNativePath),
                scenario(
                        "PostgreSQL selector: a named migrate runs a real guard and never a permissive no-op",
                        FlywayDatabaseBoundaryTest::postgresSelectorNeverFallsBackToANoOpGuard),
                scenario(
                        "orchestration sources contain no PostgreSQL catalog, session or lock text",
                        FlywayDatabaseBoundaryTest::orchestrationSourcesCarryNoPostgresText));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void dispatchesBackendOperationsWithoutPostgresAssumptions(String label, Scenario scenario) throws Exception {
        scenario.check();
    }

    private static Arguments scenario(String label, Scenario scenario) {
        return Arguments.of(label, scenario);
    }

    // =====================================================================================
    // Supported alternative backend: dispatch and pass-through
    // =====================================================================================

    private static void supportedBackendReceivesEveryOperationInOrder() throws Exception {
        // Given: an alternative backend, a root schema list, an instance that replaces it with the
        // connection default, and a disabled instance between two enabled ones
        Fixture fixture = new Fixture();
        fixture.rootSchemas = List.of("root_first", "extra_schema");
        fixture.overrides =
                List.of(override("job", null, List.of(), null), override("audit", FlywayMode.DISABLED, null, null));
        fixture.backend.freezeUnknownDefaults = true;

        // When: the batch runs and the first instance fails natively on the unusable destination
        MigrationRunException failure = fixture.runFailure();

        // Then: the backend saw validation, open, preflight, the guard and close, in that order
        assertEquals(List.of("validate", "open", "preflight", "beforeInstance:job", "close"), fixture.backend.events());
        assertEquals(1, fixture.backend.closes());
        assertEquals(0, fixture.connector.attempts(), "orchestration opened a JDBC connection of its own");

        // And: validation saw every instance, preflight only the enabled ones, with the root-first schema
        List<MigrationInstanceView> validated =
                fixture.backend.validatedBatches().get(0);
        assertEquals(List.of("job", "audit", "app"), names(validated));
        assertEquals(
                validated.stream().filter(v -> v.mode() != FlywayMode.DISABLED).toList(),
                fixture.backend.preflightViews().get(0));
        assertEquals(List.of("root_first"), fixture.backend.rootFirstSchemas());

        // And: the session was opened with the destination, the root pair and the configured timeout
        assertEquals(1, fixture.backend.opens().size());
        Open open = fixture.backend.opens().get(0);
        assertEquals(ALT_URL, open.jdbcUrl());
        assertEquals(ROOT_USER, open.user());
        assertEquals(ROOT_PASSWORD, open.password());
        assertEquals(LOCK_TIMEOUT_MS, open.lockTimeoutMs());

        // And: the guard received the frozen view the preflight returned, and the report carries it
        MigrationInstanceView original = validated.get(0);
        assertNull(original.schema(), "the fixture needs an instance without a known default schema");
        assertEquals(
                List.of(original.withSchema("frozen_job")),
                fixture.backend.beforeInstanceViews(),
                "the guard must receive the frozen view");
        assertEquals("MIGRATION", failure.failureCode());
        assertEquals("run", failure.operation());
        assertTrue(failure.getMessage().contains("'job'"), failure.getMessage());
        assertSafe(failure, BACKEND_TEXT, "jdbc:", ROOT_PASSWORD);
        List<MigrationInstanceResult> results = failure.report().instances();
        assertEquals(List.of("job", "audit", "app"), resultNames(results));
        assertEquals(
                List.of(MigrationStatus.FAILED, MigrationStatus.NOT_RUN, MigrationStatus.NOT_RUN),
                results.stream().map(MigrationInstanceResult::status).toList());
        assertEquals("frozen_job", results.get(0).instance().schema(), "the report must show the frozen schema");
        assertEquals(0, results.get(0).migrationsApplied());
        assertNull(results.get(0).currentVersion());
    }

    private static void sessionParametersPassThrough() throws Exception {
        // Given: the recording double as backend, no root schema list and a configured timeout
        Fixture fixture = new Fixture();
        fixture.selector = fixture.recording.selector();

        // When
        MigrationRunException failure = fixture.runFailure();

        // Then: the session got the timeout, the preflight got the enabled instances and no root schema
        assertEquals(List.of(LOCK_TIMEOUT_MS), fixture.recording.lockTimeouts());
        assertEquals(1, fixture.recording.opens().size());
        assertEquals(ALT_URL, fixture.recording.opens().get(0).jdbcUrl());
        assertEquals(1, fixture.recording.rootFirstSchemas().size());
        assertNull(fixture.recording.rootFirstSchemas().get(0), "no root schema list means no root-first schema");
        assertEquals(1, fixture.recording.preflights().size());
        assertEquals(List.of("job", "app"), names(fixture.recording.preflights().get(0)));
        assertEquals(
                fixture.recording.preflights().get(0),
                fixture.recording.preflightResults().get(0).frozenViews());
        assertEquals(
                List.of("job"),
                fixture.recording.beforeInstances().stream()
                        .map(MigrationInstanceView::name)
                        .toList());
        assertEquals(1, fixture.recording.closedSessions());
        assertEquals("MIGRATION", failure.failureCode());
        assertEquals(0, fixture.connector.attempts());
    }

    private static void historyOwnersAreValidatedBeforeAnyInstance() throws Exception {
        // Given: a backend whose preflight reports one instance as the owner of an existing history
        Fixture fixture = new Fixture();
        fixture.backend.historyOwners = Set.of("job");

        // When: the destination cannot be validated natively, so the owner validation must fail
        MigrationRunException failure = fixture.runFailure();

        // Then: the owner validation ran after preflight and before the guard of any instance
        assertEquals(List.of("validate", "open", "preflight", "close"), fixture.backend.events());
        assertTrue(fixture.backend.beforeInstanceViews().isEmpty(), "no instance may start after a failed owner");
        assertEquals("PREFLIGHT", failure.failureCode());
        assertEquals("run", failure.operation());
        assertAllNotRun(failure.report(), List.of("job", "audit", "app"));
        assertSafe(failure, BACKEND_TEXT, "jdbc:", ROOT_PASSWORD);
        assertEquals(1, fixture.backend.closes());
    }

    private static void identifierRulesBelongToTheBackend() {
        // Given: configurations the alternative backend accepts and PostgreSQL rejects
        String longTable = "history_" + "x".repeat(50);
        assertEquals(58, longTable.length());

        // Then: the alternative backend accepts a schema with the reserved PostgreSQL prefix
        Fixture reservedSchema = new Fixture();
        reservedSchema.placement = Map.of("job", "pg_audit");
        assertEquals("pg_audit", view(reservedSchema.instances(), "job").schema());
        assertEquals(List.of("validate"), reservedSchema.backend.events());

        // And: it accepts a history table longer than PostgreSQL allows
        Fixture longHistory = new Fixture();
        longHistory.overrides = List.of(override("job", null, null, longTable));
        assertEquals(longTable, view(longHistory.instances(), "job").table());

        // And: PostgreSQL's own backend, selected for a PostgreSQL destination, rejects both
        for (Fixture postgres : List.of(reservedSchema.forPostgres(), longHistory.forPostgres())) {
            MigrationRunException rejection = assertThrows(
                    MigrationRunException.class, () -> postgres.runner().instances());
            assertEquals("CONFIGURATION", rejection.failureCode());
            assertEquals("instances", rejection.operation());
            assertNull(rejection.report());
            assertTrue(rejection.getMessage().contains("job"), "the rejection must name the instance");
            assertSafe(rejection, "jdbc:", ROOT_PASSWORD);
            assertEquals(0, postgres.connector.attempts());
        }

        // And: a rule that only the alternative backend has rejects what PostgreSQL accepts
        Fixture digitTable = new Fixture();
        digitTable.overrides = List.of(override("job", null, null, "history_2"));
        MigrationRunException altRejection = assertThrows(
                MigrationRunException.class, () -> digitTable.runner().instances());
        assertFailure(altRejection, "CONFIGURATION", "instances", Fixture.DIGIT_RULE_DETAIL);
        assertEquals(
                "history_2", view(digitTable.forPostgres().instances(), "job").table());
    }

    // =====================================================================================
    // Injected failures: the pinned code, no untouched entry attempted, no backend text leaking
    // =====================================================================================

    private static void validationRejectionFailsInstances() {
        // Given: a backend that rejects the configuration with a bare detail
        Fixture fixture = new Fixture();
        fixture.backend.validationFailure =
                new MigrationRunException(Fixture.VALIDATION_DETAIL, "CONFIGURATION", "configuration", null);

        // When
        MigrationRunException failure =
                assertThrows(MigrationRunException.class, () -> fixture.runner().instances());

        // Then: the orchestration adds the operation prefix exactly once and no session was opened
        assertFailure(failure, "CONFIGURATION", "instances", Fixture.VALIDATION_DETAIL);
        assertNull(failure.report());
        assertSafe(failure, BACKEND_TEXT);
        assertEquals(List.of("validate"), fixture.backend.events());
    }

    private static void validationRejectionFailsRun() throws Exception {
        // Given
        Fixture fixture = new Fixture();
        fixture.backend.validationFailure =
                new MigrationRunException(Fixture.VALIDATION_DETAIL, "CONFIGURATION", "configuration", null);

        // When
        MigrationRunException failure = fixture.runFailure();

        // Then: nothing was attempted and no session was opened
        assertFailure(failure, "CONFIGURATION", "run", Fixture.VALIDATION_DETAIL);
        assertAllNotRun(failure.report(), List.of("job", "audit", "app"));
        assertSafe(failure, BACKEND_TEXT);
        assertEquals(List.of("validate"), fixture.backend.events());
        assertEquals(0, fixture.backend.closes());
    }

    private static void validationContractBreakIsSafe() throws Exception {
        // Given: a backend that throws an unexpected exception carrying its own text and cause
        Fixture fixture = new Fixture();
        fixture.backend.validationFailure = new IllegalStateException(BACKEND_TEXT, new SQLException(BACKEND_TEXT));

        // When
        MigrationRunException failure = fixture.runFailure();

        // Then: a configuration failure with a fixed detail, no cause and none of the backend text
        assertEquals("CONFIGURATION", failure.failureCode());
        assertEquals("run", failure.operation());
        assertAllNotRun(failure.report(), List.of("job", "audit", "app"));
        assertSafe(failure, BACKEND_TEXT);
        assertEquals(List.of("validate"), fixture.backend.events());
    }

    private static void sessionOpenFailureIsCoordination() throws Exception {
        // Given: a backend whose session cannot be opened, with backend text in the SQL failure
        Fixture fixture = new Fixture();
        fixture.backend.openFailure = new SQLException(BACKEND_TEXT, "08001", new RuntimeException(BACKEND_TEXT));

        // When
        MigrationRunException failure = fixture.runFailure();

        // Then: coordination failure naming no instance, nothing attempted and nothing leaked
        assertEquals("COORDINATION", failure.failureCode());
        assertEquals("coordination", failure.operation());
        assertTrue(failure.getMessage().startsWith("Named migration coordination failed: "), failure.getMessage());
        assertFalse(failure.getMessage().contains("instance '"), failure.getMessage());
        assertAllNotRun(failure.report(), List.of("job", "audit", "app"));
        assertSafe(failure, BACKEND_TEXT, "jdbc:", ROOT_PASSWORD);
        assertEquals(List.of("validate", "open"), fixture.backend.events());
    }

    private static void sessionLockRejectionIsCoordination() throws Exception {
        // Given: a backend whose lock acquisition times out with a bare detail
        Fixture fixture = new Fixture();
        fixture.backend.openFailure =
                new MigrationRunException(Fixture.COORDINATION_DETAIL, "COORDINATION", "coordination", null);

        // When
        MigrationRunException failure = fixture.runFailure();

        // Then: the prefix is added once, the disabled entry is not run either, nothing else happened
        assertFailure(failure, "COORDINATION", "coordination", Fixture.COORDINATION_DETAIL);
        assertAllNotRun(failure.report(), List.of("job", "audit", "app"));
        assertSafe(failure, BACKEND_TEXT);
        assertEquals(List.of("validate", "open"), fixture.backend.events());
    }

    private static void preflightRejectionLeavesEveryEntryNotRun() throws Exception {
        // Given: a backend that rejects the batch at preflight with a bare detail
        Fixture fixture = new Fixture();
        fixture.backend.preflightFailure =
                new MigrationRunException(Fixture.PREFLIGHT_DETAIL, "PREFLIGHT", "run", null);

        // When
        MigrationRunException failure = fixture.runFailure();

        // Then: the pinned code and operation, every entry not run, the session released, no guard call
        assertFailure(failure, "PREFLIGHT", "run", Fixture.PREFLIGHT_DETAIL);
        assertAllNotRun(failure.report(), List.of("job", "audit", "app"));
        assertSafe(failure, BACKEND_TEXT);
        assertEquals(List.of("validate", "open", "preflight", "close"), fixture.backend.events());
        assertEquals(1, fixture.backend.closes());
    }

    private static void preflightContractBreakIsSafe() throws Exception {
        // Given: a backend that throws an unexpected exception carrying its own text and cause
        Fixture fixture = new Fixture();
        fixture.backend.preflightFailure = new IllegalStateException(BACKEND_TEXT, new SQLException(BACKEND_TEXT));

        // When
        MigrationRunException failure = fixture.runFailure();

        // Then
        assertEquals("PREFLIGHT", failure.failureCode());
        assertEquals("run", failure.operation());
        assertAllNotRun(failure.report(), List.of("job", "audit", "app"));
        assertSafe(failure, BACKEND_TEXT);
        assertEquals(List.of("validate", "open", "preflight", "close"), fixture.backend.events());
    }

    private static void guardRejectionFailsTheInstance() throws Exception {
        // Given: a backend whose guard rejects the first instance with a bare detail
        Fixture fixture = new Fixture();
        fixture.backend.guardFailure = new MigrationRunException(Fixture.GUARD_DETAIL, "PREFLIGHT", "run", null);

        // When
        MigrationRunException failure = fixture.runFailure();

        // Then: that instance failed with nothing applied, the others did not run, and no native run happened
        assertFailure(failure, "PREFLIGHT", "run", Fixture.GUARD_DETAIL);
        assertFailedThenNotRun(failure.report());
        assertSafe(failure, BACKEND_TEXT);
        assertEquals(List.of("validate", "open", "preflight", "beforeInstance:job", "close"), fixture.backend.events());
    }

    private static void guardLostLockIsCoordination() throws Exception {
        // Given: a backend whose guard reports that the coordination lock is gone
        Fixture fixture = new Fixture();
        fixture.backend.guardFailure =
                new MigrationRunException(Fixture.COORDINATION_DETAIL, "COORDINATION", "coordination", null);

        // When
        MigrationRunException failure = fixture.runFailure();

        // Then: a coordination failure, the instance failed with nothing applied, the rest did not run
        assertFailure(failure, "COORDINATION", "coordination", Fixture.COORDINATION_DETAIL);
        assertFailedThenNotRun(failure.report());
        assertSafe(failure, BACKEND_TEXT);
        assertEquals(1, fixture.backend.closes());
    }

    private static void guardContractBreakIsSafe() throws Exception {
        // Given: a backend whose guard throws an unexpected exception carrying its own text and cause
        Fixture fixture = new Fixture();
        fixture.backend.guardFailure = new IllegalStateException(BACKEND_TEXT, new SQLException(BACKEND_TEXT));

        // When
        MigrationRunException failure = fixture.runFailure();

        // Then
        assertEquals("PREFLIGHT", failure.failureCode());
        assertEquals("run", failure.operation());
        assertFailedThenNotRun(failure.report());
        assertSafe(failure, BACKEND_TEXT);
        assertEquals(1, fixture.backend.closes());
    }

    // =====================================================================================
    // Unsupported named destination and non-PostgreSQL legacy destination
    // =====================================================================================

    private static void unsupportedNamedDestinationFailsBeforeWrites() throws Exception {
        // Given: named instances and a destination no backend supports, once through a selector double
        // and once through the real PostgreSQL selector
        Fixture selectorDouble = new Fixture();
        RecordingDatabaseSupport.Selector unsupported = RecordingDatabaseSupport.unsupportedDestination();
        selectorDouble.selector = unsupported;
        Fixture real = new Fixture();
        real.selector = PostgresDatabaseSupport.selector(real.connector);

        for (Fixture fixture : List.of(selectorDouble, real)) {
            // When
            MigrationRunException failure = fixture.runFailure();
            MigrationRunException resolution = assertThrows(
                    MigrationRunException.class, () -> fixture.runner().instances());

            // Then: a configuration failure, every entry not run, no connection and no session
            assertEquals("CONFIGURATION", failure.failureCode());
            assertEquals("run", failure.operation());
            assertAllNotRun(failure.report(), List.of("job", "audit", "app"));
            assertSafe(failure, "jdbc:", ROOT_PASSWORD);
            assertEquals("CONFIGURATION", resolution.failureCode());
            assertEquals("instances", resolution.operation());
            assertNull(resolution.report());
            assertEquals(0, fixture.connector.attempts());
            assertTrue(fixture.backend.events().isEmpty());
            assertTrue(fixture.recording.opens().isEmpty());
            assertTrue(fixture.recording.validatedBatches().isEmpty());
        }
        assertFalse(unsupported.selectedUrls().isEmpty(), "the selector must have been asked");
        assertTrue(unsupported.selectedUrls().stream().allMatch(ALT_URL::equals));
    }

    private static void legacyNonPostgresDestinationKeepsTheNativePath() throws Exception {
        // Given: a legacy configuration (no named instances) for a destination only native Flyway could
        // judge, with a selector and connector that would record any new dispatch or probe
        Fixture fixture = new Fixture();
        fixture.named = false;

        // When: both legacy entry points run
        Throwable migrate = failureOf(fixture.runner().migrate(vertx));
        Throwable run = failureOf(fixture.runner().run(vertx));

        // Then: each fails on the unchanged native path, not as a named failure
        for (Throwable failure : List.of(migrate, run)) {
            assertInstanceOf(FlywayException.class, failure);
            assertFalse(failure instanceof MigrationRunException, "legacy exception behavior is unchanged");
        }

        // And: no backend was selected, no session opened, no probe or guard connection made
        assertTrue(fixture.backend.selectedUrls().isEmpty(), "legacy execution must not select a backend");
        assertTrue(fixture.backend.events().isEmpty());
        assertEquals(0, fixture.connector.attempts());
    }

    // =====================================================================================
    // The real PostgreSQL selector never degrades to a permissive no-op
    // =====================================================================================

    private static void postgresSelectorNeverFallsBackToANoOpGuard() throws Exception {
        // Given: named instances migrating a PostgreSQL destination through the real selector, with a
        // connector that counts and fails every attempt
        Fixture fixture = new Fixture().forPostgres();
        FlywayMigrationRunner runner = fixture.runner();
        runner.instances();
        assertEquals(0, fixture.connector.attempts(), "resolving the configuration must not connect");

        // When
        Throwable thrown = failureOf(runner.run(vertx));

        // Then: the backend tried to open its own guard connection and the run stopped as a coordination
        // failure with nothing attempted
        MigrationRunException failure = assertInstanceOf(MigrationRunException.class, thrown);
        assertTrue(
                fixture.connector.attempts() >= 1,
                "a named migrate must open the backend's guard connection, never skip the guard");
        assertEquals("COORDINATION", failure.failureCode());
        assertEquals("coordination", failure.operation());
        assertAllNotRun(failure.report(), List.of("job", "audit", "app"));
        assertSafe(failure, "jdbc:", ROOT_PASSWORD, "127.0.0.1");
    }

    // =====================================================================================
    // Source-level boundary: orchestration carries no PostgreSQL text
    // =====================================================================================

    private static void orchestrationSourcesCarryNoPostgresText() throws IOException {
        // Given: the main sources of the engine-neutral orchestration
        Path root = Path.of(System.getProperty("user.dir"), "src/main/java/dev/vertique/db/flyway");
        assertTrue(Files.isDirectory(root), "expected " + root + " to exist");
        Pattern catalogOrLock = Pattern.compile("\\bpg_|search_path|current_schema");
        Pattern backendName = Pattern.compile("postgresdatabasesupport|jdbc:postgresql");

        // When: they are scanned for catalog, session or lock text and for the PostgreSQL backend itself
        List<String> violations = new ArrayList<>();
        for (String file : List.of(
                "NamedMigrationExecution.java",
                "NamedInstanceResolver.java",
                "NamedPlan.java",
                "NamedMigrationFailure.java")) {
            String source = Files.readString(root.resolve(file)).toLowerCase(Locale.ROOT);
            if (catalogOrLock.matcher(source).find()) {
                violations.add(file + " mentions PostgreSQL catalog, session or lock text");
            }
            if (backendName.matcher(source).find()) {
                violations.add(file + " names the PostgreSQL backend or destination");
            }
        }
        // The runner may wire the PostgreSQL selector and build its legacy fallback URL, nothing more.
        String runner =
                Files.readString(root.resolve("FlywayMigrationRunner.java")).toLowerCase(Locale.ROOT);
        if (catalogOrLock.matcher(runner).find()) {
            violations.add("FlywayMigrationRunner.java mentions PostgreSQL catalog, session or lock text");
        }

        // Then
        assertTrue(violations.isEmpty(), String.valueOf(violations));
    }

    // =====================================================================================
    // Fixtures and assertions
    // =====================================================================================

    private record Open(String jdbcUrl, String user, String password, long lockTimeoutMs) {

        // The password is deliberately kept out of the rendering so a failing assertion cannot print it.
        @Override
        public String toString() {
            return "Open[jdbcUrl=" + jdbcUrl + ", user=" + user + ", lockTimeoutMs=" + lockTimeoutMs + "]";
        }
    }

    private static FlywayInstanceConfig override(String name, FlywayMode mode, List<String> schemas, String table) {
        return new FlywayInstanceConfig(
                name, mode, null, schemas, table, null, null, null, null, null, null, null, null);
    }

    private static List<String> names(List<MigrationInstanceView> views) {
        return views.stream().map(MigrationInstanceView::name).toList();
    }

    private static List<String> resultNames(List<MigrationInstanceResult> results) {
        return results.stream().map(r -> r.instance().name()).toList();
    }

    private static MigrationInstanceView view(List<MigrationInstanceView> views, String name) {
        return views.stream()
                .filter(v -> v.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no view named " + name + " in " + names(views)));
    }

    private static Throwable failureOf(Future<?> future) {
        ExecutionException ex = assertThrows(
                ExecutionException.class,
                () -> future.toCompletionStage().toCompletableFuture().get(30, TimeUnit.SECONDS));
        return ex.getCause();
    }

    /** The failure carries the stable code, the operation and the fixed backend detail behind the prefix. */
    private static void assertFailure(MigrationRunException failure, String code, String operation, String detail) {
        assertEquals(code, failure.failureCode());
        assertEquals(operation, failure.operation());
        assertEquals(
                "Named migration " + operation + " failed: " + detail,
                failure.getMessage(),
                "the prefix must appear once and the backend text must be the bare detail");
    }

    private static void assertAllNotRun(MigrationReport report, List<String> names) {
        assertNotNull(report, "a failed run reports every entry");
        assertEquals(names, resultNames(report.instances()));
        for (MigrationInstanceResult result : report.instances()) {
            assertEquals(
                    MigrationStatus.NOT_RUN, result.status(), result.instance().name());
            assertEquals(0, result.migrationsApplied(), result.instance().name());
            assertNull(result.currentVersion(), result.instance().name());
        }
    }

    /** The first enabled instance failed with nothing applied; every other entry did not run. */
    private static void assertFailedThenNotRun(MigrationReport report) {
        assertNotNull(report, "a failed run reports every entry");
        assertEquals(List.of("job", "audit", "app"), resultNames(report.instances()));
        assertEquals(
                List.of(MigrationStatus.FAILED, MigrationStatus.NOT_RUN, MigrationStatus.NOT_RUN),
                report.instances().stream().map(MigrationInstanceResult::status).toList());
        for (MigrationInstanceResult result : report.instances()) {
            assertEquals(0, result.migrationsApplied(), result.instance().name());
            assertNull(result.currentVersion(), result.instance().name());
        }
    }

    /** A named failure keeps no throwable, a closed-set code and none of the forbidden text anywhere. */
    private static void assertSafe(MigrationRunException failure, String... forbidden) {
        assertNull(failure.getCause(), "no third-party cause may be retained");
        assertEquals(0, failure.getSuppressed().length, "no suppressed throwable may be retained");
        assertTrue(FAILURE_CODES.contains(failure.failureCode()), "unknown failure code " + failure.failureCode());
        assertNotNull(failure.operation());
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        String rendered = trace + String.valueOf(failure.report());
        for (String text : forbidden) {
            assertFalse(rendered.contains(text), "'" + text + "' leaked into the failure: " + failure.getMessage());
        }
    }

    /** One configuration, its declarations and its doubles; the destination defaults to the alternative backend. */
    private static final class Fixture {

        static final String VALIDATION_DETAIL = "the alternative backend rejects the configuration of instance 'job'";
        static final String DIGIT_RULE_DETAIL =
                "the alternative backend rejects a digit in the history table of instance 'job'";
        static final String COORDINATION_DETAIL = "the alternative backend could not coordinate the run";
        static final String PREFLIGHT_DETAIL = "the alternative backend rejects the batch";
        static final String GUARD_DETAIL = "the alternative backend rejects instance 'job'";

        final AlternativeBackend backend = new AlternativeBackend();
        final RecordingDatabaseSupport recording = new RecordingDatabaseSupport();
        final RecordingDatabaseSupport.CountingConnector connector = new RecordingDatabaseSupport.CountingConnector();
        DatabaseSupportSelector selector = new AlternativeSelector(backend);
        String url = ALT_URL;
        boolean named = true;
        List<String> rootSchemas = List.of();
        List<FlywayInstanceConfig> overrides = List.of(override("audit", FlywayMode.DISABLED, null, null));
        Map<String, String> placement = Map.of();

        /** Targets a PostgreSQL destination through the real selector, keeping the rest of the setup. */
        Fixture forPostgres() {
            this.url = POSTGRES_URL;
            this.selector = PostgresDatabaseSupport.selector(connector);
            return this;
        }

        FlywayMigrationRunner runner() {
            FlywayConfig.FlywayConfigBuilder config = FlywayConfig.builder()
                    .mode(FlywayMode.MIGRATE)
                    .jdbcUrl(url)
                    .user(ROOT_USER)
                    .password(ROOT_PASSWORD)
                    .locations(List.of(ALPHA))
                    .schemas(rootSchemas)
                    .coordinationLockTimeoutMs(LOCK_TIMEOUT_MS);
            if (named) {
                config.instances(overrides);
            }
            Set<MigrationDefinition> definitions = new LinkedHashSet<>(List.of(
                    new MigrationDefinition("job", List.of(ALPHA), List.of()),
                    new MigrationDefinition("audit", List.of(BETA), List.of("job")),
                    new MigrationDefinition("app", List.of(GAMMA), List.of("audit"))));
            return new FlywayMigrationRunner(
                    config.build(), POOL, definitions, new DbSchemas(placement), selector, connector);
        }

        List<MigrationInstanceView> instances() {
            return runner().instances();
        }

        /** Runs the batch on a worker and returns the named failure it must end with. */
        MigrationRunException runFailure() throws Exception {
            Throwable failure = failureOf(runner().run(vertx));
            return assertInstanceOf(MigrationRunException.class, failure, String.valueOf(failure));
        }

        /** Selector of the alternative backend: it supports only {@code jdbc:altdb:} destinations. */
        static final class AlternativeSelector implements DatabaseSupportSelector {

            private final AlternativeBackend backend;

            AlternativeSelector(AlternativeBackend backend) {
                this.backend = backend;
            }

            @Override
            public Optional<DatabaseSupport> select(String jdbcUrl) {
                backend.selectedUrls.add(jdbcUrl);
                return jdbcUrl != null && jdbcUrl.startsWith("jdbc:altdb:") ? Optional.of(backend) : Optional.empty();
            }
        }
    }

    /**
     * Internal alternative backend. Its identifier rule (no digit in a history table) differs from
     * PostgreSQL's, it accepts every schema name, and it records each call in order. A failure set
     * on it is thrown by the matching operation after the call is recorded.
     */
    private static final class AlternativeBackend implements DatabaseSupport {

        private final List<String> events = new CopyOnWriteArrayList<>();
        private final List<String> selectedUrls = new CopyOnWriteArrayList<>();
        private final List<List<MigrationInstanceView>> validatedBatches = new CopyOnWriteArrayList<>();
        private final List<Open> opens = new CopyOnWriteArrayList<>();
        private final List<List<MigrationInstanceView>> preflightViews = new CopyOnWriteArrayList<>();
        private final List<String> rootFirstSchemas = new CopyOnWriteArrayList<>();
        private final List<MigrationInstanceView> beforeInstanceViews = new CopyOnWriteArrayList<>();
        private final AtomicInteger closes = new AtomicInteger();

        volatile RuntimeException validationFailure;
        volatile Exception openFailure;
        volatile RuntimeException preflightFailure;
        volatile RuntimeException guardFailure;
        volatile boolean freezeUnknownDefaults;
        volatile Set<String> historyOwners = Set.of();

        List<String> events() {
            return List.copyOf(events);
        }

        List<String> selectedUrls() {
            return List.copyOf(selectedUrls);
        }

        List<List<MigrationInstanceView>> validatedBatches() {
            return List.copyOf(validatedBatches);
        }

        List<Open> opens() {
            return List.copyOf(opens);
        }

        List<List<MigrationInstanceView>> preflightViews() {
            return List.copyOf(preflightViews);
        }

        List<String> rootFirstSchemas() {
            return List.copyOf(rootFirstSchemas);
        }

        List<MigrationInstanceView> beforeInstanceViews() {
            return List.copyOf(beforeInstanceViews);
        }

        int closes() {
            return closes.get();
        }

        @Override
        public void validateConfiguration(List<MigrationInstanceView> views) {
            events.add("validate");
            validatedBatches.add(List.copyOf(views));
            RuntimeException failure = validationFailure;
            if (failure != null) {
                throw failure;
            }
            for (MigrationInstanceView view : views) {
                if (view.table().chars().anyMatch(Character::isDigit)) {
                    throw new MigrationRunException(
                            Fixture.DIGIT_RULE_DETAIL.replace("'job'", "'" + view.name() + "'"),
                            "CONFIGURATION",
                            "configuration",
                            null);
                }
            }
        }

        @Override
        public Session open(String jdbcUrl, String user, String password, long lockTimeoutMs) throws SQLException {
            events.add("open");
            opens.add(new Open(jdbcUrl, user, password, lockTimeoutMs));
            Exception failure = openFailure;
            if (failure instanceof SQLException sql) {
                throw sql;
            }
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            return new AlternativeSession();
        }

        private final class AlternativeSession implements Session {

            @Override
            public Preflight preflight(List<NamedPlan.Instance> enabledInstances, String rootFirstSchema) {
                events.add("preflight");
                List<MigrationInstanceView> enabled =
                        enabledInstances.stream().map(NamedPlan.Instance::view).toList();
                preflightViews.add(enabled);
                if (rootFirstSchema != null) {
                    rootFirstSchemas.add(rootFirstSchema);
                }
                RuntimeException failure = preflightFailure;
                if (failure != null) {
                    throw failure;
                }
                List<MigrationInstanceView> frozen = enabled.stream()
                        .map(view -> freezeUnknownDefaults && view.schema() == null
                                ? view.withSchema("frozen_" + view.name())
                                : view)
                        .toList();
                return new Preflight(frozen, historyOwners);
            }

            @Override
            public void beforeInstance(MigrationInstanceView frozenView) {
                events.add("beforeInstance:" + frozenView.name());
                beforeInstanceViews.add(frozenView);
                RuntimeException failure = guardFailure;
                if (failure != null) {
                    throw failure;
                }
            }

            @Override
            public void close() {
                events.add("close");
                closes.incrementAndGet();
            }
        }
    }
}
