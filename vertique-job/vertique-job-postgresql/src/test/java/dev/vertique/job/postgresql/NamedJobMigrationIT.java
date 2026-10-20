// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.postgresql;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import dagger.BindsInstance;
import dagger.BindsOptionalOf;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dagger.multibindings.Multibinds;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.exception.ConfigurationException;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.db.DbModule;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.MigrationDefinition;
import dev.vertique.db.MigrationRunner;
import dev.vertique.db.flyway.DbFlywayModule;
import dev.vertique.db.flyway.FlywayConfig;
import dev.vertique.db.flyway.FlywayMigrationRunner;
import dev.vertique.db.flyway.FlywayMigrationStartupStep;
import dev.vertique.db.flyway.FlywayMode;
import dev.vertique.db.flyway.MigrationInstanceResult;
import dev.vertique.db.flyway.MigrationInstanceView;
import dev.vertique.db.flyway.MigrationReport;
import dev.vertique.db.flyway.MigrationStatus;
import dev.vertique.db.postgresql.DbPostgresqlModule;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.job.CronJobSchedule;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobExecutionStateTransitionEvent;
import dev.vertique.job.JobExecutionStateTransitionListener;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.LogEntry;
import dev.vertique.job.ProgressSnapshot;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import jakarta.inject.Singleton;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end proof that named migration instances and the neutral schema placement setting drive both
 * the migration-only Dagger component and the runtime-only Dagger component from one configuration.
 *
 * <p>A fresh database gets a decoy default schema whose tables carry the job table names but an
 * incompatible shape. Any migration or query that ignores the mapped schema therefore collides with,
 * or fails against, the decoy instead of silently succeeding.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class NamedJobMigrationIT {

    private static final String JOB_SCHEMA = "jobs";
    private static final String APP_SCHEMA = "app_data";
    private static final String DECOY_SCHEMA = "public";
    private static final String UNUSED_FLYWAY_SCHEMA = "flyway_only";

    private static final String JOB_HISTORY = "flyway_schema_history_job";
    private static final String APP_HISTORY = "flyway_schema_history_app";

    private static final List<String> JOB_TABLES =
            List.of("job_executions", "job_logs", "job_schedules", "job_server_heartbeats");

    private static final int MALFORMED_PLACEMENT_VALUE = 424242;

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("named_job");

    @Test
    @DisplayName("named job and app migrations land in their mapped schemas and the job repository uses the mapping")
    void migratesNamedJobAndAppAndUsesMappedRepository(Vertx vertx) throws Exception {
        // Given: a decoy default schema, a migration-only component and a runtime-only component sharing one
        // placement configuration
        createDecoyDefaultSchema();
        MigrationComponent migration = migrationComponent(vertx, migrationConfig());
        RuntimeComponent runtime = runtimeComponent(vertx, runtimeConfig(jobAndAppPlacement()));
        try {
            // Then: the two graphs have the intended shape before anything touches the database
            assertMigrationGraphHasNoPool(migration);
            assertRuntimeGraphHasNoFlyway(runtime);
            assertPlacementEqualsConfiguration(migration, runtime);
            assertConnectionFreeInstanceViews(migration.runner());
            assertFlywayListsCreateNoPlacement(vertx);
            assertMalformedPlacementFailsTheDbConfiguration(vertx);
            assertCompatibilityGraphHasNoPlacement();

            // When: the migration-only component runs for the first time
            MigrationReport first = await(migration.runner().run(vertx));

            // Then: both instances migrated once into their own schema and histories, the decoy is untouched
            assertEveryInstanceMigrated(first, 1);
            assertObjectsLiveOnlyInTheirMappedSchemas();
            assertEquals(List.of("1"), versionedHistory(JOB_SCHEMA, JOB_HISTORY));
            assertEquals(List.of("1"), versionedHistory(APP_SCHEMA, APP_HISTORY));
            assertEquals(List.of("1"), column("SELECT count(*) FROM " + APP_SCHEMA + ".app_items"));

            // When: the runtime-only component serves every repository operation
            // Then: each one succeeds against the mapped schema, which the decoy shape would not allow
            exerciseEveryRawRepositoryOperation(runtime);
            assertScopedRepositoryIdentity(runtime);
            assertDecoratorUsesMappedRawRepositoryAndNotifies(runtime);
            assertApplicationBoundPlacementIsUsed(runtime);
            assertDecoyDefaultSchemaUntouched();

            // When: migrations are rerun through the report API and through the startup step
            MigrationReport second = await(migration.runner().run(vertx));
            await(startupStep(migration).start());

            // Then: no script is applied again
            assertEveryInstanceMigrated(second, 0);
            assertEquals(List.of("1"), versionedHistory(JOB_SCHEMA, JOB_HISTORY));
            assertEquals(List.of("1"), versionedHistory(APP_SCHEMA, APP_HISTORY));
            assertDecoyDefaultSchemaUntouched();
        } finally {
            await(runtime.pool().close());
        }
    }

    // --- Components ---

    /** Migration-only graph: no Pool and no PostgreSQL runtime module. */
    @Singleton
    @Component(
            modules = {
                DbModule.class,
                DbFlywayModule.class,
                JobMigrationsModule.class,
                ConfigParsingModule.class,
                ApplicationMigrationModule.class,
                AbsentPoolProbeModule.class
            })
    interface MigrationComponent {

        FlywayMigrationRunner runner();

        DbSchemas dbSchemas();

        Optional<DbSchemas> optionalDbSchemas();

        Optional<Pool> optionalPool();

        Set<ApplicationStartupStep> startupSteps();

        @Component.Factory
        interface Factory {
            MigrationComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** Runtime-only graph: the PostgreSQL runtime and the job adapter, deliberately without the Flyway module. */
    @Singleton
    @Component(
            modules = {
                DbModule.class,
                DbPostgresqlModule.class,
                JobPostgresqlModule.class,
                ConfigParsingModule.class,
                RecordingListenerModule.class,
                AbsentFlywayProbeModule.class
            })
    interface RuntimeComponent {

        Pool pool();

        DbSchemas dbSchemas();

        Optional<DbSchemas> optionalDbSchemas();

        PgJobRepository pgJobRepository();

        @RawJobRepository
        JobRepository rawJobRepository();

        JobRepository jobRepository();

        RecordingListener recordingListener();

        Optional<FlywayConfig> optionalFlywayConfig();

        Optional<MigrationRunner> optionalMigrationRunner();

        Set<ApplicationStartupStep> startupSteps();

        @Component.Factory
        interface Factory {
            RuntimeComponent create(@BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** Minimal Pool-plus-mapper graph with no schema placement binding at all. */
    @Singleton
    @Component(modules = {JobPostgresqlModule.class, CompatibilityStubsModule.class})
    interface CompatibilityComponent {

        Optional<DbSchemas> optionalDbSchemas();

        JobRepository jobRepository();
    }

    /** Graph without DbModule whose application supplies the placement binding itself. */
    @Singleton
    @Component(modules = {JobPostgresqlModule.class, ApplicationPlacementModule.class})
    interface ApplicationPlacementComponent {

        Optional<DbSchemas> optionalDbSchemas();

        @RawJobRepository
        JobRepository rawJobRepository();

        @Component.Factory
        interface Factory {
            ApplicationPlacementComponent create(@BindsInstance Pool pool);
        }
    }

    @Module
    abstract static class ApplicationMigrationModule {

        @Provides
        @IntoSet
        static MigrationDefinition appMigration() {
            return new MigrationDefinition("app", List.of("classpath:db/it-named-app"), List.of());
        }
    }

    @Module
    abstract static class AbsentPoolProbeModule {

        @BindsOptionalOf
        abstract Pool pool();
    }

    @Module
    abstract static class AbsentFlywayProbeModule {

        @BindsOptionalOf
        abstract FlywayConfig flywayConfig();

        @BindsOptionalOf
        abstract MigrationRunner migrationRunner();

        @Multibinds
        abstract Set<ApplicationStartupStep> startupSteps();
    }

    @Module
    abstract static class RecordingListenerModule {

        @Provides
        @Singleton
        static RecordingListener recordingListener() {
            return new RecordingListener();
        }

        @Provides
        @IntoSet
        static JobExecutionStateTransitionListener transitionListener(RecordingListener listener) {
            return listener;
        }
    }

    @Module
    abstract static class CompatibilityStubsModule {

        @Provides
        @Singleton
        static Pool pool() {
            return mock(Pool.class);
        }

        @Provides
        @Singleton
        static PgDbExceptionMapper pgDbExceptionMapper() {
            return mock(PgDbExceptionMapper.class);
        }
    }

    @Module
    abstract static class ApplicationPlacementModule {

        @Provides
        @Singleton
        static DbSchemas dbSchemas() {
            return new DbSchemas(Map.of("job", JOB_SCHEMA));
        }

        @Provides
        @Singleton
        static PgDbExceptionMapper pgDbExceptionMapper() {
            return new PgDbExceptionMapper();
        }
    }

    static final class RecordingListener implements JobExecutionStateTransitionListener {

        final List<JobExecutionStateTransitionEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void onStateTransition(JobExecutionStateTransitionEvent event) {
            events.add(event);
        }
    }

    // --- Graph assertions ---

    private static void assertMigrationGraphHasNoPool(MigrationComponent migration) {
        Set<Class<?>> modules = declaredModules(MigrationComponent.class);
        assertTrue(modules.contains(DbFlywayModule.class), "migration graph must declare the Flyway module");
        assertTrue(modules.contains(JobMigrationsModule.class), "migration graph must declare the job declaration");
        assertFalse(modules.contains(DbPostgresqlModule.class), "migration graph must not declare the runtime module");
        assertTrue(migration.optionalPool().isEmpty(), "migration graph must not bind a Pool");
        assertTrue(
                migration.startupSteps().stream().anyMatch(FlywayMigrationStartupStep.class::isInstance),
                "migration graph must contribute the Flyway startup step");
    }

    private static void assertRuntimeGraphHasNoFlyway(RuntimeComponent runtime) {
        Set<Class<?>> modules = declaredModules(RuntimeComponent.class);
        assertTrue(modules.contains(JobPostgresqlModule.class), "runtime graph must declare the job adapter module");
        assertTrue(
                modules.stream().noneMatch(module -> module.getName().startsWith("dev.vertique.db.flyway.")),
                "runtime graph must declare no module from the Flyway package, directly or through includes: "
                        + modules);
        assertTrue(runtime.optionalFlywayConfig().isEmpty(), "runtime graph must have no FlywayConfig binding");
        assertTrue(runtime.optionalMigrationRunner().isEmpty(), "runtime graph must have no MigrationRunner binding");
        assertTrue(
                runtime.startupSteps().stream().noneMatch(FlywayMigrationStartupStep.class::isInstance),
                "runtime graph must contribute no Flyway startup step");
    }

    private static void assertPlacementEqualsConfiguration(MigrationComponent migration, RuntimeComponent runtime) {
        Map<String, String> configured = Map.of("job", JOB_SCHEMA, "app", APP_SCHEMA);

        assertEquals(configured, runtime.dbSchemas().mappings());
        assertEquals(configured, migration.dbSchemas().mappings());
        assertEquals(Optional.of(JOB_SCHEMA), runtime.dbSchemas().schema("job"));
        assertEquals(Optional.of(APP_SCHEMA), runtime.dbSchemas().schema("app"));
        assertTrue(runtime.dbSchemas().schema("workflow").isEmpty());
        assertEquals(configured, runtime.optionalDbSchemas().orElseThrow().mappings());
        assertEquals(configured, migration.optionalDbSchemas().orElseThrow().mappings());
    }

    private static void assertConnectionFreeInstanceViews(FlywayMigrationRunner runner) {
        List<MigrationInstanceView> views = runner.instances();

        assertEquals(
                List.of("app", "job"),
                views.stream().map(MigrationInstanceView::name).toList());
        for (MigrationInstanceView view : views) {
            String schema = view.name().equals("job") ? JOB_SCHEMA : APP_SCHEMA;
            assertEquals(schema, view.schema(), view.name() + " default schema");
            assertEquals(List.of(schema), view.schemas(), view.name() + " managed schemas");
            assertEquals("flyway_schema_history_" + view.name(), view.table());
            assertEquals(FlywayMode.MIGRATE, view.mode());
        }
        assertEquals(List.of("classpath:db/migration/job"), views.get(1).locations());
        assertEquals(List.of("classpath:db/it-named-app"), views.get(0).locations());
    }

    private static void assertFlywayListsCreateNoPlacement(Vertx vertx) throws Exception {
        // Given: a placement entry for the application only, while Flyway lists name another schema for the job
        JsonObject config = runtimeConfig(new JsonObject().put("app", APP_SCHEMA))
                .put(
                        "flyway",
                        new JsonObject()
                                .put("schemas", new JsonArray().add(UNUSED_FLYWAY_SCHEMA))
                                .put("named", true)
                                .put(
                                        "instances",
                                        new JsonObject()
                                                .put(
                                                        "job",
                                                        new JsonObject()
                                                                .put(
                                                                        "schemas",
                                                                        new JsonArray().add(UNUSED_FLYWAY_SCHEMA)))));

        // When: the runtime graph is built and its placement is read
        RuntimeComponent component = runtimeComponent(vertx, config);
        try {
            DbSchemas schemas = component.dbSchemas();

            // Then: only the configured key exists; nothing is derived from Flyway lists
            assertEquals(Map.of("app", APP_SCHEMA), schemas.mappings());
            assertTrue(schemas.schema("job").isEmpty(), "a job entry exists only when db.schemas.job is set");
            assertTrue(component.optionalDbSchemas().isPresent());
            assertNotNull(component.pgJobRepository(), "an unmapped job entry still builds the adapter");
        } finally {
            await(component.pool().close());
        }
    }

    private static void assertMalformedPlacementFailsTheDbConfiguration(Vertx vertx) {
        // Given: a non-string value for the job entry
        JsonObject malformed = new JsonObject().put("job", MALFORMED_PLACEMENT_VALUE);

        // When: the runtime graph is created
        RuntimeComponent component = assertDoesNotThrow(() -> runtimeComponent(vertx, runtimeConfig(malformed)));

        // Then: creation succeeded; every binding built from the db section fails naming the key only
        assertNamesKeyWithoutValue(assertThrows(ConfigurationException.class, component::dbSchemas));
        assertNamesKeyWithoutValue(assertThrows(ConfigurationException.class, component::pool));
        assertNamesKeyWithoutValue(assertThrows(ConfigurationException.class, component::pgJobRepository));
    }

    private static void assertNamesKeyWithoutValue(ConfigurationException failure) {
        assertNotNull(failure.getMessage());
        assertTrue(failure.getMessage().contains("db.schemas.job"), failure.getMessage());
        assertFalse(failure.getMessage().contains(String.valueOf(MALFORMED_PLACEMENT_VALUE)), failure.getMessage());
    }

    private static void assertCompatibilityGraphHasNoPlacement() {
        CompatibilityComponent compatibility = DaggerNamedJobMigrationIT_CompatibilityComponent.create();

        assertTrue(
                compatibility.optionalDbSchemas().isEmpty(),
                "a graph without DbModule or an application binding has no placement");
        assertInstanceOf(NotifyingJobRepository.class, compatibility.jobRepository());
    }

    private static Set<Class<?>> declaredModules(Class<?> component) {
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>(
                List.of(component.getAnnotation(Component.class).modules()));
        while (!pending.isEmpty()) {
            Class<?> module = pending.pop();
            if (seen.add(module)) {
                Module annotation = module.getAnnotation(Module.class);
                if (annotation != null) {
                    pending.addAll(List.of(annotation.includes()));
                }
            }
        }
        return seen;
    }

    // --- Migration assertions ---

    private static void assertEveryInstanceMigrated(MigrationReport report, int expectedApplied) {
        List<MigrationInstanceResult> results = report.instances();

        assertEquals(
                List.of("app", "job"),
                results.stream().map(result -> result.instance().name()).toList());
        for (MigrationInstanceResult result : results) {
            String name = result.instance().name();
            assertEquals(MigrationStatus.MIGRATED, result.status(), name);
            assertEquals(expectedApplied, result.migrationsApplied(), name + " applied scripts");
            assertEquals("1", result.currentVersion(), name + " current version");
        }
    }

    private static void assertObjectsLiveOnlyInTheirMappedSchemas() throws SQLException {
        Set<String> tables = Set.copyOf(column("SELECT table_schema || '.' || table_name FROM information_schema.tables"
                + " WHERE table_schema IN ('" + JOB_SCHEMA + "', '" + APP_SCHEMA + "', '" + DECOY_SCHEMA + "')"));
        Set<String> indexes = Set.copyOf(column("SELECT schemaname || '.' || indexname FROM pg_indexes"
                + " WHERE schemaname IN ('" + JOB_SCHEMA + "', '" + APP_SCHEMA + "', '" + DECOY_SCHEMA + "')"));

        for (String table : JOB_TABLES) {
            assertTrue(tables.contains(JOB_SCHEMA + "." + table), "job table in the job schema: " + table);
            assertFalse(tables.contains(APP_SCHEMA + "." + table), "job table must not appear in app schema: " + table);
        }
        assertTrue(indexes.contains(JOB_SCHEMA + ".idx_job_executions_claim"));
        assertTrue(tables.contains(JOB_SCHEMA + "." + JOB_HISTORY));
        assertFalse(tables.contains(JOB_SCHEMA + "." + APP_HISTORY));

        assertTrue(tables.contains(APP_SCHEMA + ".app_items"));
        assertTrue(indexes.contains(APP_SCHEMA + ".idx_app_items_label"));
        assertTrue(tables.contains(APP_SCHEMA + "." + APP_HISTORY));
        assertFalse(tables.contains(APP_SCHEMA + "." + JOB_HISTORY));
        assertFalse(tables.contains(JOB_SCHEMA + ".app_items"), "app table must not appear in job schema");

        assertFalse(tables.contains(DECOY_SCHEMA + ".app_items"), "app table must not appear in the decoy schema");
        assertTrue(
                tables.stream().noneMatch(table -> table.startsWith(DECOY_SCHEMA + ".flyway_schema_history")),
                "no migration history may appear in the decoy schema");
        assertTrue(
                indexes.stream().noneMatch(index -> index.startsWith(DECOY_SCHEMA + ".idx_")),
                "no migrated index may appear in the decoy schema");
    }

    private static List<String> versionedHistory(String schema, String table) throws SQLException {
        return column("SELECT version FROM " + schema + "." + table
                + " WHERE version IS NOT NULL AND success ORDER BY installed_rank");
    }

    // --- Repository assertions ---

    private static void exerciseEveryRawRepositoryOperation(RuntimeComponent runtime) throws Exception {
        PgJobRepository repository = runtime.pgJobRepository();
        String queue = "mapped-" + UUID.randomUUID();
        JobExecution first = delayed(queue);
        JobExecution second = delayed(queue);
        JobExecution third = delayed(queue);
        JobExecution fourth = delayed(queue);

        // save, save in the caller's transaction, findById
        assertEquals(first.id(), await(repository.save(first)));
        assertEquals(
                second.id(), await(runtime.pool().withTransaction(connection -> repository.save(second, connection))));
        await(repository.save(third));
        await(repository.save(fourth));
        JobExecution found = await(repository.findById(first.id())).orElseThrow();
        assertEquals(queue, found.queue());
        assertEquals(JobState.ENQUEUED, found.state());

        // claimNextJob, findByLockedBy
        List<JobExecution> claimed = await(repository.claimNextJob(queue, 4));
        assertEquals(4, claimed.size());
        assertTrue(claimed.stream().allMatch(execution -> execution.state() == JobState.PROCESSING));
        String nodeId = claimed.get(0).lockedBy();
        assertNotNull(nodeId);
        assertEquals(
                Set.of(first.id(), second.id(), third.id(), fourth.id()),
                Set.copyOf(await(repository.findByLockedBy(nodeId)).stream()
                        .map(JobExecution::id)
                        .toList()));

        // heartbeat, findStale
        await(repository.heartbeat(first.id(), new ProgressSnapshot(100, 42, 0, "in progress")));
        JobExecution beating = await(repository.findById(first.id())).orElseThrow();
        assertEquals(42L, beating.progress().succeeded());
        assertEquals("in progress", beating.progress().status());
        execute("UPDATE " + JOB_SCHEMA + ".job_executions SET updated_at = NOW() - INTERVAL '2 minutes'"
                + " WHERE id = '" + first.id() + "'");
        List<UUID> stale = await(repository.findStale(Duration.ofMinutes(1))).stream()
                .map(JobExecution::id)
                .toList();
        assertTrue(stale.contains(first.id()));
        assertFalse(stale.contains(second.id()));

        // updateState, including the optimistic state guard, then scheduleRetry from the recorded state
        await(repository.updateState(second.id(), JobState.PROCESSING, JobState.ABANDONED));
        assertEquals(
                JobState.ABANDONED,
                await(repository.findById(second.id())).orElseThrow().state());
        assertInstanceOf(
                IllegalStateException.class,
                failureOf(repository.updateState(second.id(), JobState.PROCESSING, JobState.SUCCEEDED)));
        await(repository.scheduleRetry(second.id(), Instant.now().plusSeconds(30), 1));
        JobExecution rescheduled = await(repository.findById(second.id())).orElseThrow();
        assertEquals(JobState.ENQUEUED, rescheduled.state());
        assertEquals(1, rescheduled.attemptNumber());
        assertNull(rescheduled.lockedBy());

        // completeExecution is idempotent once terminal; saveLogs lands in the job log table
        ProgressSnapshot done = new ProgressSnapshot(10, 10, 0, "done");
        JobExecution completed = await(repository.completeExecution(third.id(), JobState.SUCCEEDED, null, null, done))
                .orElseThrow();
        assertEquals(JobState.SUCCEEDED, completed.state());
        assertNotNull(completed.completedAt());
        assertTrue(await(repository.completeExecution(third.id(), JobState.SUCCEEDED, null, null, done))
                .isEmpty());
        Instant now = Instant.now();
        await(repository.saveLogs(
                third.id(),
                List.of(
                        new LogEntry("INFO", "started", now),
                        new LogEntry("WARN", "odd", now),
                        new LogEntry("ERROR", "failed", now))));
        assertEquals(
                List.of("3"),
                column("SELECT count(*) FROM " + JOB_SCHEMA + ".job_logs WHERE execution_id::text = ?", third.id()));

        // failAndScheduleRetry and abandonAndScheduleRetry re-enqueue atomically
        JobExecution failedSnapshot = await(repository.failAndScheduleRetry(
                        fourth.id(),
                        "boom",
                        "java.lang.RuntimeException",
                        ProgressSnapshot.EMPTY,
                        Instant.now().plusSeconds(30),
                        1))
                .orElseThrow();
        assertEquals(JobState.FAILED, failedSnapshot.state());
        assertEquals(
                JobState.ENQUEUED,
                await(repository.findById(fourth.id())).orElseThrow().state());
        JobExecution abandonedSnapshot = await(repository.abandonAndScheduleRetry(
                        first.id(),
                        "lost",
                        "dev.example.Lost",
                        ProgressSnapshot.EMPTY,
                        Instant.now().plusSeconds(30),
                        1))
                .orElseThrow();
        assertEquals(JobState.ABANDONED, abandonedSnapshot.state());
        assertEquals(
                JobState.ENQUEUED,
                await(repository.findById(first.id())).orElseThrow().state());

        // tryInsert elects exactly one winner per fire time
        String cronJobId = "cron-" + UUID.randomUUID();
        Instant fireTime = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        JobExecution cronWinner = cron(cronJobId, fireTime);
        assertEquals(Optional.of(cronWinner.id()), await(repository.tryInsert(cronWinner)));
        assertTrue(await(repository.tryInsert(cron(cronJobId, fireTime))).isEmpty());

        // saveSchedule, findSchedule, updateScheduleFireTimes
        await(repository.saveSchedule(schedule(cronJobId)));
        CronJobSchedule savedSchedule =
                await(repository.findSchedule(cronJobId)).orElseThrow();
        assertEquals("0 0 8 * * *", savedSchedule.cronExpression());
        assertNull(savedSchedule.lastFiredAt());
        Instant lastFired = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant nextFire = lastFired.plusSeconds(3600);
        await(repository.updateScheduleFireTimes(cronJobId, lastFired, nextFire));
        CronJobSchedule firedSchedule =
                await(repository.findSchedule(cronJobId)).orElseThrow();
        assertEquals(lastFired, firedSchedule.lastFiredAt());
        assertEquals(nextFire, firedSchedule.nextFireAt());

        // serverHeartbeat, findDeadServers, removeServer
        String deadServer = "dead-" + UUID.randomUUID();
        String liveServer = "live-" + UUID.randomUUID();
        await(repository.serverHeartbeat(deadServer));
        await(repository.serverHeartbeat(liveServer));
        execute("UPDATE " + JOB_SCHEMA + ".job_server_heartbeats SET last_heartbeat = NOW() - INTERVAL '2 minutes'"
                + " WHERE server_id = '" + deadServer + "'");
        List<String> dead = await(repository.findDeadServers(Duration.ofMinutes(1)));
        assertTrue(dead.contains(deadServer));
        assertFalse(dead.contains(liveServer));
        await(repository.removeServer(deadServer));
        assertEquals(
                List.of("0"),
                column(
                        "SELECT count(*) FROM " + JOB_SCHEMA + ".job_server_heartbeats WHERE server_id = ?",
                        deadServer));
        assertEquals(
                List.of("1"),
                column(
                        "SELECT count(*) FROM " + JOB_SCHEMA + ".job_server_heartbeats WHERE server_id = ?",
                        liveServer));
    }

    private static void assertScopedRepositoryIdentity(RuntimeComponent runtime) {
        PgJobRepository concrete = runtime.pgJobRepository();

        assertSame(concrete, runtime.pgJobRepository(), "repeated concrete lookups share one scoped instance");
        assertSame(concrete, runtime.rawJobRepository(), "the raw qualifier aliases the scoped concrete repository");
        assertSame(runtime.rawJobRepository(), runtime.rawJobRepository());
        assertSame(runtime.jobRepository(), runtime.jobRepository(), "the unqualified binding is one singleton");
        assertInstanceOf(NotifyingJobRepository.class, runtime.jobRepository());
        assertNotSame(
                runtime.rawJobRepository(), runtime.jobRepository(), "the decorator is separate from the raw one");
    }

    private static void assertDecoratorUsesMappedRawRepositoryAndNotifies(RuntimeComponent runtime) throws Exception {
        // Given: one execution claimed through the raw repository and three more claimed through the decorator
        JobRepository raw = runtime.rawJobRepository();
        JobRepository decorated = runtime.jobRepository();
        RecordingListener listener = runtime.recordingListener();
        String rawQueue = "raw-claim-" + UUID.randomUUID();
        String decoratedQueue = "decorated-claim-" + UUID.randomUUID();
        JobExecution rawClaimed = delayed(rawQueue);
        JobExecution toComplete = delayed(decoratedQueue);
        JobExecution toFail = delayed(decoratedQueue);
        JobExecution toAbandon = delayed(decoratedQueue);
        for (JobExecution execution : List.of(rawClaimed, toComplete, toFail, toAbandon)) {
            await(decorated.save(execution));
        }
        String rawNode = await(raw.claimNextJob(rawQueue, 1)).get(0).lockedBy();
        List<JobExecution> claimed = await(decorated.claimNextJob(decoratedQueue, 3));

        // Then: the decorator reached the same raw instance, so both claims carry one node identity
        assertEquals(3, claimed.size());
        assertTrue(
                claimed.stream().allMatch(execution -> rawNode.equals(execution.lockedBy())),
                "claims through the decorator must be owned by the raw repository's node identity");
        assertEquals(
                Set.of(toComplete.id(), toFail.id(), toAbandon.id()),
                Set.copyOf(claimed.stream().map(JobExecution::id).toList()));

        // When: each lifecycle transition is performed through the decorator
        int before = listener.events.size();
        Instant retryAt = Instant.now().plusSeconds(30);
        await(decorated.completeExecution(toComplete.id(), JobState.SUCCEEDED, null, null, ProgressSnapshot.EMPTY));
        await(decorated.failAndScheduleRetry(
                toFail.id(), "boom", "java.lang.RuntimeException", ProgressSnapshot.EMPTY, retryAt, 1));
        await(decorated.abandonAndScheduleRetry(
                toAbandon.id(), "lost", "dev.example.Lost", ProgressSnapshot.EMPTY, retryAt, 1));

        // Then: transitions are persisted in the mapped schema and notifications are retained
        List<JobExecutionStateTransitionEvent> events =
                new ArrayList<>(listener.events.subList(before, listener.events.size()));
        assertEquals(
                List.of(JobState.SUCCEEDED, JobState.FAILED, JobState.ABANDONED),
                events.stream().map(JobExecutionStateTransitionEvent::newState).toList());
        assertEquals(
                List.of(toComplete.id(), toFail.id(), toAbandon.id()),
                events.stream()
                        .map(JobExecutionStateTransitionEvent::executionId)
                        .toList());
        assertEquals(
                List.of("SUCCEEDED"),
                column("SELECT state FROM " + JOB_SCHEMA + ".job_executions WHERE id::text = ?", toComplete.id()));
        assertEquals(
                List.of("ENQUEUED"),
                column("SELECT state FROM " + JOB_SCHEMA + ".job_executions WHERE id::text = ?", toFail.id()));
        assertEquals(
                List.of("ENQUEUED"),
                column("SELECT state FROM " + JOB_SCHEMA + ".job_executions WHERE id::text = ?", toAbandon.id()));
    }

    private static void assertApplicationBoundPlacementIsUsed(RuntimeComponent runtime) throws Exception {
        // Given: a graph without DbModule whose application binds the placement and reuses the real pool
        ApplicationPlacementComponent component = DaggerNamedJobMigrationIT_ApplicationPlacementComponent.factory()
                .create(runtime.pool());
        String serverId = "application-bound-" + UUID.randomUUID();

        // When: an adapter operation runs
        await(component.rawJobRepository().serverHeartbeat(serverId));

        // Then: the application's binding was honored and the row sits in the mapped schema
        assertEquals(
                Map.of("job", JOB_SCHEMA),
                component.optionalDbSchemas().orElseThrow().mappings());
        assertEquals(
                List.of("1"),
                column("SELECT count(*) FROM " + JOB_SCHEMA + ".job_server_heartbeats WHERE server_id = ?", serverId));
    }

    // --- Decoy default schema ---

    private static void createDecoyDefaultSchema() throws SQLException {
        for (String table : JOB_TABLES) {
            execute("CREATE TABLE " + DECOY_SCHEMA + "." + table + " (id UUID PRIMARY KEY)");
        }
        execute("INSERT INTO " + DECOY_SCHEMA + ".job_executions (id) VALUES (gen_random_uuid())");
    }

    private static void assertDecoyDefaultSchemaUntouched() throws SQLException {
        assertEquals(
                Set.copyOf(JOB_TABLES),
                Set.copyOf(column("SELECT table_name FROM information_schema.tables WHERE table_schema = '"
                        + DECOY_SCHEMA + "'")),
                "the decoy schema keeps exactly its own tables");
        for (String table : JOB_TABLES) {
            assertEquals(
                    List.of("id"),
                    column("SELECT column_name FROM information_schema.columns WHERE table_schema = '" + DECOY_SCHEMA
                            + "' AND table_name = '" + table + "'"),
                    "decoy table keeps its original shape: " + table);
            assertEquals(
                    List.of(table.equals("job_executions") ? "1" : "0"),
                    column("SELECT count(*) FROM " + DECOY_SCHEMA + "." + table),
                    "decoy table keeps its original rows: " + table);
        }
    }

    // --- Fixtures ---

    private static JsonObject jobAndAppPlacement() {
        return new JsonObject().put("job", JOB_SCHEMA).put("app", APP_SCHEMA);
    }

    private static JsonObject dbSection(JsonObject placement) {
        DbPoolConfig pool = db.toPoolConfig();
        return new JsonObject()
                .put("host", pool.host())
                .put("port", pool.port())
                .put("database", pool.database())
                .put("user", pool.user())
                .put("password", pool.password())
                .put("schemas", placement);
    }

    private static JsonObject runtimeConfig(JsonObject placement) {
        return new JsonObject().put("db", dbSection(placement));
    }

    private static JsonObject migrationConfig() {
        return runtimeConfig(jobAndAppPlacement())
                .put(
                        "flyway",
                        new JsonObject()
                                .put("mode", "MIGRATE")
                                .put("jdbcUrl", db.jdbcUrl())
                                .put("baselineOnMigrate", false)
                                .put("named", true));
    }

    private static MigrationComponent migrationComponent(Vertx vertx, JsonObject config) {
        return DaggerNamedJobMigrationIT_MigrationComponent.factory().create(vertx, config);
    }

    private static RuntimeComponent runtimeComponent(Vertx vertx, JsonObject config) {
        return DaggerNamedJobMigrationIT_RuntimeComponent.factory().create(vertx, config);
    }

    private static FlywayMigrationStartupStep startupStep(MigrationComponent migration) {
        return migration.startupSteps().stream()
                .filter(FlywayMigrationStartupStep.class::isInstance)
                .map(FlywayMigrationStartupStep.class::cast)
                .findFirst()
                .orElseThrow();
    }

    private static JobExecution delayed(String queue) {
        return new JobExecution(
                UUID.randomUUID(),
                "logical-job-" + UUID.randomUUID(),
                JobType.DELAYED,
                "job.delayed.test",
                queue,
                JobState.ENQUEUED,
                0,
                5,
                null,
                0,
                null,
                Instant.now().minusSeconds(5),
                null,
                null,
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                null,
                null,
                null);
    }

    private static JobExecution cron(String jobId, Instant scheduledAt) {
        return new JobExecution(
                UUID.randomUUID(),
                jobId,
                JobType.CRON,
                "cron.handler.test",
                "cron",
                JobState.PROCESSING,
                0,
                3,
                null,
                0,
                "node-" + UUID.randomUUID(),
                scheduledAt,
                null,
                null,
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                null,
                null,
                null);
    }

    private static CronJobSchedule schedule(String jobId) {
        return new CronJobSchedule(
                jobId,
                "0 0 8 * * *",
                "test/my-svc/doWork",
                "eventbus:test/my-svc/doWork",
                "EVERY_INSTANCE",
                "UTC",
                true,
                "SKIP",
                3,
                true,
                null,
                null);
    }

    // --- Native database access ---

    private static Connection jdbc() throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Runs a query and returns its first column rendered as text, one entry per row. */
    private static List<String> column(String sql, Object... parameters) throws SQLException {
        List<String> values = new ArrayList<>();
        try (Connection connection = jdbc();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, String.valueOf(parameters[i]));
            }
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    values.add(rows.getString(1));
                }
            }
        }
        return values;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(Future<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(future));
        return failure.getCause();
    }
}
