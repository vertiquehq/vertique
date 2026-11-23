// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.Binds;
import dagger.BindsInstance;
import dagger.BindsOptionalOf;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.config.parser.ConfigParsingModule;
import dev.vertique.core.VertxConfig;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbModule;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.DbSchemas;
import dev.vertique.db.DbSchemasModule;
import dev.vertique.db.MigrationDefinition;
import dev.vertique.db.MigrationRunner;
import dev.vertique.db.flyway.DbFlywayModule;
import dev.vertique.db.flyway.FlywayConfig;
import dev.vertique.db.flyway.FlywayMigrationRunner;
import dev.vertique.db.flyway.FlywayMode;
import dev.vertique.db.flyway.MigrationInstanceResult;
import dev.vertique.db.flyway.MigrationInstanceView;
import dev.vertique.db.flyway.MigrationReport;
import dev.vertique.db.flyway.MigrationStatus;
import dev.vertique.db.postgresql.DbPostgresqlModule;
import dev.vertique.db.postgresql.PgTableNames;
import dev.vertique.db.query.PageCursor;
import dev.vertique.db.query.PagedResult;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.inboxoutbox.postgresql.InboxOutboxMigrationsModule;
import dev.vertique.inboxoutbox.postgresql.TransactionalMessagingPostgresqlModule;
import dev.vertique.security.PrincipalRef;
import dev.vertique.security.PrincipalType;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.actor.WorkflowAttribution;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.executor.CommandErrors;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.DedupClaim;
import dev.vertique.workflow.engine.spi.DispositionResult;
import dev.vertique.workflow.engine.spi.InstanceFailureListener;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.ParkedSignal;
import dev.vertique.workflow.engine.spi.QueueCounts;
import dev.vertique.workflow.engine.spi.RecordedTimer;
import dev.vertique.workflow.engine.spi.SegmentCommand;
import dev.vertique.workflow.engine.spi.SettledCommand;
import dev.vertique.workflow.engine.spi.SignalWaitRecord;
import dev.vertique.workflow.engine.spi.StartDedupResult;
import dev.vertique.workflow.engine.spi.TaskReservations;
import dev.vertique.workflow.engine.spi.TimerCommandSpec;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowSignalWaitRepository;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.postgresql.engine.WorkflowMigrationsModule;
import dev.vertique.workflow.postgresql.engine.WorkflowPostgresqlModule;
import dev.vertique.workflow.postgresql.executor.PgWorkflowCommandRepository;
import dev.vertique.workflow.postgresql.query.PgBranchTokenQueryService;
import dev.vertique.workflow.postgresql.query.PgWorkflowInstanceQueryService;
import dev.vertique.workflow.postgresql.registry.PgWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.postgresql.repository.PgBranchTokenRepository;
import dev.vertique.workflow.postgresql.repository.PgJoinStateRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowSignalWaitRepository;
import dev.vertique.workflow.postgresql.retention.PgWorkflowRetentionRepository;
import dev.vertique.workflow.postgresql.retention.PgWorkflowRetentionService;
import dev.vertique.workflow.postgresql.tasks.PgTaskReservations;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.query.WorkflowInstanceQuery;
import dev.vertique.workflow.query.WorkflowInstanceQueryService;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.registry.store.ActivationClaim;
import dev.vertique.workflow.registry.store.DefinitionFilter;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.DefinitionKind;
import dev.vertique.workflow.registry.store.DefinitionProvenance;
import dev.vertique.workflow.registry.store.DefinitionStatus;
import dev.vertique.workflow.registry.store.ReconcileOutcome;
import dev.vertique.workflow.registry.store.WorkflowDefinitionDocument;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRecord;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.retention.WorkflowRetentionService;
import dev.vertique.workflow.retention.WorkflowRetentionService.PurgeResult;
import dev.vertique.workflow.retention.WorkflowRetentionService.RetentionResult;
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.BranchTokenFilter;
import dev.vertique.workflow.state.JoinPolicyType;
import dev.vertique.workflow.state.JoinState;
import dev.vertique.workflow.state.JoinStateStatus;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowEntryType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.subject.WorkflowSubjectRef;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskFilter;
import dev.vertique.workflow.tasks.TaskReassignmentResult;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.tasks.TaskTransition;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import dev.vertique.workflow.timer.TimerStatusTransition;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Singleton;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end proof that the neutral schema placement setting places the workflow migration and every
 * statement of the PostgreSQL workflow stores in the mapped schema, including the query and retention
 * paths, and that workflow writes share one caller transaction with application and
 * outbox work.
 *
 * <p>Each scenario starts from a decoy default schema whose tables carry the real workflow table names
 * but an incompatible shape, one sentinel row each and no migration history, while every mapped schema
 * is missing. A statement that ignores the mapping therefore reaches the decoy and fails on its columns
 * instead of silently succeeding, and the decoy's catalog entries and rows are compared with a snapshot
 * taken before anything ran.
 *
 * <p>Generated Dagger components share one configuration: a migration-only graph with the shipped
 * migration declarations and the public named runner, and a runtime-only graph with the shipped workflow
 * module and the PostgreSQL inbox/outbox module, without any
 * Flyway module. The placement binding reaches the stores only through the shipped modules.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class PgWorkflowSchemaIT {

    private static final String WORKFLOW_INSTANCE = "workflow";
    private static final String WORKFLOW_LOCATION = "classpath:db/migration/workflow";
    private static final String WORKFLOW_HISTORY = "flyway_schema_history_workflow";
    private static final String MESSAGING_INSTANCE = "inbox-outbox";
    private static final String MESSAGING_HISTORY = "flyway_schema_history_inbox_outbox";
    private static final String APP_INSTANCE = "app";
    private static final String APP_LOCATION = "classpath:db/it-schema-app";
    private static final String APP_HISTORY = "flyway_schema_history_app";

    private static final String MAPPED_SCHEMA = "orchestration";
    private static final String MESSAGING_SCHEMA = "messaging";
    private static final String APP_SCHEMA = "app_ledger";
    private static final String DECOY_SCHEMA = "public";

    private static final Set<String> WORKFLOW_TABLES = Set.of(
            "workflow_instances",
            "workflow_history",
            "workflow_dedup",
            "workflow_timers",
            "workflow_tasks",
            "workflow_branch_tokens",
            "workflow_join_states",
            "workflow_definitions",
            "workflow_definition_activations",
            "workflow_commands",
            "workflow_command_queues",
            "workflow_signal_waits");
    private static final Set<String> TRANSACTION_DECOY_TABLES = with(WORKFLOW_TABLES, "outbox", "inbox", "orders");

    private static final Set<String> MIGRATED_INDEXES = Set.of(
            "idx_workflow_instances_business_key",
            "idx_workflow_instances_wait",
            "idx_workflow_instances_subject",
            "idx_workflow_instances_updated_at",
            "idx_workflow_instances_wait_aux_id",
            "idx_workflow_instances_archive_sweep",
            "idx_workflow_instances_archived_at",
            "idx_workflow_instances_subject_versioned",
            "idx_workflow_instances_scope_updated_at",
            "idx_workflow_dedup_workflow_id",
            "idx_workflow_timers_orphan_scan",
            "idx_workflow_timers_workflow_id",
            "idx_workflow_timers_task_reminders",
            "idx_workflow_timers_branch_token",
            "idx_workflow_tasks_workflow_id",
            "idx_workflow_tasks_open_by_user",
            "idx_workflow_tasks_open_by_role",
            "idx_workflow_tasks_open_by_queue",
            "idx_workflow_tasks_open_due_at",
            "idx_workflow_tasks_updated_at_keyset",
            "idx_workflow_tasks_branch_token",
            "idx_workflow_tasks_scope_updated_at",
            "idx_workflow_branch_tokens_status_lookup",
            "idx_workflow_branch_tokens_recovery",
            "idx_workflow_branch_tokens_wait",
            "idx_workflow_join_states_status",
            "idx_workflow_definitions_id",
            "idx_workflow_signal_waits_contract_signal_key",
            "idx_workflow_instances_business_key_scope_key_definition_id");
    private static final Set<String> MIGRATED_CONSTRAINTS =
            Set.of("fk_workflow_dedup_workflow_id", "ck_workflow_timers_task_id_purpose");

    /** Column names disjoint from every column the workflow and outbox statements reference. */
    private static final List<String> DECOY_COLUMNS = List.of("decoy_key", "sentinel");

    private static final Map<String, String> WORKFLOW_PLACEMENT = Map.of(WORKFLOW_INSTANCE, MAPPED_SCHEMA);
    private static final Map<String, String> TRANSACTION_PLACEMENT = Map.of(
            WORKFLOW_INSTANCE, MAPPED_SCHEMA,
            MESSAGING_INSTANCE, MESSAGING_SCHEMA,
            APP_INSTANCE, APP_SCHEMA);

    private static final String FORK_STEP = "fork";
    private static final String JOIN_STEP = "join";
    private static final String PLAN_HASH = "schema-placement-plan";
    private static final String RETENTION_DEFINITION = "schema-placement-retention";
    private static final String REGISTRY_DEFINITION = "schema-placement-registry";
    private static final String SUBJECT_TYPE = "order";
    private static final String SCOPE_KEY = "scope-a";
    private static final String OTHER_SCOPE_KEY = "scope-b";
    private static final List<TaskDecisionDescriptor> DECISIONS =
            List.of(new TaskDecisionDescriptor("approve", "java.lang.String", "done"));
    private static final int PAGE_SIZE = 20;

    static final PostgresContainer db = new PostgresContainer().withDatabaseName("workflow_schema");

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("workflow migration and every store, query and retention operation use the mapped schema")
    void routesLifecycleAndMaintenanceToMappedSchema(Vertx vertx) throws Exception {
        // Given: a decoy default schema with the real workflow table names, no mapped schema yet, and a
        // migration-only plus a runtime-only component sharing one placement configuration
        createDecoyDefaultSchema(WORKFLOW_TABLES);
        List<String> decoyBefore = decoySnapshot(WORKFLOW_TABLES);
        assertSchemasDoNotExist(MAPPED_SCHEMA);
        WorkflowMigrationComponent migration = DaggerPgWorkflowSchemaIT_WorkflowMigrationComponent.factory()
                .create(vertx, migrationConfig(WORKFLOW_PLACEMENT));
        RuntimeComponent runtime = DaggerPgWorkflowSchemaIT_RuntimeComponent.factory()
                .create(vertx, runtimeConfig(WORKFLOW_PLACEMENT), Clock.systemUTC());
        try {
            FlywayMigrationRunner runner = migration.runner();

            // Then: both graphs have the intended shape before anything touches the database
            assertMigrationGraphHasNoPool(WorkflowMigrationComponent.class, migration.optionalPool());
            assertRuntimeGraphHasNoFlyway(runtime);
            assertPlacementBoundFromConfiguration(runtime, migration.dbSchemas(), WORKFLOW_PLACEMENT);
            assertEquals(
                    Set.of(new MigrationDefinition(WORKFLOW_INSTANCE, List.of(WORKFLOW_LOCATION), List.of())),
                    migration.migrationDefinitions(),
                    "the shipped declaration contributes exactly the workflow migration location");
            assertConnectionFreeWorkflowView(runner);

            // When: the public named runner migrates twice
            MigrationReport first = await(runner.run(vertx));
            MigrationReport second = await(runner.run(vertx));

            // Then: the scripts ran once, and every workflow object exists only in the mapped schema
            MigrationInstanceResult applied = onlyResult(first);
            assertMigratedInto(applied, MAPPED_SCHEMA, WORKFLOW_HISTORY);
            assertTrue(applied.migrationsApplied() > 0, "the first run applies the packaged scripts");
            MigrationInstanceResult repeated = onlyResult(second);
            assertMigratedInto(repeated, MAPPED_SCHEMA, WORKFLOW_HISTORY);
            assertEquals(0, repeated.migrationsApplied(), "nothing is applied twice");
            assertEquals(applied.currentVersion(), repeated.currentVersion());
            assertWorkflowObjectsLiveOnlyInMappedSchema();
            assertDecoyUntouched(decoyBefore, WORKFLOW_TABLES);

            // Then: every store resolves one scoped instance, and the services hold those instances
            assertEachStoreIsOneScopedInstance(runtime);
            assertServicesReuseScopedStores(runtime);

            // When: every public store, query and retention operation runs while the decoy exists
            // Then: each succeeds against the mapped objects, which the decoy's shape would not allow
            LifecycleInstances instances = exerciseInstanceRepository(runtime);
            exerciseHistoryRepository(runtime, instances);
            exerciseDedupRepository(runtime, instances);
            SeededBranches branches = exerciseBranchTokenRepository(runtime, instances);
            exerciseJoinStateRepository(runtime, instances, branches);
            UUID openTask = exerciseTaskStore(runtime, instances, branches);
            exerciseTimerStore(runtime, instances, branches, openTask);
            exerciseDefinitionRegistry(runtime);
            exerciseRetentionAndArchivedFilters(runtime);
            exerciseSignalWaitRepository(runtime, instances);
            exerciseTaskReservations(runtime, instances);
            exerciseCommandRepository(runtime);

            // Then: the decoy schema is exactly as it was, and no new object appeared outside the mapping
            assertDecoyUntouched(decoyBefore, WORKFLOW_TABLES);
            assertWorkflowObjectsLiveOnlyInMappedSchema();
        } finally {
            await(runtime.pool().close());
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("workflow, application and outbox writes roll back and commit together in one caller transaction")
    void rollsBackWorkflowAppAndOutboxTogether(Vertx vertx) throws Exception {
        // Given: a decoy default schema with the workflow, outbox, inbox and application table names, and
        // workflow, messaging and application instances that each map to their own missing schema
        createDecoyDefaultSchema(TRANSACTION_DECOY_TABLES);
        List<String> decoyBefore = decoySnapshot(TRANSACTION_DECOY_TABLES);
        assertSchemasDoNotExist(MAPPED_SCHEMA, MESSAGING_SCHEMA, APP_SCHEMA);
        TransactionMigrationComponent migration = DaggerPgWorkflowSchemaIT_TransactionMigrationComponent.factory()
                .create(vertx, migrationConfig(TRANSACTION_PLACEMENT));
        RuntimeComponent runtime = DaggerPgWorkflowSchemaIT_RuntimeComponent.factory()
                .create(vertx, runtimeConfig(TRANSACTION_PLACEMENT), Clock.systemUTC());
        try {
            assertMigrationGraphHasNoPool(TransactionMigrationComponent.class, migration.optionalPool());
            assertRuntimeGraphHasNoFlyway(runtime);
            assertPlacementBoundFromConfiguration(runtime, migration.dbSchemas(), TRANSACTION_PLACEMENT);

            // When: the public named runner migrates all three instances
            MigrationReport report = await(migration.runner().run(vertx));

            // Then: each instance migrated into its own schema, and the decoy is untouched
            assertEachInstanceMigratedIntoItsSchema(report);
            assertTransactionObjectsLiveInTheirSchemas();
            assertDecoyUntouched(decoyBefore, TRANSACTION_DECOY_TABLES);

            // When: workflow, application and outbox writes share one caller transaction that is then failed
            // Then: all of them roll back together
            assertCallerTransactionRollsBackEveryModule(runtime);
            assertDecoyUntouched(decoyBefore, TRANSACTION_DECOY_TABLES);

            // When: the same unit of work commits
            // Then: all of them are kept together
            assertCallerTransactionCommitsEveryModule(runtime);
            assertDecoyUntouched(decoyBefore, TRANSACTION_DECOY_TABLES);
        } finally {
            await(runtime.pool().close());
        }
    }

    @AfterEach
    void dropFixtureSchemas() throws SQLException {
        for (String schema : List.of(MAPPED_SCHEMA, MESSAGING_SCHEMA, APP_SCHEMA)) {
            execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
        for (String table : TRANSACTION_DECOY_TABLES) {
            execute("DROP TABLE IF EXISTS " + DECOY_SCHEMA + "." + table);
        }
    }

    // --- Components ---

    /** Migration-only graph with the shipped workflow declaration: no Pool and no workflow runtime. */
    @Singleton
    @Component(
            modules = {
                DbModule.class,
                DbFlywayModule.class,
                ConfigParsingModule.class,
                WorkflowMigrationsModule.class,
                AbsentPoolProbeModule.class
            })
    interface WorkflowMigrationComponent {

        FlywayMigrationRunner runner();

        DbSchemas dbSchemas();

        Set<MigrationDefinition> migrationDefinitions();

        Optional<Pool> optionalPool();

        @Component.Factory
        interface Factory {
            WorkflowMigrationComponent create(
                    @BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }

    /** Migration-only graph with the shipped workflow and inbox/outbox declarations plus the application's. */
    @Singleton
    @Component(
            modules = {
                DbModule.class,
                DbFlywayModule.class,
                ConfigParsingModule.class,
                WorkflowMigrationsModule.class,
                InboxOutboxMigrationsModule.class,
                ApplicationMigrationModule.class,
                AbsentPoolProbeModule.class
            })
    interface TransactionMigrationComponent {

        FlywayMigrationRunner runner();

        DbSchemas dbSchemas();

        Optional<Pool> optionalPool();

        @Component.Factory
        interface Factory {
            TransactionMigrationComponent create(
                    @BindsInstance Vertx vertx, @BindsInstance @VertxConfig JsonObject config);
        }
    }

    /**
     * Runtime-only graph: the PostgreSQL runtime, the shipped workflow module and the PostgreSQL inbox/outbox
     * module, deliberately without any Flyway module.
     */
    @Singleton
    @Component(
            modules = {
                DbModule.class,
                DbPostgresqlModule.class,
                ConfigParsingModule.class,
                WorkflowPostgresqlModule.class,
                TransactionalMessagingPostgresqlModule.class,
                AbsentFlywayProbeModule.class,
                ForkJoinDefinitionModule.class
            })
    interface RuntimeComponent {

        Pool pool();

        DbSchemas dbSchemas();

        Optional<DbSchemas> optionalDbSchemas();

        Optional<FlywayConfig> optionalFlywayConfig();

        Optional<MigrationRunner> optionalMigrationRunner();

        PgWorkflowInstanceRepository pgWorkflowInstanceRepository();

        WorkflowInstanceRepository<SqlClient> workflowInstanceRepository();

        PgWorkflowHistoryRepository pgWorkflowHistoryRepository();

        WorkflowHistoryRepository<SqlClient> workflowHistoryRepository();

        PgWorkflowDedupRepository pgWorkflowDedupRepository();

        WorkflowDedupRepository<SqlClient> workflowDedupRepository();

        PgBranchTokenRepository pgBranchTokenRepository();

        BranchTokenRepository<SqlClient> branchTokenRepository();

        PgJoinStateRepository pgJoinStateRepository();

        JoinStateRepository<SqlClient> joinStateRepository();

        PgTaskStore pgTaskStore();

        TaskStore<SqlClient> taskStore();

        PgTimerStore pgTimerStore();

        TimerStore<SqlClient> timerStore();

        PgWorkflowDefinitionRegistryStore pgWorkflowDefinitionRegistryStore();

        WorkflowDefinitionRegistryStore workflowDefinitionRegistryStore();

        PgWorkflowSignalWaitRepository pgWorkflowSignalWaitRepository();

        WorkflowSignalWaitRepository<SqlClient> workflowSignalWaitRepository();

        PgTaskReservations pgTaskReservations();

        TaskReservations<SqlClient> taskReservations();

        PgWorkflowCommandRepository pgWorkflowCommandRepository();

        WorkflowCommandRepository<SqlClient> workflowCommandRepository();

        PgWorkflowRetentionRepository pgWorkflowRetentionRepository();

        PgWorkflowInstanceQueryService pgWorkflowInstanceQueryService();

        WorkflowInstanceQueryService workflowInstanceQueryService();

        PgWorkflowRetentionService pgWorkflowRetentionService();

        WorkflowRetentionService workflowRetentionService();

        PgBranchTokenQueryService branchTokenQueryService();

        OutboxService outboxService();

        @Component.Factory
        interface Factory {
            RuntimeComponent create(
                    @BindsInstance Vertx vertx,
                    @BindsInstance @VertxConfig JsonObject config,
                    @BindsInstance Clock clock);
        }
    }

    @Module
    abstract static class ApplicationMigrationModule {

        @Provides
        @IntoSet
        static MigrationDefinition applicationMigration() {
            return new MigrationDefinition(APP_INSTANCE, List.of(APP_LOCATION), List.of());
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
    }

    /** Registers the fan-out definition and supplies the plan validator the workflow registry needs. */
    @Module
    abstract static class ForkJoinDefinitionModule {

        @Provides
        @IntoSet
        static WorkflowContributor forkJoinContributor() {
            return registry -> registry.register(FORK_JOIN_DEFINITION);
        }

        @Provides
        @Singleton
        static WorkflowPlanValidator planValidator() {
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.of()));
        }
    }

    record StartFanout(String orderId) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "schema-placement-" + orderId;
        }
    }

    record FanoutState(String orderId) {}

    interface FanoutContract {
        @WorkflowSignal("signal-a")
        Future<Void> signalA(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);

        @WorkflowSignal("signal-b")
        Future<Void> signalB(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    /** Fork with two signal-wait branches under an all-required join, the shape the executor serves. */
    static final WorkflowDefinition<FanoutState, FanoutContract> FORK_JOIN_DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<FanoutContract> contract() {
            return FanoutContract.class;
        }

        @Override
        public Class<FanoutState> stateType() {
            return FanoutState.class;
        }

        @Override
        public String definitionId() {
            return "schema-placement-fanout";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<FanoutState> wf) {
            wf.init(StartFanout.class, p -> new FanoutState(p.orderId()))
                    .fork(FORK_STEP)
                    .branch("a", "wait-a")
                    .branch("b", "wait-b")
                    .join(JOIN_STEP)
                    .waitForSignal("wait-a", "signal-a", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("complete-a")
                    .build()
                    .complete("complete-a")
                    .waitForSignal("wait-b", "signal-b", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("complete-b")
                    .build()
                    .complete("complete-b")
                    .join(JOIN_STEP)
                    .allRequired((s, results) -> s)
                    .toStep("done")
                    .endJoin()
                    .complete("done");
            wf.initialStep(FORK_STEP);
        }
    };

    // --- Graph assertions ---

    private static void assertMigrationGraphHasNoPool(Class<?> component, Optional<Pool> pool) {
        Set<Class<?>> modules = declaredModules(component);

        assertTrue(modules.contains(DbFlywayModule.class), "migration graph must declare the Flyway module");
        assertTrue(modules.contains(WorkflowMigrationsModule.class), "migration graph must declare the workflow");
        assertFalse(modules.contains(DbPostgresqlModule.class), "migration graph must not declare the runtime module");
        assertFalse(
                modules.contains(WorkflowPostgresqlModule.class),
                "migration graph must not declare the workflow runtime module");
        assertTrue(pool.isEmpty(), "migration graph must not bind a Pool");
    }

    private static void assertRuntimeGraphHasNoFlyway(RuntimeComponent runtime) {
        Set<Class<?>> modules = declaredModules(RuntimeComponent.class);
        Set<Class<?>> workflowModules = declaredModules(WorkflowPostgresqlModule.class);

        assertTrue(
                workflowModules.contains(WorkflowMigrationsModule.class),
                "the workflow module must include its migration declaration: " + workflowModules);
        assertTrue(
                workflowModules.contains(DbSchemasModule.class),
                "the workflow module must include the optional placement declaration: " + workflowModules);
        assertTrue(
                modules.stream().noneMatch(module -> module.getName().startsWith("dev.vertique.db.flyway.")),
                "runtime graph must declare no module from the Flyway package, directly or through includes: "
                        + modules);
        assertTrue(runtime.optionalFlywayConfig().isEmpty(), "runtime graph must have no FlywayConfig binding");
        assertTrue(runtime.optionalMigrationRunner().isEmpty(), "runtime graph must have no MigrationRunner binding");
        assertEquals(
                Set.of(DbModule.class),
                modulesBinding(modules, DbSchemas.class),
                "only DbModule may bind the placement; no test module substitutes it");
    }

    private static void assertPlacementBoundFromConfiguration(
            RuntimeComponent runtime, DbSchemas migrationPlacement, Map<String, String> configured) {
        assertEquals(configured, runtime.dbSchemas().mappings(), "runtime placement comes from db.schemas");
        assertEquals(configured, migrationPlacement.mappings(), "migration placement comes from db.schemas");
        assertTrue(runtime.optionalDbSchemas().isPresent(), "the workflow module sees the placement binding");
        assertEquals(configured, runtime.optionalDbSchemas().orElseThrow().mappings());
    }

    private static void assertConnectionFreeWorkflowView(FlywayMigrationRunner runner) {
        List<MigrationInstanceView> views = runner.instances();

        assertEquals(
                List.of(WORKFLOW_INSTANCE),
                views.stream().map(MigrationInstanceView::name).toList());
        MigrationInstanceView view = views.get(0);
        assertEquals(MAPPED_SCHEMA, view.schema(), "default and history schema");
        assertEquals(List.of(MAPPED_SCHEMA), view.schemas(), "managed schemas");
        assertEquals(WORKFLOW_HISTORY, view.table(), "derived history table");
        assertEquals(List.of(WORKFLOW_LOCATION), view.locations());
        assertEquals(FlywayMode.MIGRATE, view.mode());
    }

    /** The component's modules plus everything they include, transitively. */
    private static Set<Class<?>> declaredModules(Class<?> root) {
        Component component = root.getAnnotation(Component.class);
        List<Class<?>> start = component != null ? List.of(component.modules()) : List.of(root);
        Set<Class<?>> seen = new LinkedHashSet<>();
        Deque<Class<?>> pending = new ArrayDeque<>(start);
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

    /** Modules declaring a provides or binds method whose key type is the given type. */
    private static Set<Class<?>> modulesBinding(Set<Class<?>> modules, Class<?> type) {
        return modules.stream()
                .filter(module -> Arrays.stream(module.getDeclaredMethods())
                        .filter(method -> method.getReturnType() == type)
                        .anyMatch(PgWorkflowSchemaIT::isBindingMethod))
                .collect(Collectors.toSet());
    }

    private static boolean isBindingMethod(Method method) {
        return method.isAnnotationPresent(Provides.class) || method.isAnnotationPresent(Binds.class);
    }

    // --- Migration and catalog assertions ---

    private static MigrationInstanceResult onlyResult(MigrationReport report) {
        List<MigrationInstanceResult> results = report.instances();
        assertEquals(
                List.of(WORKFLOW_INSTANCE),
                results.stream().map(result -> result.instance().name()).toList());
        return results.get(0);
    }

    private static void assertMigratedInto(MigrationInstanceResult result, String schema, String history) {
        String name = result.instance().name();
        assertEquals(MigrationStatus.MIGRATED, result.status(), name + " status");
        assertNotNull(result.currentVersion(), name + " current version");
        assertEquals(schema, result.instance().schema(), name + " resolved history schema");
        assertEquals(history, result.instance().table(), name + " resolved history table");
    }

    private static void assertEachInstanceMigratedIntoItsSchema(MigrationReport report) {
        Map<String, MigrationInstanceResult> byName = new TreeMap<>();
        for (MigrationInstanceResult result : report.instances()) {
            byName.put(result.instance().name(), result);
        }

        assertEquals(Set.of(APP_INSTANCE, MESSAGING_INSTANCE, WORKFLOW_INSTANCE), byName.keySet());
        assertMigratedInto(byName.get(APP_INSTANCE), APP_SCHEMA, APP_HISTORY);
        assertMigratedInto(byName.get(MESSAGING_INSTANCE), MESSAGING_SCHEMA, MESSAGING_HISTORY);
        assertMigratedInto(byName.get(WORKFLOW_INSTANCE), MAPPED_SCHEMA, WORKFLOW_HISTORY);
        for (MigrationInstanceResult result : byName.values()) {
            assertTrue(result.migrationsApplied() > 0, result.instance().name() + " applied its scripts");
        }
    }

    private static void assertWorkflowObjectsLiveOnlyInMappedSchema() throws SQLException {
        // The mapped schema holds the history, the twelve tables, their indexes and named constraints, and
        // nothing that the packaged scripts do not create
        assertEquals(with(WORKFLOW_TABLES, WORKFLOW_HISTORY), tablesIn(MAPPED_SCHEMA));
        assertTrue(
                indexesIn(MAPPED_SCHEMA).containsAll(MIGRATED_INDEXES),
                "migrated indexes in the mapped schema: " + indexesIn(MAPPED_SCHEMA));
        assertTrue(
                sequencesIn(MAPPED_SCHEMA).isEmpty(),
                "the workflow scripts create no standalone sequence (an identity column owns its own)");
        assertEquals(
                Set.of("workflow_commands_notify"),
                functionsIn(MAPPED_SCHEMA),
                "the workflow scripts create only the command notify function");

        // No other schema received any of them
        assertEquals(Set.of(MAPPED_SCHEMA), schemasHoldingTable(WORKFLOW_HISTORY));
        for (String table : WORKFLOW_TABLES) {
            assertEquals(Set.of(MAPPED_SCHEMA, DECOY_SCHEMA), schemasHoldingTable(table), table);
        }
        assertEquals(
                Set.of(MAPPED_SCHEMA),
                Set.copyOf(column("SELECT schemaname FROM pg_indexes WHERE indexname LIKE 'idx\\_workflow\\_%'")),
                "workflow indexes exist only in the mapped schema");
        assertEquals(
                Set.of(MAPPED_SCHEMA),
                Set.copyOf(column(
                        "SELECT n.nspname FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace"
                                + " WHERE c.conname = ? OR c.conname = ?",
                        MIGRATED_CONSTRAINTS.toArray())),
                "named workflow constraints exist only in the mapped schema");
    }

    private static void assertTransactionObjectsLiveInTheirSchemas() throws SQLException {
        assertEquals(Set.of("orders", APP_HISTORY), tablesIn(APP_SCHEMA));
        assertEquals(Set.of(APP_SCHEMA, DECOY_SCHEMA), schemasHoldingTable("orders"));
        assertEquals(Set.of("inbox", "outbox", MESSAGING_HISTORY), tablesIn(MESSAGING_SCHEMA));
        assertEquals(Set.of(MESSAGING_SCHEMA, DECOY_SCHEMA), schemasHoldingTable("outbox"));
        assertEquals(with(WORKFLOW_TABLES, WORKFLOW_HISTORY), tablesIn(MAPPED_SCHEMA));
        for (String table : WORKFLOW_TABLES) {
            assertEquals(Set.of(MAPPED_SCHEMA, DECOY_SCHEMA), schemasHoldingTable(table), table);
        }
        for (String history : List.of(APP_HISTORY, MESSAGING_HISTORY, WORKFLOW_HISTORY)) {
            assertEquals(1, schemasHoldingTable(history).size(), "one schema holds " + history);
        }
    }

    // --- Scoped identity ---

    private static void assertEachStoreIsOneScopedInstance(RuntimeComponent runtime) {
        assertOneScopedInstance(
                "instance repository",
                runtime.pgWorkflowInstanceRepository(),
                runtime.pgWorkflowInstanceRepository(),
                runtime.workflowInstanceRepository(),
                runtime.workflowInstanceRepository());
        assertOneScopedInstance(
                "history repository",
                runtime.pgWorkflowHistoryRepository(),
                runtime.pgWorkflowHistoryRepository(),
                runtime.workflowHistoryRepository(),
                runtime.workflowHistoryRepository());
        assertOneScopedInstance(
                "dedup repository",
                runtime.pgWorkflowDedupRepository(),
                runtime.pgWorkflowDedupRepository(),
                runtime.workflowDedupRepository(),
                runtime.workflowDedupRepository());
        assertOneScopedInstance(
                "branch-token repository",
                runtime.pgBranchTokenRepository(),
                runtime.pgBranchTokenRepository(),
                runtime.branchTokenRepository(),
                runtime.branchTokenRepository());
        assertOneScopedInstance(
                "join-state repository",
                runtime.pgJoinStateRepository(),
                runtime.pgJoinStateRepository(),
                runtime.joinStateRepository(),
                runtime.joinStateRepository());
        assertOneScopedInstance(
                "task store", runtime.pgTaskStore(), runtime.pgTaskStore(), runtime.taskStore(), runtime.taskStore());
        assertOneScopedInstance(
                "timer store",
                runtime.pgTimerStore(),
                runtime.pgTimerStore(),
                runtime.timerStore(),
                runtime.timerStore());
        assertOneScopedInstance(
                "definition registry store",
                runtime.pgWorkflowDefinitionRegistryStore(),
                runtime.pgWorkflowDefinitionRegistryStore(),
                runtime.workflowDefinitionRegistryStore(),
                runtime.workflowDefinitionRegistryStore());
        assertOneScopedInstance(
                "signal-wait repository",
                runtime.pgWorkflowSignalWaitRepository(),
                runtime.pgWorkflowSignalWaitRepository(),
                runtime.workflowSignalWaitRepository(),
                runtime.workflowSignalWaitRepository());
        assertOneScopedInstance(
                "task reservations",
                runtime.pgTaskReservations(),
                runtime.pgTaskReservations(),
                runtime.taskReservations(),
                runtime.taskReservations());
        assertOneScopedInstance(
                "command repository",
                runtime.pgWorkflowCommandRepository(),
                runtime.pgWorkflowCommandRepository(),
                runtime.workflowCommandRepository(),
                runtime.workflowCommandRepository());
        assertOneScopedInstance(
                "retention repository",
                runtime.pgWorkflowRetentionRepository(),
                runtime.pgWorkflowRetentionRepository());
    }

    private static void assertOneScopedInstance(String store, Object first, Object... lookups) {
        for (Object lookup : lookups) {
            assertSame(first, lookup, "every concrete and SPI lookup of the " + store + " is one scoped instance");
        }
    }

    /**
     * The query and retention services must use the scoped stores, not instances of their own; the
     * collaborator a service holds is not observable through its API, so this reads the service's fields.
     */
    private static void assertServicesReuseScopedStores(RuntimeComponent runtime) throws Exception {
        assertSame(runtime.pgWorkflowInstanceQueryService(), runtime.workflowInstanceQueryService());
        assertSame(runtime.pgWorkflowRetentionService(), runtime.workflowRetentionService());
        assertHolds(runtime.pgWorkflowInstanceQueryService(), runtime.pgWorkflowInstanceRepository());
        assertHolds(runtime.pgWorkflowRetentionService(), runtime.pgWorkflowRetentionRepository());
        assertHolds(runtime.branchTokenQueryService(), runtime.pgBranchTokenRepository());
        assertHolds(runtime.branchTokenQueryService(), runtime.pgJoinStateRepository());
    }

    private static void assertHolds(Object service, Object scoped) throws IllegalAccessException {
        List<Object> held = heldValues(service);
        assertTrue(
                held.stream().anyMatch(value -> value == scoped),
                service.getClass().getSimpleName() + " must hold the scoped "
                        + scoped.getClass().getSimpleName());
    }

    private static List<Object> heldValues(Object holder) throws IllegalAccessException {
        List<Object> values = new ArrayList<>();
        for (Class<?> type = holder.getClass(); type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    values.add(field.get(holder));
                }
            }
        }
        return values;
    }

    // --- Instance repository and instance query service ---

    /** The two lifecycle instances of the fan-out definition and the keys that find them. */
    private record LifecycleInstances(
            WorkflowInstance withSubject, WorkflowInstance plain, String businessKey, String subjectId) {}

    private static LifecycleInstances exerciseInstanceRepository(RuntimeComponent runtime) throws Exception {
        PgWorkflowInstanceRepository instances = runtime.pgWorkflowInstanceRepository();
        String definitionId = FORK_JOIN_DEFINITION.definitionId();
        String businessKey = unique("business-key");
        String subjectId = unique("subject");
        WorkflowInstance withSubject = newInstance(
                definitionId, businessKey, new WorkflowSubjectRef(SUBJECT_TYPE, subjectId, "v1"), SCOPE_KEY);
        WorkflowInstance plain = newInstance(definitionId, null, null);

        // insert in the caller's transaction
        inTransaction(runtime, tx -> instances.insert(withSubject, tx));
        inTransaction(runtime, tx -> instances.insert(plain, tx));
        assertEquals("RUNNING", mappedValue("workflow_instances", "id", withSubject.id(), "status"));
        assertEquals(SCOPE_KEY, mappedValue("workflow_instances", "id", withSubject.id(), "scope_key"));
        assertEquals(WorkflowScopes.DEFAULT, mappedValue("workflow_instances", "id", plain.id(), "scope_key"));

        // every read path finds the mapped row
        WorkflowInstance loaded = await(instances.findById(withSubject.id())).orElseThrow();
        assertEquals(businessKey, loaded.businessKey());
        assertEquals(new WorkflowSubjectRef(SUBJECT_TYPE, subjectId, "v1"), loaded.subjectRef());
        assertTrue(inTransaction(runtime, tx -> instances.findById(withSubject.id(), tx))
                .isPresent());
        assertTrue(inTransaction(runtime, tx -> instances.findByIdForUpdate(withSubject.id(), tx))
                .isPresent());
        assertEquals(
                withSubject.id(),
                inTransaction(runtime, tx -> instances.findByBusinessKey(SCOPE_KEY, definitionId, businessKey, tx))
                        .orElseThrow()
                        .id());

        // optimistic update applies once, then reports the stale expected version
        WorkflowInstance waiting = withSubject.withVersion(1).withStatus(WorkflowStatus.WAITING);
        assertEquals(1, rowsChanged(runtime, tx -> instances.updateOptimistic(waiting, 0, tx)));
        assertEquals(0, rowsChanged(runtime, tx -> instances.updateOptimistic(waiting, 0, tx)));
        assertEquals("WAITING", mappedValue("workflow_instances", "id", withSubject.id(), "status"));

        // migration re-pins the definition version and plan
        WorkflowInstance repinned = repinned(waiting, 2L, "schema-placement-plan-v2", 2L, "{\"migrated\":true}");
        assertEquals(1, rowsChanged(runtime, tx -> instances.migratePinAndState(repinned, 1, tx)));
        assertEquals("2", mappedValue("workflow_instances", "id", withSubject.id(), "definition_version"));
        assertEquals(
                "schema-placement-plan-v2", mappedValue("workflow_instances", "id", withSubject.id(), "plan_hash"));
        assertEquals("RUNNING", mappedValue("workflow_instances", "id", withSubject.id(), "status"));

        // the filtered page and the query service read the same mapped rows
        WorkflowInstanceQuery byDefinition = WorkflowInstanceQuery.byDefinitionId(definitionId);
        Set<UUID> both = Set.of(withSubject.id().value(), plain.id().value());
        assertEquals(both, instanceIds(await(instances.findFiltered(byDefinition, PageCursor.first(PAGE_SIZE)))));
        assertEquals(
                both,
                instanceIds(
                        await(runtime.workflowInstanceQueryService().list(byDefinition, PageCursor.first(PAGE_SIZE)))));
        assertEquals(
                2L,
                await(runtime.workflowInstanceQueryService().getById(withSubject.id()))
                        .orElseThrow()
                        .version());

        // the scope filter is an exact match on the mapped column, and the re-pin leaves the scope alone
        assertEquals(
                Set.of(withSubject.id().value()),
                instanceIds(await(
                        instances.findFiltered(byDefinition.withScopeKey(SCOPE_KEY), PageCursor.first(PAGE_SIZE)))));
        assertEquals(
                Set.of(),
                instanceIds(await(instances.findFiltered(
                        byDefinition.withScopeKey(OTHER_SCOPE_KEY), PageCursor.first(PAGE_SIZE)))));
        assertEquals(SCOPE_KEY, mappedValue("workflow_instances", "id", withSubject.id(), "scope_key"));

        return new LifecycleInstances(repinned, plain, businessKey, subjectId);
    }

    // --- History repository ---

    private static void exerciseHistoryRepository(RuntimeComponent runtime, LifecycleInstances instances)
            throws Exception {
        PgWorkflowHistoryRepository history = runtime.pgWorkflowHistoryRepository();
        WorkflowInstanceId id = instances.withSubject().id();

        assertEquals(1L, sequenceNumber(runtime, tx -> history.nextSequence(id, tx)));
        inTransaction(runtime, tx -> history.append(historyEntry(id, 1, WorkflowEntryType.START), tx));
        inTransaction(runtime, tx -> history.append(historyEntry(id, 2, WorkflowEntryType.FORK_DISPATCHED), tx));
        assertEquals(3L, sequenceNumber(runtime, tx -> history.nextSequence(id, tx)));

        assertEquals(List.of(1L, 2L), sequences(await(history.listByInstance(id))));
        assertEquals(List.of(1L, 2L), sequences(inTransaction(runtime, tx -> history.listByInstance(id, tx))));
        assertEquals(List.of(2L), sequences(inTransaction(runtime, tx -> history.listRecentByInstance(id, 1, tx))));
        assertEquals(2, mappedRows("workflow_history", "workflow_id", id));
    }

    // --- Dedup repository ---

    private static void exerciseDedupRepository(RuntimeComponent runtime, LifecycleInstances instances)
            throws Exception {
        PgWorkflowDedupRepository dedup = runtime.pgWorkflowDedupRepository();
        WorkflowInstanceId winner = instances.withSubject().id();
        WorkflowInstanceId other = instances.plain().id();
        String definitionId = FORK_JOIN_DEFINITION.definitionId();

        // start: the first claim inserts, a later claim resolves to the winning instance
        String startKey = unique("start");
        StartDedupResult claimed = inTransaction(
                runtime,
                tx -> dedup.claimOrResolveStart(
                        WorkflowScopes.DEFAULT, definitionId, startKey, winner, "fp-start", tx));
        StartDedupResult resolved = inTransaction(
                runtime,
                tx -> dedup.claimOrResolveStart(WorkflowScopes.DEFAULT, definitionId, startKey, other, "fp-other", tx));
        assertTrue(claimed.didInsert());
        assertFalse(resolved.didInsert());
        assertEquals(winner, resolved.workflowId());
        assertEquals("fp-start", resolved.existingFingerprint());

        // signal: absent, claimed, then found
        String signalKey = unique("signal");
        assertTrue(inTransaction(runtime, tx -> dedup.findSignal(winner, signalKey, tx))
                .isEmpty());
        inTransaction(
                runtime, tx -> dedup.claimOrResolveSignalScoped(winner.value().toString(), signalKey, winner, tx));
        assertEquals(
                Optional.of(winner.value()), inTransaction(runtime, tx -> dedup.findSignal(winner, signalKey, tx)));

        // task completion and reassignment: the repeat reports the stored fingerprint
        UUID taskId = UUID.randomUUID();
        String completionKey = unique("complete");
        String reassignmentKey = unique("reassign");
        assertEquals(
                new DedupClaim(true, "fp-complete"),
                inTransaction(
                        runtime,
                        tx -> dedup.claimOrResolveTaskCompletion(taskId, completionKey, winner, "fp-complete", tx)));
        assertEquals(
                new DedupClaim(false, "fp-complete"),
                inTransaction(
                        runtime,
                        tx -> dedup.claimOrResolveTaskCompletion(taskId, completionKey, winner, "fp-again", tx)));
        assertEquals(
                new DedupClaim(true, "fp-reassign"),
                inTransaction(
                        runtime,
                        tx -> dedup.claimOrResolveTaskReassignment(
                                taskId, reassignmentKey, winner, "fp-reassign", tx)));
        assertEquals(
                new DedupClaim(false, "fp-reassign"),
                inTransaction(
                        runtime,
                        tx -> dedup.claimOrResolveTaskReassignment(taskId, reassignmentKey, winner, "fp-again", tx)));

        // signal, scoped signal and dispatch claims: inserted once, then resolved
        String claimKey = unique("signal-claim");
        String scope = unique("branch-scope");
        String scopedKey = unique("scoped");
        String dispatchKey = unique("dispatch");
        String claimScope = winner.value().toString();
        assertTrue(claimed(runtime, tx -> dedup.claimOrResolveSignalScoped(claimScope, claimKey, winner, tx)));
        assertFalse(claimed(runtime, tx -> dedup.claimOrResolveSignalScoped(claimScope, claimKey, winner, tx)));
        assertTrue(claimed(runtime, tx -> dedup.claimOrResolveSignalScoped(scope, scopedKey, winner, tx)));
        assertFalse(claimed(runtime, tx -> dedup.claimOrResolveSignalScoped(scope, scopedKey, winner, tx)));
        String dispatchScope = winner.value().toString();
        assertTrue(claimed(runtime, tx -> dedup.claimOrResolveDispatch(winner, dispatchScope, dispatchKey, tx)));
        assertFalse(claimed(runtime, tx -> dedup.claimOrResolveDispatch(winner, dispatchScope, dispatchKey, tx)));

        // every claim is one mapped row owned by the winning instance
        assertEquals(
                List.of("dispatch=1", "signal=3", "start=1", "task-complete=1", "task-reassign=1"),
                column(
                        "SELECT kind || '=' || count(*) FROM " + MAPPED_SCHEMA + ".workflow_dedup"
                                + " WHERE workflow_id::text = ? GROUP BY kind ORDER BY kind",
                        winner.value()));
    }

    // --- Branch-token repository and branch query service ---

    /** Branches of the subject instance's fork. */
    private record SeededBranches(
            BranchToken running, BranchToken waiting, BranchToken retryScheduled, BranchToken completed) {}

    private static SeededBranches exerciseBranchTokenRepository(RuntimeComponent runtime, LifecycleInstances instances)
            throws Exception {
        PgBranchTokenRepository branches = runtime.pgBranchTokenRepository();
        WorkflowInstanceId workflowId = instances.withSubject().id();
        Instant now = Instant.now();

        // Given: one branch per status the queries distinguish
        BranchToken running = newBranch(workflowId, "a", BranchStatus.RUNNING, null, null, null, now);
        BranchToken waiting = newBranch(workflowId, "b", BranchStatus.WAITING, WaitType.SIGNAL, "signal-b", null, now);
        BranchToken retryScheduled = newBranch(
                workflowId, "c", BranchStatus.RETRY_SCHEDULED, null, null, now.plus(Duration.ofMinutes(30)), now);
        BranchToken completed = newBranch(workflowId, "d", BranchStatus.COMPLETED, null, null, null, now);
        for (BranchToken token : List.of(running, waiting, retryScheduled, completed)) {
            inTransaction(runtime, tx -> branches.insert(token, tx));
        }

        // optimistic update applies once, then reports the stale expected version
        BranchToken advanced = running.withStep("step-2", 1L, now);
        assertEquals(1, rowsChanged(runtime, tx -> branches.updateOptimistic(advanced, 0L, tx)));
        assertEquals(0, rowsChanged(runtime, tx -> branches.updateOptimistic(advanced, 0L, tx)));
        assertEquals("step-2", mappedValue("workflow_branch_tokens", "id", running.id(), "current_step_id"));

        // point reads
        assertEquals(
                "step-2",
                inTransaction(runtime, tx -> branches.findById(running.id(), tx))
                        .orElseThrow()
                        .currentStepId());
        assertTrue(inTransaction(runtime, tx -> branches.findByIdForUpdate(running.id(), tx))
                .isPresent());

        // list reads
        assertEquals(
                List.of("a", "b", "c", "d"),
                branchIds(inTransaction(runtime, tx -> branches.findByWorkflow(workflowId, tx))));
        assertEquals(
                List.of("a", "b", "c"),
                branchIds(inTransaction(runtime, tx -> branches.findActiveByWorkflow(workflowId, tx))));
        assertEquals(
                List.of("a", "b", "c", "d"),
                branchIds(inTransaction(runtime, tx -> branches.findByWorkflowAndFork(workflowId, FORK_STEP, tx))));
        assertEquals(
                waiting.id(),
                inTransaction(
                                runtime,
                                tx -> branches.findByWaitForBranch(
                                        workflowId, FORK_STEP, "b", WaitType.SIGNAL.name(), "signal-b", tx))
                        .orElseThrow()
                        .id());

        // status scans
        assertEquals(
                List.of(waiting.id()),
                tokenIds(inTransaction(runtime, tx -> branches.findByStatus(BranchStatus.WAITING, 10, tx))));

        // the branch query service reads the same rows through every filter path
        PgBranchTokenQueryService query = runtime.branchTokenQueryService();
        assertEquals(List.of("a", "b", "c", "d"), branchIds(await(query.findByWorkflow(workflowId, null))));
        assertEquals(
                List.of("a", "b", "c", "d"),
                branchIds(await(query.findByWorkflow(workflowId, new BranchTokenFilter(null, null, FORK_STEP)))));
        assertEquals(
                List.of("b"),
                branchIds(await(
                        query.findByWorkflow(workflowId, new BranchTokenFilter(BranchStatus.WAITING, null, null)))));

        return new SeededBranches(advanced, waiting, retryScheduled, completed);
    }

    // --- Definition registry store ---

    /** Reads and writes definition rows through the shipped SPI binding while the decoy table exists. */
    private static void exerciseDefinitionRegistry(RuntimeComponent runtime) throws Exception {
        WorkflowDefinitionRegistryStore registry = runtime.workflowDefinitionRegistryStore();
        String planHash = "c".repeat(64);
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        DefinitionKey first = new DefinitionKey(WorkflowScopes.DEFAULT, REGISTRY_DEFINITION, 1);
        DefinitionKey second = new DefinitionKey(WorkflowScopes.DEFAULT, REGISTRY_DEFINITION, 2);

        await(registry.insertCandidate(
                registryRecord(first, planHash, DefinitionStatus.CANDIDATE, now), registryDocument(first)));
        ActivationClaim claim = await(registry.claim(first));
        await(registry.complete(claim));
        assertEquals(
                ReconcileOutcome.INSERTED,
                await(registry.upsertDocument(
                        registryRecord(second, planHash, DefinitionStatus.AVAILABLE, now), registryDocument(second))));
        await(registry.transition(first, DefinitionStatus.AVAILABLE, DefinitionStatus.DEPRECATED));

        assertEquals(
                DefinitionStatus.AVAILABLE,
                await(registry.find(second)).orElseThrow().status());
        assertEquals(
                "{\"v\":1}", await(registry.findDocument(first)).orElseThrow().document());
        assertEquals(
                2, await(registry.list(new DefinitionFilter(null, null, null))).size());
        assertEquals("DEPRECATED", mappedValue("workflow_definitions", "definition_version", 1, "status"));
        assertEquals("AVAILABLE", mappedValue("workflow_definitions", "definition_version", 2, "status"));
        assertEquals("{\"v\":2}", mappedValue("workflow_definitions", "definition_version", 2, "document"));
    }

    private static WorkflowDefinitionRecord registryRecord(
            DefinitionKey key, String planHash, DefinitionStatus status, Instant now) {
        return new WorkflowDefinitionRecord(
                key,
                DefinitionKind.DOCUMENT,
                "example.Contract",
                "example.State",
                planHash,
                status,
                new DefinitionProvenance("test", "memory://" + key.definitionVersion(), null, null, null, now),
                "JSON",
                now,
                now,
                Map.of(),
                null);
    }

    private static WorkflowDefinitionDocument registryDocument(DefinitionKey key) {
        return new WorkflowDefinitionDocument(key, "{\"v\":" + key.definitionVersion() + "}", "JSON");
    }

    // --- Join-state repository ---

    private static void exerciseJoinStateRepository(
            RuntimeComponent runtime, LifecycleInstances instances, SeededBranches branches) throws Exception {
        PgJoinStateRepository joins = runtime.pgJoinStateRepository();
        PgBranchTokenQueryService query = runtime.branchTokenQueryService();
        WorkflowInstanceId workflowId = instances.withSubject().id();
        Instant now = Instant.now();

        inTransaction(runtime, tx -> joins.insert(openJoin(workflowId, now), tx));
        assertEquals(
                JoinStateStatus.OPEN,
                inTransaction(runtime, tx -> joins.findByKey(workflowId, FORK_STEP, JOIN_STEP, tx))
                        .orElseThrow()
                        .status());
        assertTrue(inTransaction(runtime, tx -> joins.findForUpdate(workflowId, FORK_STEP, JOIN_STEP, tx))
                .isPresent());
        assertEquals(
                1,
                inTransaction(runtime, tx -> joins.findOpenForWorkflow(workflowId, tx))
                        .size());
        assertEquals(1, await(query.findOpenJoinStates(workflowId)).size());

        // the decision applies once, then reports the stale expected version
        String winner = branches.running().branchId();
        assertEquals(
                1,
                rowsChanged(
                        runtime,
                        tx -> joins.decide(
                                workflowId, FORK_STEP, JOIN_STEP, JoinStateStatus.COMPLETED, winner, now, 1L, 0L, tx)));
        assertEquals(
                0,
                rowsChanged(
                        runtime,
                        tx -> joins.decide(
                                workflowId, FORK_STEP, JOIN_STEP, JoinStateStatus.COMPLETED, winner, now, 1L, 0L, tx)));

        assertTrue(inTransaction(runtime, tx -> joins.findOpenForWorkflow(workflowId, tx))
                .isEmpty());
        JoinState decided =
                await(query.findJoinState(workflowId, FORK_STEP, JOIN_STEP)).orElseThrow();
        assertEquals(JoinStateStatus.COMPLETED, decided.status());
        assertEquals(winner, decided.winningBranchId());
        assertEquals("COMPLETED", mappedValue("workflow_join_states", "workflow_id", workflowId, "status"));
    }

    // --- Task store ---

    private static UUID exerciseTaskStore(
            RuntimeComponent runtime, LifecycleInstances instances, SeededBranches branches) throws Exception {
        PgTaskStore tasks = runtime.pgTaskStore();
        WorkflowInstanceId subjectWorkflow = instances.withSubject().id();
        WorkflowInstanceId plainWorkflow = instances.plain().id();
        TaskAssignment alice = new TaskAssignment.User("alice");
        Instant now = Instant.now();

        // Given: three tasks to settle, one open branch task, and one task of the plain instance
        TaskRecord toComplete = openTask(subjectWorkflow, "review", alice, null);
        TaskRecord toCancel = openTask(subjectWorkflow, "review", alice, null);
        TaskRecord toExpire = openTask(subjectWorkflow, "review", alice, null);
        TaskRecord open = openTask(subjectWorkflow, "wait-b", alice, branches.waiting());
        TaskRecord plain = openTask(plainWorkflow, "review", new TaskAssignment.Queue(unique("queue")), null);
        for (TaskRecord task : List.of(toComplete, toCancel, toExpire, open, plain)) {
            inTransaction(runtime, tx -> tasks.insertOpen(task, tx));
        }
        assertEquals(
                TaskStatus.OPEN,
                inTransaction(runtime, tx -> tasks.lockForCompletion(toComplete.taskId(), tx))
                        .orElseThrow()
                        .status());

        // each terminal transition applies to an open task
        assertEquals(
                TaskTransition.APPLIED,
                inTransaction(
                        runtime,
                        tx -> tasks.markCompleted(
                                toComplete.taskId(), "approve", "{\"approved\":true}", now, actor("alice"), tx)));
        assertEquals(
                TaskTransition.APPLIED,
                inTransaction(
                        runtime,
                        tx -> tasks.markCancelled(
                                toCancel.taskId(), "withdrawn", now, new WorkflowActor.Service("router"), tx)));
        assertEquals(
                TaskTransition.APPLIED, inTransaction(runtime, tx -> tasks.markExpired(toExpire.taskId(), now, tx)));

        // an already-terminal task reports the status it holds, read back from the mapped table
        assertEquals(
                TaskTransition.LOST_TO_CANCELLED,
                inTransaction(
                        runtime,
                        tx -> tasks.markCompleted(toCancel.taskId(), "approve", "{}", now, actor("alice"), tx)));
        assertEquals(
                TaskTransition.LOST_TO_EXPIRED,
                inTransaction(runtime, tx -> tasks.markCancelled(toExpire.taskId(), "late", now, actor("bob"), tx)));
        assertEquals(
                TaskTransition.LOST_TO_COMPLETED,
                inTransaction(runtime, tx -> tasks.markExpired(toComplete.taskId(), now, tx)));
        assertEquals(
                new TaskReassignmentResult.LostToTerminal(TaskTransition.LOST_TO_EXPIRED),
                inTransaction(
                        runtime,
                        tx -> tasks.reassign(
                                toExpire.taskId(), new TaskAssignment.Role("approvers"), actor("bob"), null, now, tx)));

        // reassignment of the open task reports the previous assignee
        TaskReassignmentResult reassigned = inTransaction(
                runtime,
                tx -> tasks.reassign(
                        open.taskId(),
                        new TaskAssignment.Role("approvers"),
                        new WorkflowActor.Service("router"),
                        "rebalance",
                        now,
                        tx));
        TaskReassignmentResult.Applied applied = assertInstanceOf(TaskReassignmentResult.Applied.class, reassigned);
        assertEquals(alice, applied.oldAssignment());
        assertEquals(subjectWorkflow, applied.workflowId());
        assertEquals("wait-b", applied.stepId());

        // reminder counter increments for the open task only
        assertEquals(
                Optional.of(1), inTransaction(runtime, tx -> tasks.incrementRemindersFiredCount(open.taskId(), tx)));
        assertEquals(
                Optional.of(2), inTransaction(runtime, tx -> tasks.incrementRemindersFiredCount(open.taskId(), tx)));
        assertEquals(
                Optional.empty(),
                inTransaction(runtime, tx -> tasks.incrementRemindersFiredCount(toComplete.taskId(), tx)));

        // point and branch reads
        TaskRecord reloaded =
                inTransaction(runtime, tx -> tasks.findById(open.taskId(), tx)).orElseThrow();
        assertEquals(new TaskAssignment.Role("approvers"), reloaded.assignment());
        assertEquals("rebalance", reloaded.reassignmentReason());
        assertEquals(
                List.of(open.taskId()),
                taskIdList(inTransaction(
                        runtime,
                        tx -> tasks.findOpenByBranchToken(branches.waiting().id(), tx))));

        // the filtered page with a subject filter joins the instance table
        TaskFilter bySubject = TaskFilter.empty().withSubjectType(SUBJECT_TYPE).withSubjectId(instances.subjectId());
        assertEquals(
                Set.of(toComplete.taskId(), toCancel.taskId(), toExpire.taskId(), open.taskId()),
                taskIds(inTransaction(runtime, tx -> tasks.findByFilter(bySubject, PageCursor.first(PAGE_SIZE), tx))));
        assertEquals(
                Set.of(open.taskId()),
                taskIds(inTransaction(
                        runtime,
                        tx -> tasks.findByFilter(
                                bySubject.withStatus(TaskStatus.OPEN), PageCursor.first(PAGE_SIZE), tx))));

        // the filtered page without a subject filter checks archival in a correlated subquery
        TaskFilter byWorkflow = TaskFilter.empty().withWorkflowId(plainWorkflow);
        assertEquals(
                Set.of(plain.taskId()),
                taskIds(inTransaction(runtime, tx -> tasks.findByFilter(byWorkflow, PageCursor.first(PAGE_SIZE), tx))));

        // the insert stamped each task with its instance's scope, read from the mapped instances table
        Set<UUID> subjectTasks = Set.of(toComplete.taskId(), toCancel.taskId(), toExpire.taskId(), open.taskId());
        for (UUID taskId : subjectTasks) {
            assertEquals(SCOPE_KEY, mappedValue("workflow_tasks", "task_id", taskId, "scope_key"));
        }
        assertEquals(WorkflowScopes.DEFAULT, mappedValue("workflow_tasks", "task_id", plain.taskId(), "scope_key"));

        // the scope filter works on both query shapes: the correlated archival check and the subject join
        TaskFilter byScope = TaskFilter.empty().withScopeKey(SCOPE_KEY);
        assertEquals(
                subjectTasks,
                taskIds(inTransaction(runtime, tx -> tasks.findByFilter(byScope, PageCursor.first(PAGE_SIZE), tx))));
        assertEquals(
                subjectTasks,
                taskIds(inTransaction(
                        runtime,
                        tx -> tasks.findByFilter(bySubject.withScopeKey(SCOPE_KEY), PageCursor.first(PAGE_SIZE), tx))));
        assertEquals(
                Set.of(),
                taskIds(inTransaction(
                        runtime,
                        tx -> tasks.findByFilter(
                                bySubject.withScopeKey(OTHER_SCOPE_KEY), PageCursor.first(PAGE_SIZE), tx))));

        assertEquals("COMPLETED", mappedValue("workflow_tasks", "task_id", toComplete.taskId(), "status"));
        assertEquals("CANCELLED", mappedValue("workflow_tasks", "task_id", toCancel.taskId(), "status"));
        assertEquals("EXPIRED", mappedValue("workflow_tasks", "task_id", toExpire.taskId(), "status"));
        assertEquals("2", mappedValue("workflow_tasks", "task_id", open.taskId(), "reminders_fired_count"));
        return open.taskId();
    }

    // --- Timer store ---

    private static void exerciseTimerStore(
            RuntimeComponent runtime, LifecycleInstances instances, SeededBranches branches, UUID openTask)
            throws Exception {
        PgTimerStore timers = runtime.pgTimerStore();
        WorkflowInstanceId workflowId = instances.withSubject().id();
        Instant later = Instant.now().plus(Duration.ofHours(1));
        Instant now = Instant.now();

        // Given: timers to settle, a reminder of the open task and a branch timer
        TimerRecord toFire = scheduledTimer(workflowId, later, TimerPurpose.STANDALONE, null, null);
        TimerRecord toCancel = scheduledTimer(workflowId, later, TimerPurpose.STANDALONE, null, null);
        TimerRecord toFail = scheduledTimer(workflowId, later, TimerPurpose.STANDALONE, null, null);
        TimerRecord reminder = scheduledTimer(workflowId, later, TimerPurpose.TASK_REMINDER, openTask, null);
        TimerRecord branchTimer =
                scheduledTimer(workflowId, later, TimerPurpose.SIGNAL_TIMEOUT, null, branches.waiting());
        for (TimerRecord timer : List.of(toFire, toCancel, toFail, reminder, branchTimer)) {
            inTransaction(runtime, tx -> timers.insertScheduled(timer, tx));
        }

        // point reads
        assertEquals(
                TimerStatus.SCHEDULED,
                inTransaction(runtime, tx -> timers.findById(toFire.timerId(), tx))
                        .orElseThrow()
                        .status());
        assertTrue(inTransaction(runtime, tx -> timers.lockForFiring(toFire.timerId(), tx))
                .isPresent());

        // each terminal transition applies to a scheduled timer
        assertEquals(
                TimerStatusTransition.APPLIED,
                inTransaction(runtime, tx -> timers.markFired(toFire.timerId(), now, tx)));
        assertEquals(
                TimerStatusTransition.APPLIED,
                inTransaction(runtime, tx -> timers.markCancelled(toCancel.timerId(), now, tx)));
        assertEquals(
                TimerStatusTransition.APPLIED,
                inTransaction(runtime, tx -> timers.markFailed(toFail.timerId(), now, "handler failed", tx)));

        // an already-terminal timer reports the status it holds, read back from the mapped table
        assertEquals(
                TimerStatusTransition.LOST_TO_CANCELLED,
                inTransaction(runtime, tx -> timers.markFired(toCancel.timerId(), now, tx)));
        assertEquals(
                TimerStatusTransition.LOST_TO_FAILED,
                inTransaction(runtime, tx -> timers.markCancelled(toFail.timerId(), now, tx)));
        assertEquals(
                TimerStatusTransition.LOST_TO_FIRED,
                inTransaction(runtime, tx -> timers.markFailed(toFire.timerId(), now, "late", tx)));

        // reminder and branch lookups
        assertEquals(
                List.of(reminder.timerId()),
                inTransaction(runtime, tx -> timers.findScheduledRemindersForTask(openTask, tx)));
        assertEquals(
                List.of(branchTimer.timerId()),
                timerIds(inTransaction(
                        runtime,
                        tx -> timers.findScheduledByBranchToken(
                                branches.waiting().id(), tx))));

        assertEquals("FIRED", mappedValue("workflow_timers", "timer_id", toFire.timerId(), "status"));
        assertEquals("CANCELLED", mappedValue("workflow_timers", "timer_id", toCancel.timerId(), "status"));
        assertEquals("FAILED", mappedValue("workflow_timers", "timer_id", toFail.timerId(), "status"));
    }

    // --- Retention, archived filters and purge ---

    private static void exerciseRetentionAndArchivedFilters(RuntimeComponent runtime) throws Exception {
        PgWorkflowInstanceRepository instances = runtime.pgWorkflowInstanceRepository();
        PgWorkflowRetentionRepository retention = runtime.pgWorkflowRetentionRepository();
        WorkflowRetentionService service = runtime.workflowRetentionService();
        Instant now = Instant.now();
        Instant cutoffDayAgo = now.minus(Duration.ofDays(1));

        // Given: an old and a recent completed instance, an old failed instance, and an open task of the old
        // completed one; completion times are seeded explicitly so no cutoff depends on the database clock
        WorkflowInstance oldCompleted = newInstance(RETENTION_DEFINITION, null, null);
        WorkflowInstance recentCompleted = newInstance(RETENTION_DEFINITION, null, null);
        WorkflowInstance oldFailed = newInstance(RETENTION_DEFINITION, null, null);
        for (WorkflowInstance instance : List.of(oldCompleted, recentCompleted, oldFailed)) {
            inTransaction(runtime, tx -> instances.insert(instance, tx));
        }
        String archivedQueue = unique("archived-queue");
        TaskRecord archivedTask = openTask(oldCompleted.id(), "review", new TaskAssignment.Queue(archivedQueue), null);
        inTransaction(runtime, tx -> runtime.taskStore().insertOpen(archivedTask, tx));
        settle(runtime, oldCompleted, WorkflowStatus.COMPLETED);
        settle(runtime, recentCompleted, WorkflowStatus.COMPLETED);
        settle(runtime, oldFailed, WorkflowStatus.FAILED);
        seedInstanceTimestamp(oldCompleted.id(), "completed_at", now.minus(Duration.ofDays(10)));
        seedInstanceTimestamp(oldFailed.id(), "completed_at", now.minus(Duration.ofDays(10)));
        WorkflowInstanceQuery byDefinition = WorkflowInstanceQuery.byDefinitionId(RETENTION_DEFINITION);
        Set<UUID> all = Set.of(
                oldCompleted.id().value(),
                recentCompleted.id().value(),
                oldFailed.id().value());
        assertEquals(all, instanceIds(await(instances.findFiltered(byDefinition, PageCursor.first(PAGE_SIZE)))));

        // When: the repository archives completed instances and the service archives failed ones
        assertEquals(
                1,
                await(retention.archiveBefore(
                        "COMPLETED", cutoffDayAgo.atOffset(ZoneOffset.UTC), RETENTION_DEFINITION, 10)));
        assertEquals(
                new RetentionResult(1, false),
                await(service.archiveFailedBefore(cutoffDayAgo, RETENTION_DEFINITION, 10)));

        // Then: exactly the old instances are archived in the mapped table
        assertEquals("true", mappedValue("workflow_instances", "id", oldCompleted.id(), "archived_at IS NOT NULL"));
        assertEquals("true", mappedValue("workflow_instances", "id", oldFailed.id(), "archived_at IS NOT NULL"));
        assertEquals("true", mappedValue("workflow_instances", "id", recentCompleted.id(), "archived_at IS NULL"));

        // Then: filtered instance pages exclude archived rows unless asked to include them
        assertEquals(
                Set.of(recentCompleted.id().value()),
                instanceIds(await(instances.findFiltered(byDefinition, PageCursor.first(PAGE_SIZE)))));
        assertEquals(
                all,
                instanceIds(await(
                        instances.findFiltered(byDefinition.withIncludeArchived(true), PageCursor.first(PAGE_SIZE)))));

        // Then: the task page without a subject filter excludes tasks of archived instances unless asked
        TaskFilter byQueue = TaskFilter.empty().withAssigneeQueue(archivedQueue);
        assertEquals(Set.of(), taskIds(inTransaction(runtime, tx -> runtime.taskStore()
                .findByFilter(byQueue, PageCursor.first(PAGE_SIZE), tx))));
        assertEquals(Set.of(archivedTask.taskId()), taskIds(inTransaction(runtime, tx -> runtime.taskStore()
                .findByFilter(byQueue.withIncludeArchived(true), PageCursor.first(PAGE_SIZE), tx))));

        // Given: archive times seeded explicitly, three days and two hours ago
        seedInstanceTimestamp(oldCompleted.id(), "archived_at", now.minus(Duration.ofDays(3)));
        seedInstanceTimestamp(oldFailed.id(), "archived_at", now.minus(Duration.ofHours(2)));

        // When / Then: the repository purges the instance archived before a day ago, with its task
        assertEquals(
                1,
                await(retention.purgeArchivedBefore(cutoffDayAgo.atOffset(ZoneOffset.UTC), RETENTION_DEFINITION, 10)));
        assertEquals(0, mappedRows("workflow_instances", "id", oldCompleted.id()));
        assertEquals(0, mappedRows("workflow_tasks", "task_id", archivedTask.taskId()));
        assertEquals(1, mappedRows("workflow_instances", "id", oldFailed.id()));

        // When / Then: the service purges the instance archived before an hour ago; the unarchived one stays
        assertEquals(
                new PurgeResult(1, false),
                await(service.purgeArchivedBefore(now.minus(Duration.ofHours(1)), RETENTION_DEFINITION, 10)));
        assertEquals(0, mappedRows("workflow_instances", "id", oldFailed.id()));
        assertEquals(1, mappedRows("workflow_instances", "id", recentCompleted.id()));
    }

    /** Moves an instance from RUNNING to a terminal status through the optimistic update. */
    private static void settle(RuntimeComponent runtime, WorkflowInstance instance, WorkflowStatus status)
            throws Exception {
        WorkflowInstance settled = instance.withVersion(instance.version() + 1).withStatus(status);
        assertEquals(1, rowsChanged(runtime, tx -> runtime.workflowInstanceRepository()
                .updateOptimistic(settled, instance.version(), tx)));
    }

    // --- Signal wait repository ---

    private static final String WAIT_CONTRACT = "schema-placement-contract";
    private static final String WAIT_SIGNAL = "signal-a";

    /** Writes, replaces, reads and deletes wait records, including the read that joins the instance table. */
    private static void exerciseSignalWaitRepository(RuntimeComponent runtime, LifecycleInstances instances)
            throws Exception {
        PgWorkflowSignalWaitRepository waits = runtime.pgWorkflowSignalWaitRepository();
        WorkflowInstanceId subject = instances.withSubject().id();
        WorkflowInstanceId plain = instances.plain().id();
        String definitionId = FORK_JOIN_DEFINITION.definitionId();
        String key = unique("wait-key");

        // Given: two waits of one instance, one of another, and one of an instance that has ended, all with one key
        WorkflowInstance ended = newInstance(definitionId, null, null);
        inTransaction(runtime, tx -> runtime.pgWorkflowInstanceRepository().insert(ended, tx));
        settle(runtime, ended, WorkflowStatus.COMPLETED);
        SignalWaitRecord main = signalWait(subject, "wait-a", "", "", SCOPE_KEY, key);
        SignalWaitRecord branch = signalWait(subject, "wait-b", FORK_STEP, "b", SCOPE_KEY, key);
        SignalWaitRecord other = signalWait(plain, "wait-a", "", "", WorkflowScopes.DEFAULT, key);
        SignalWaitRecord closed = signalWait(ended.id(), "wait-a", "", "", WorkflowScopes.DEFAULT, key);
        for (SignalWaitRecord wait : List.of(main, branch, other, closed)) {
            inTransaction(runtime, tx -> waits.write(wait, tx));
        }
        assertEquals(4, mappedRows("workflow_signal_waits", "correlation_key", key));
        assertEquals(SCOPE_KEY, mappedValue("workflow_signal_waits", "wait_id", main.waitId(), "scope_key"));

        // Then: the open read joins the mapped instance table, so the wait of the ended instance is left out
        List<SignalWaitRecord> open =
                inTransaction(runtime, tx -> waits.findOpen(WAIT_CONTRACT, WAIT_SIGNAL, key, null, null, tx));
        assertEquals(2, open.size(), "the match limit applies to the three open waits");
        assertFalse(waitIds(open).contains(closed.waitId()), "the wait of an ended instance is not open");
        assertEquals(
                Set.of(main.waitId(), branch.waitId()),
                waitIds(inTransaction(
                        runtime, tx -> waits.findOpen(WAIT_CONTRACT, WAIT_SIGNAL, key, null, SCOPE_KEY, tx))));
        assertEquals(
                Set.of(other.waitId()),
                waitIds(inTransaction(
                        runtime,
                        tx -> waits.findOpen(
                                WAIT_CONTRACT, WAIT_SIGNAL, key, definitionId, WorkflowScopes.DEFAULT, tx))));
        assertEquals(
                Set.of(),
                waitIds(inTransaction(
                        runtime,
                        tx -> waits.findOpen(WAIT_CONTRACT, WAIT_SIGNAL, key, "no-such-definition", null, tx))));

        // Then: point and instance reads find the mapped rows
        assertEquals(
                main,
                inTransaction(runtime, tx -> waits.findById(main.waitId(), tx)).orElseThrow());
        assertEquals(
                Set.of(main.waitId(), branch.waitId()),
                waitIds(inTransaction(runtime, tx -> waits.findByInstance(subject, tx))));

        // When: a wait entered again at the same address is written
        SignalWaitRecord replaced = signalWait(subject, "wait-a", "", "", SCOPE_KEY, key);
        inTransaction(runtime, tx -> waits.write(replaced, tx));

        // Then: it holds the address in place of the earlier one
        assertTrue(
                inTransaction(runtime, tx -> waits.findById(main.waitId(), tx)).isEmpty());
        assertEquals(0, mappedRows("workflow_signal_waits", "wait_id", main.waitId()));
        assertEquals(1, mappedRows("workflow_signal_waits", "wait_id", replaced.waitId()));
        assertEquals(
                Set.of(replaced.waitId(), branch.waitId()),
                waitIds(inTransaction(runtime, tx -> waits.findByInstance(subject, tx))));

        // When / Then: one address is deleted once, then every wait of the other instances
        assertTrue(flag(runtime, tx -> waits.delete(subject, "wait-b", FORK_STEP, "b", tx)));
        assertFalse(flag(runtime, tx -> waits.delete(subject, "wait-b", FORK_STEP, "b", tx)));
        assertEquals(1, mappedRows("workflow_signal_waits", "instance_id", subject));
        inTransaction(runtime, tx -> waits.deleteAll(plain, tx));
        inTransaction(runtime, tx -> waits.deleteAll(ended.id(), tx));
        assertEquals(0, mappedRows("workflow_signal_waits", "instance_id", plain));
        assertEquals(0, mappedRows("workflow_signal_waits", "instance_id", ended.id()));
        assertEquals(1, mappedRows("workflow_signal_waits", "correlation_key", key));
    }

    private static SignalWaitRecord signalWait(
            WorkflowInstanceId instance,
            String stepId,
            String forkStepId,
            String branchId,
            String scopeKey,
            String key) {
        return new SignalWaitRecord(
                UUID.randomUUID(),
                instance,
                stepId,
                forkStepId,
                branchId,
                scopeKey,
                WAIT_CONTRACT,
                FORK_JOIN_DEFINITION.definitionId(),
                WAIT_SIGNAL,
                key,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
    }

    private static Set<UUID> waitIds(List<SignalWaitRecord> waits) {
        return waits.stream().map(SignalWaitRecord::waitId).collect(Collectors.toSet());
    }

    // --- Task reservations ---

    /** Moves a task between OPEN and COMPLETING through both conditional updates, each applied and refused. */
    private static void exerciseTaskReservations(RuntimeComponent runtime, LifecycleInstances instances)
            throws Exception {
        PgTaskReservations reservations = runtime.pgTaskReservations();
        TaskRecord task = openTask(instances.plain().id(), "reserve", new TaskAssignment.User("alice"), null);
        inTransaction(runtime, tx -> runtime.pgTaskStore().insertOpen(task, tx));
        String taskId = task.taskId().toString();

        // reserving applies to an open task once
        assertTrue(flag(runtime, tx -> reservations.markCompleting(taskId, 41L, tx)));
        assertEquals("COMPLETING", mappedValue("workflow_tasks", "task_id", task.taskId(), "status"));
        assertEquals("41", mappedValue("workflow_tasks", "task_id", task.taskId(), "reservation_command_id"));
        assertFalse(flag(runtime, tx -> reservations.markCompleting(taskId, 42L, tx)));
        assertFalse(flag(
                runtime, tx -> reservations.markCompleting(UUID.randomUUID().toString(), 43L, tx)));
        assertEquals("41", mappedValue("workflow_tasks", "task_id", task.taskId(), "reservation_command_id"));

        // reopening applies only for the command that holds the reservation
        assertFalse(flag(runtime, tx -> reservations.reopen(taskId, 42L, tx)));
        assertEquals("COMPLETING", mappedValue("workflow_tasks", "task_id", task.taskId(), "status"));
        assertTrue(flag(runtime, tx -> reservations.reopen(taskId, 41L, tx)));
        assertEquals("OPEN", mappedValue("workflow_tasks", "task_id", task.taskId(), "status"));
        assertNull(mappedValue("workflow_tasks", "task_id", task.taskId(), "reservation_command_id"));
        assertFalse(flag(runtime, tx -> reservations.reopen(taskId, 41L, tx)));
    }

    // --- Command repository ---

    /**
     * Runs the command repository against fresh instances, one scenario per instance. The commands table holds
     * only the commands these scenarios record, so a scenario finds its heads by command id.
     */
    private static void exerciseCommandRepository(RuntimeComponent runtime) throws Exception {
        CommandSteps steps = new CommandSteps(runtime);
        exerciseCommandLifecycle(steps);
        exerciseSignalCommands(steps);
        exerciseDeferralAndTransientRetry(steps);
        exerciseEndedInstanceClaims(steps);
        exerciseDeadLetters(steps);
        exerciseTimerAndCompletionCommands(steps);
        exerciseCancellationAndRearm(steps);
        exerciseAttributionAndDefinitionNames(steps);
        exerciseCommandProbes(steps);
    }

    /** Start command: queue sequence, record, peek, claim, fence, release, transient retry and completion. */
    private static void exerciseCommandLifecycle(CommandSteps steps) throws Exception {
        RuntimeComponent runtime = steps.runtime;
        PgWorkflowCommandRepository commands = steps.commands;
        WorkflowInstance instance = steps.instance();
        WorkflowInstanceId id = instance.id();
        String key = unique("start-key");

        // the execution lock is taken, and taken again by the transaction that holds it
        assertTrue(
                flag(runtime, tx -> commands.lockInstance(id, tx).compose(locked -> commands.tryLockInstance(id, tx))));

        // the queue row is created on first use and hands out consecutive sequence numbers
        assertEquals(1L, sequenceNumber(runtime, tx -> commands.nextQueueSequence(id, tx)));
        long start = steps.start(instance, key);
        assertEquals("2", steps.commandValue(start, "seq"));
        assertEquals("PENDING", steps.state(start));
        assertEquals("alice", steps.commandValue(start, "actor_id"));
        assertEquals("3", mappedValue("workflow_command_queues", "workflow_id", id, "next_seq"));
        assertFalse(flag(runtime, tx -> commands.hasDeadLetters(id, tx)));
        SegmentCommand recorded =
                inTransaction(runtime, tx -> commands.readStart(id, tx)).orElseThrow();
        assertEquals(start, recorded.commandId());
        assertEquals(key, recorded.dedupKey());

        // the command is at the head of its instance, and its first claim spends one attempt
        CommandHead head = steps.head(start);
        assertEquals(id, head.workflowId());
        assertEquals("START", head.kind());
        assertFalse(head.instanceEnded());
        assertEquals(FORK_JOIN_DEFINITION.definitionId(), head.definitionId());
        assertEquals(1, await(commands.countRunnable(id)));
        ClaimedCommand first = steps.claim(start);
        assertEquals(1, first.attempts());
        assertEquals(CommandSteps.NODE, steps.commandValue(start, "claimed_by"));
        assertEquals(1, await(commands.countRunnable(id)), "a claim under its lease still counts as runnable");

        // the fence reads the command under its token, and release gives the attempt back
        SegmentCommand fenced =
                inTransaction(runtime, tx -> commands.fence(first, tx)).orElseThrow();
        assertEquals(key, fenced.dedupKey());
        assertInstanceOf(WorkflowActor.User.class, fenced.attribution().actor());
        assertTrue(await(commands.release(first)));
        assertFalse(await(commands.release(first)));
        assertEquals("PENDING", steps.state(start));
        assertEquals("0", steps.commandValue(start, "attempts"));

        // a transient failure gives the attempt back and keeps the command pending
        ClaimedCommand second = steps.claim(start);
        assertEquals(1, await(commands.retryTransient(second, count -> 0L, "transient failure")));
        assertEquals("1", steps.commandValue(start, "transient_count"));
        assertEquals("0", steps.commandValue(start, "attempts"));

        // the command completes under its token, and only once
        ClaimedCommand third = steps.claim(start);
        assertEquals(1, rowsChanged(runtime, tx -> commands.complete(start, third.token(), tx)));
        assertEquals(0, rowsChanged(runtime, tx -> commands.complete(start, third.token(), tx)));
        assertEquals("COMPLETE", steps.state(start));

        // the open-signal count is raised up to its limit and lowered again, never below zero
        assertTrue(flag(runtime, tx -> commands.raiseOpenSignals(id, 1, tx)));
        assertFalse(flag(runtime, tx -> commands.raiseOpenSignals(id, 1, tx)));
        assertEquals(1, steps.openSignals(instance));
        inTransaction(runtime, tx -> commands.lowerOpenSignals(id, tx));
        inTransaction(runtime, tx -> commands.lowerOpenSignals(id, tx));
        assertEquals(0, steps.openSignals(instance));
    }

    /** Signal commands: record, park, requeue and settle in every way, with the open-signal count following. */
    private static void exerciseSignalCommands(CommandSteps steps) throws Exception {
        RuntimeComponent runtime = steps.runtime;
        PgWorkflowCommandRepository commands = steps.commands;
        WorkflowInstance instance = steps.instance();
        WorkflowInstanceId id = instance.id();

        // a claimed signal is parked; every parked read finds it
        long first = steps.signal(instance, "signal-a");
        assertEquals(1, steps.openSignals(instance));
        ClaimedCommand firstClaim = steps.claim(first);
        assertEquals(1, rowsChanged(runtime, tx -> commands.park(first, firstClaim.token(), tx)));
        assertEquals("PARKED", steps.state(first));
        assertEquals("0", steps.commandValue(first, "attempts"));
        assertEquals(List.of(first), parkedIds(inTransaction(runtime, tx -> commands.parkedSignals(id, tx))));
        assertEquals(
                List.of(first),
                parkedIds(inTransaction(runtime, tx -> commands.parkedSignalsAt(id, "signal-a", null, null, tx))));
        assertEquals(
                List.of(),
                parkedIds(inTransaction(runtime, tx -> commands.parkedSignalsAt(id, "other", null, null, tx))));
        SegmentCommand parked =
                inTransaction(runtime, tx -> commands.readParked(id, first, tx)).orElseThrow();
        assertEquals("SIGNAL", parked.kind());
        assertTrue(parked.payloadJson().contains("value"));
        assertEquals(new WorkflowActor.User("alice"), parked.attribution().actor());
        assertFalse(flag(runtime, tx -> commands.wasParked(id, first, tx)));
        assertEquals(1, await(commands.findParked(id)).size());

        // requeueing makes it pending again; parked once more, it is settled and frees its slot
        assertEquals(1, rowsChanged(runtime, tx -> commands.requeueParked(first, tx)));
        assertEquals("PENDING", steps.state(first));
        assertEquals(0, rowsChanged(runtime, tx -> commands.requeueParked(first, tx)));
        ClaimedCommand again = steps.claim(first);
        assertEquals(1, rowsChanged(runtime, tx -> commands.park(first, again.token(), tx)));
        assertEquals(1, rowsChanged(runtime, tx -> commands.settleParked(first, "REJECTED", "no longer needed", tx)));
        assertEquals("REJECTED", steps.state(first));
        assertEquals("no longer needed", steps.commandValue(first, "last_error"));
        assertEquals(0, steps.openSignals(instance));
        assertTrue(
                inTransaction(runtime, tx -> commands.readParked(id, first, tx)).isEmpty());

        // a claimed signal is rejected, rejected with a reason, made stale, or cancelled, each once
        ClaimedCommand rejected = steps.claimedSignal(instance);
        assertEquals(1, rowsChanged(runtime, tx -> commands.reject(rejected.commandId(), rejected.token(), tx)));
        assertEquals(0, rowsChanged(runtime, tx -> commands.reject(rejected.commandId(), rejected.token(), tx)));
        assertEquals("REJECTED", steps.state(rejected.commandId()));
        ClaimedCommand explained = steps.claimedSignal(instance);
        assertEquals(
                1,
                rowsChanged(
                        runtime,
                        tx -> commands.reject(explained.commandId(), explained.token(), "rejected for a reason", tx)));
        assertEquals("rejected for a reason", steps.commandValue(explained.commandId(), "last_error"));
        ClaimedCommand stale = steps.claimedSignal(instance);
        assertEquals(
                1,
                rowsChanged(runtime, tx -> commands.stale(stale.commandId(), stale.token(), "wait entry closed", tx)));
        assertEquals("STALE", steps.state(stale.commandId()));
        ClaimedCommand cancelled = steps.claimedSignal(instance);
        assertEquals(
                1,
                rowsChanged(runtime, tx -> commands.cancel(cancelled.commandId(), cancelled.token(), "withdrawn", tx)));
        assertEquals("CANCELLED", steps.state(cancelled.commandId()));

        // a deterministic failure of a signal rejects it and logs the rejection; a second disposition finds no claim
        ClaimedCommand failing = steps.claimedSignal(instance);
        assertEquals(
                DispositionResult.REJECTED,
                await(commands.failCommand(
                        failing,
                        CommandErrors.Reason.DETERMINISTIC_FAILURE.name(),
                        "segment failed",
                        InstanceFailureListener.none())));
        assertEquals("REJECTED", steps.state(failing.commandId()));
        assertEquals(1, mappedRows("workflow_history", "command_id", failing.commandId()));
        assertEquals(
                DispositionResult.CLAIM_LOST,
                await(commands.failCommand(
                        failing,
                        CommandErrors.Reason.DETERMINISTIC_FAILURE.name(),
                        "segment failed",
                        InstanceFailureListener.none())));
        assertEquals(0, steps.openSignals(instance), "every settled signal freed its slot");

        // a branch signal is parked and found only at its own address; it keeps its slot
        long branchSignal = steps.signal(instance, "signal-b", FORK_STEP, "b", null);
        ClaimedCommand branchClaim = steps.claim(branchSignal);
        assertEquals(1, rowsChanged(runtime, tx -> commands.park(branchSignal, branchClaim.token(), tx)));
        List<ParkedSignal> atBranch =
                inTransaction(runtime, tx -> commands.parkedSignalsAt(id, "signal-b", FORK_STEP, "b", tx));
        assertEquals(List.of(branchSignal), parkedIds(atBranch));
        assertEquals(FORK_STEP, atBranch.get(0).forkStepId());
        assertEquals("b", atBranch.get(0).branchId());
        assertEquals(
                List.of(),
                parkedIds(inTransaction(runtime, tx -> commands.parkedSignalsAt(id, "signal-b", null, null, tx))));
        assertEquals(List.of(branchSignal), parkedIds(inTransaction(runtime, tx -> commands.parkedSignals(id, tx))));
        assertEquals(1, steps.openSignals(instance));
    }

    /** A deferred start, and a start that a transient failure puts back with a long backoff. */
    private static void exerciseDeferralAndTransientRetry(CommandSteps steps) throws Exception {
        PgWorkflowCommandRepository commands = steps.commands;

        // the first deferral of a head writes one history entry and moves its availability into the future
        WorkflowInstance deferred = steps.instance();
        long deferredStart = steps.start(deferred, unique("deferred-key"));
        CommandHead head = steps.head(deferredStart);
        assertEquals(1, await(commands.defer(head)));
        assertEquals("DEFERRED", steps.state(deferredStart));
        assertEquals("1", steps.commandValue(deferredStart, "deferral_count"));
        assertEquals("true", steps.commandValue(deferredStart, "available_at > now()"));
        assertEquals(1, mappedRows("workflow_history", "command_id", deferredStart));
        assertEquals(0, await(commands.defer(head)), "a deferred command is not claimable until its backoff ends");
        assertEquals(1, await(commands.findDeferred(deferred.id())).size());
        assertEquals(0, await(commands.findNotYetAvailable(deferred.id())).size());
        assertEquals(0, await(commands.countRunnable(deferred.id())));
        assertEquals(1, await(commands.findStuck(deferred.id(), 0)).size());

        // a transient failure with a long backoff leaves the command pending but not yet available
        WorkflowInstance backedOff = steps.instance();
        long backedOffStart = steps.start(backedOff, unique("backoff-key"));
        ClaimedCommand claim = steps.claim(backedOffStart);
        assertEquals(1, await(commands.retryTransient(claim, count -> 60_000L, "transient failure")));
        assertEquals(0, await(commands.retryTransient(claim, count -> 60_000L, "again")), "the claim is gone");
        assertEquals("PENDING", steps.state(backedOffStart));
        assertEquals("transient failure", steps.commandValue(backedOffStart, "last_error"));
        List<PendingCommand> notYet = await(commands.findNotYetAvailable(backedOff.id()));
        assertEquals(1, notYet.size());
        assertEquals("PENDING", notYet.get(0).state());
        assertEquals(0, await(commands.findDeferred(backedOff.id())).size());

        // the earliest availability that the wake-up reads is a positive delay
        long delayMs = await(commands.findEarliestAvailabilityDelayMs()).orElseThrow();
        assertTrue(delayMs > 0, "the earliest pending or deferred command is not yet available: " + delayMs);
    }

    /** Heads of ended instances are settled by the claim: rejected for a completed one, cancelled for a cancelled one. */
    private static void exerciseEndedInstanceClaims(CommandSteps steps) throws Exception {
        RuntimeComponent runtime = steps.runtime;

        // a start that meets a completed instance is rejected and logged
        WorkflowInstance completed = steps.instance();
        long start = steps.start(completed, unique("ended-key"));
        settle(runtime, completed, WorkflowStatus.COMPLETED);
        assertTrue(steps.head(start).instanceEnded());
        ClaimedCommand rejected = steps.tryClaim(steps.head(start), 60, 3, InstanceFailureListener.none())
                .orElseThrow();
        assertEquals(ClaimedCommand.Result.SETTLED_INSTANCE_ENDED, rejected.result());
        assertEquals("REJECTED", steps.state(start));
        assertEquals(1, mappedRows("workflow_history", "command_id", start));

        // a completion and a timer that meet a cancelled instance are cancelled with it
        WorkflowInstance cancelled = steps.instance();
        TaskRecord task = steps.task(cancelled);
        long completion = steps.completion(cancelled, task);
        RecordedTimer timer = steps.timer(
                cancelled,
                TimerPurpose.STANDALONE,
                "timer-step",
                new TimerCommandSpec.FireTime.After(Duration.ZERO),
                null,
                List.of());
        settle(runtime, cancelled, WorkflowStatus.CANCELLED);
        ClaimedCommand cancelledCompletion = steps.tryClaim(
                        steps.head(completion), 60, 3, InstanceFailureListener.none())
                .orElseThrow();
        assertEquals(ClaimedCommand.Result.SETTLED_INSTANCE_ENDED, cancelledCompletion.result());
        assertEquals("CANCELLED", steps.state(completion));
        assertEquals("CANCELLED", mappedValue("workflow_tasks", "task_id", task.taskId(), "status"));
        assertEquals("INSTANCE_ENDED", mappedValue("workflow_tasks", "task_id", task.taskId(), "cancellation_reason"));
        ClaimedCommand cancelledTimer = steps.tryClaim(
                        steps.head(timer.commandId()), 60, 3, InstanceFailureListener.none())
                .orElseThrow();
        assertEquals(ClaimedCommand.Result.SETTLED_INSTANCE_ENDED, cancelledTimer.result());
        assertEquals("CANCELLED", steps.state(timer.commandId()));
        assertEquals("CANCELLED", mappedValue("workflow_timers", "timer_id", timer.timerId(), "status"));
    }

    /** Dead letters: a spent start fails its instance, a spent signal is offered again, and an end settles leftovers. */
    private static void exerciseDeadLetters(CommandSteps steps) throws Exception {
        RuntimeComponent runtime = steps.runtime;
        PgWorkflowCommandRepository commands = steps.commands;

        // a start that spent its attempts is dead-lettered and fails its instance once
        WorkflowInstance failing = steps.instance();
        long start = steps.start(failing, unique("dead-key"));
        List<String> failures = new ArrayList<>();
        InstanceFailureListener<SqlClient> listener = (failed, sequence, reasonCode, message, tx) -> {
            failures.add(reasonCode);
            return Future.succeededFuture();
        };
        steps.deadLetter(start, listener);
        assertEquals("DEAD", steps.state(start));
        assertEquals("FAILED", mappedValue("workflow_instances", "id", failing.id(), "status"));
        assertEquals(List.of("ATTEMPTS_EXHAUSTED"), failures);
        assertTrue(flag(runtime, tx -> commands.hasDeadLetters(failing.id(), tx)));
        assertTrue(flag(runtime, tx -> commands.wasParked(failing.id(), start, tx)));

        // superseding the dead start settles it and logs it
        assertEquals(1, rowsChanged(runtime, tx -> commands.supersedeDeadStart(failing.id(), tx)));
        assertEquals(0, rowsChanged(runtime, tx -> commands.supersedeDeadStart(failing.id(), tx)));
        assertEquals("SUPERSEDED", steps.state(start));
        assertFalse(flag(runtime, tx -> commands.hasDeadLetters(failing.id(), tx)));

        // a dead-lettered signal frees its slot, and offering it again takes the slot back
        WorkflowInstance reoffered = steps.instance();
        long signal = steps.signal(reoffered, "signal-a");
        steps.deadLetter(signal, InstanceFailureListener.none());
        assertEquals("DEAD", steps.state(signal));
        assertEquals(0, steps.openSignals(reoffered));
        assertTrue(flag(runtime, tx -> commands.wasParked(reoffered.id(), signal, tx)));
        assertEquals(1, rowsChanged(runtime, tx -> commands.reofferDeadLetters(reoffered.id(), tx)));
        assertEquals("PENDING", steps.state(signal));
        assertEquals("0", steps.commandValue(signal, "attempts"));
        assertEquals(1, steps.openSignals(reoffered));

        // a dead-lettered signal that stays dead is what the stuck read and the counts report
        WorkflowInstance stuck = steps.instance();
        long stuckSignal = steps.signal(stuck, "signal-a");
        steps.deadLetter(stuckSignal, InstanceFailureListener.none());
        List<PendingCommand> stuckCommands = await(commands.findStuck(stuck.id(), 86_400));
        assertEquals(1, stuckCommands.size());
        assertEquals("DEAD", stuckCommands.get(0).state());
        assertEquals("SIGNAL", stuckCommands.get(0).kind());

        // when the instance ends, its parked and dead-lettered commands are settled, and so are their task and timer
        WorkflowInstance leftovers = steps.instance();
        long parkedSignal = steps.signal(leftovers, "signal-a");
        ClaimedCommand parkedClaim = steps.claim(parkedSignal);
        assertEquals(1, rowsChanged(runtime, tx -> commands.park(parkedSignal, parkedClaim.token(), tx)));
        TaskRecord task = steps.task(leftovers);
        long completion = steps.completion(leftovers, task);
        RecordedTimer timer = steps.timer(
                leftovers,
                TimerPurpose.STANDALONE,
                "timer-step",
                new TimerCommandSpec.FireTime.After(Duration.ZERO),
                null,
                List.of());
        steps.deadLetter(completion, InstanceFailureListener.none());
        steps.deadLetter(timer.commandId(), InstanceFailureListener.none());
        assertEquals("COMPLETING", mappedValue("workflow_tasks", "task_id", task.taskId(), "status"));
        settle(runtime, leftovers, WorkflowStatus.COMPLETED);
        assertEquals(3, rowsChanged(runtime, tx -> commands.settleEnded(leftovers.id(), tx)));
        assertEquals(0, rowsChanged(runtime, tx -> commands.settleEnded(leftovers.id(), tx)));
        for (long settled : List.of(parkedSignal, completion, timer.commandId())) {
            assertEquals("REJECTED", steps.state(settled));
        }
        assertEquals("CANCELLED", mappedValue("workflow_tasks", "task_id", task.taskId(), "status"));
        assertEquals("CANCELLED", mappedValue("workflow_timers", "timer_id", timer.timerId(), "status"));
        assertEquals(0, steps.openSignals(leftovers));
        assertEquals(1, mappedRows("workflow_history", "command_id", parkedSignal));
        assertEquals(2, mappedRows("workflow_history", "command_id", completion));
        assertEquals(2, mappedRows("workflow_history", "command_id", timer.commandId()));
    }

    /** Timer and completion commands: a timeout racing a signal, the unsettled targets, and a failed reservation. */
    private static void exerciseTimerAndCompletionCommands(CommandSteps steps) throws Exception {
        RuntimeComponent runtime = steps.runtime;
        PgWorkflowCommandRepository commands = steps.commands;

        // a timeout whose fire time precedes a later signal blocks that signal at its wait
        WorkflowInstance waiting = steps.instance();
        Instant past = Instant.now().minus(Duration.ofHours(1));
        RecordedTimer timeout = steps.timer(
                waiting,
                TimerPurpose.SIGNAL_TIMEOUT,
                "wait-a",
                new TimerCommandSpec.FireTime.At(past),
                null,
                List.of("signal-a"));
        assertEquals("TIMEOUT", steps.commandValue(timeout.commandId(), "timer_kind"));
        assertEquals("SCHEDULED", mappedValue("workflow_timers", "timer_id", timeout.timerId(), "status"));
        assertEquals(
                String.valueOf(timeout.commandId()),
                mappedValue("workflow_timers", "timer_id", timeout.timerId(), "command_id"));
        long signal = steps.signal(waiting, "signal-a");
        assertEquals(
                Set.of(signal),
                inTransaction(
                        runtime, tx -> commands.deadlineBlocked(waiting.id(), "wait-a", null, List.of(signal), tx)));
        assertEquals(
                Set.of(),
                inTransaction(
                        runtime,
                        tx -> commands.deadlineBlocked(waiting.id(), "other-step", null, List.of(signal), tx)));
        assertEquals(
                Set.of(),
                inTransaction(runtime, tx -> commands.deadlineBlocked(waiting.id(), "wait-a", null, List.of(), tx)));
        List<String> targets = inTransaction(runtime, tx -> commands.unsettledTargets(waiting.id(), "SIGNAL", tx));
        assertEquals(1, targets.size());
        assertTrue(targets.get(0).contains("signal-a"));
        assertEquals(
                1,
                inTransaction(runtime, tx -> commands.unsettledTargets(waiting.id(), "TIMER", tx))
                        .size());

        // a task being completed is live, and a deterministic failure of its completion reopens it
        WorkflowInstance completing = steps.instance();
        TaskRecord task = steps.task(completing);
        RecordedTimer reminder = steps.timer(
                completing,
                TimerPurpose.TASK_REMINDER,
                "review",
                new TimerCommandSpec.FireTime.After(Duration.ofHours(1)),
                task.taskId(),
                List.of());
        assertEquals("REMINDER", steps.commandValue(reminder.commandId(), "timer_kind"));
        assertEquals(List.of(task.taskId()), inTransaction(runtime, tx -> commands.liveTasks(completing.id(), tx)));
        long completion = steps.completion(completing, task);
        assertEquals(List.of(task.taskId()), inTransaction(runtime, tx -> commands.liveTasks(completing.id(), tx)));
        assertEquals("COMPLETING", mappedValue("workflow_tasks", "task_id", task.taskId(), "status"));
        ClaimedCommand claim = steps.claim(completion);
        assertEquals(
                DispositionResult.REJECTED,
                await(commands.failCommand(
                        claim,
                        CommandErrors.Reason.DETERMINISTIC_FAILURE.name(),
                        "decision failed",
                        InstanceFailureListener.none())));
        assertEquals("REJECTED", steps.state(completion));
        assertEquals("OPEN", mappedValue("workflow_tasks", "task_id", task.taskId(), "status"));
        assertNull(mappedValue("workflow_tasks", "task_id", task.taskId(), "reservation_command_id"));
    }

    /** Cancelling every command of an instance, and re-arming a timer whose command failed. */
    private static void exerciseCancellationAndRearm(CommandSteps steps) throws Exception {
        RuntimeComponent runtime = steps.runtime;
        PgWorkflowCommandRepository commands = steps.commands;
        WorkflowAttribution canceller = new WorkflowAttribution(
                new WorkflowActor.Service("canceller"), null, WorkflowAttribution.Status.VERIFIED);

        // Given: one pending command of every kind, a signal that answers a wait among them
        WorkflowInstance instance = steps.instance();
        UUID waitId = UUID.randomUUID();
        String replyKey = unique("reply-key");
        long signal = steps.signal(instance, "reply-signal", null, null, waitId.toString(), replyKey);
        assertEquals(
                Optional.of("reply-signal"),
                inTransaction(runtime, tx -> commands.recordedReplyName(instance.id(), waitId, replyKey, tx)));
        assertEquals(
                Optional.empty(),
                inTransaction(
                        runtime, tx -> commands.recordedReplyName(instance.id(), UUID.randomUUID(), replyKey, tx)));
        TaskRecord task = steps.task(instance);
        long completion = steps.completion(instance, task);
        long retry = inTransaction(runtime, tx -> commands.recordRetry(instance.id(), CommandSteps.REQUESTER, tx));
        long migration = inTransaction(
                runtime, tx -> commands.recordMigration(instance.id(), 2L, "scope-b", CommandSteps.REQUESTER, tx));
        RecordedTimer timer = steps.timer(
                instance,
                TimerPurpose.STANDALONE,
                "timer-step",
                new TimerCommandSpec.FireTime.After(Duration.ofHours(1)),
                null,
                List.of());
        assertEquals("RETRY", steps.commandValue(retry, "kind"));
        assertEquals("MIGRATION", steps.commandValue(migration, "kind"));
        assertEquals("scope-b", steps.commandValue(migration, "target_json->>'scope'"));

        // When: the instance's commands are cancelled
        List<SettledCommand> settled = inTransaction(runtime, tx -> commands.cancelAll(instance.id(), canceller, tx));

        // Then: each is cancelled and logged under the canceller, and the signal's slot is free
        assertEquals(
                List.of(signal, completion, retry, migration, timer.commandId()),
                settled.stream().map(SettledCommand::commandId).toList());
        assertEquals(task.taskId(), settled.get(1).taskId());
        assertEquals(timer.timerId(), settled.get(4).timerId());
        for (SettledCommand command : settled) {
            assertEquals("CANCELLED", steps.state(command.commandId()));
        }
        assertEquals(0, steps.openSignals(instance));
        assertEquals(5, mappedRows("workflow_history", "workflow_id", instance.id()));
        assertEquals(
                List.of("SERVICE"),
                column(
                        "SELECT DISTINCT actor_kind FROM " + MAPPED_SCHEMA
                                + ".workflow_history WHERE workflow_id = ?::uuid",
                        instance.id().value()));

        // Given: a timer whose command failed, which fails its instance
        WorkflowInstance failed = steps.instance();
        RecordedTimer failedTimer = steps.timer(
                failed,
                TimerPurpose.STANDALONE,
                "timer-step",
                new TimerCommandSpec.FireTime.After(Duration.ZERO),
                null,
                List.of());
        ClaimedCommand claim = steps.claim(failedTimer.commandId());
        assertEquals(
                DispositionResult.APPLIED,
                await(commands.failCommand(
                        claim,
                        CommandErrors.Reason.DETERMINISTIC_FAILURE.name(),
                        "timer handler failed",
                        InstanceFailureListener.none())));
        assertEquals("FAILED", steps.state(failedTimer.commandId()));
        assertEquals("FAILED", mappedValue("workflow_timers", "timer_id", failedTimer.timerId(), "status"));
        assertEquals("FAILED", mappedValue("workflow_instances", "id", failed.id(), "status"));

        // When / Then: re-arming records a new command for the timer and schedules it again, once
        assertEquals(1, rowsChanged(runtime, tx -> commands.rearmTimers(failed.id(), tx)));
        assertEquals("SCHEDULED", mappedValue("workflow_timers", "timer_id", failedTimer.timerId(), "status"));
        assertEquals(2, mappedRows("workflow_commands", "timer_id", failedTimer.timerId()));
        assertNotEquals(
                String.valueOf(failedTimer.commandId()),
                mappedValue("workflow_timers", "timer_id", failedTimer.timerId(), "command_id"));
        assertEquals(0, rowsChanged(runtime, tx -> commands.rearmTimers(failed.id(), tx)));
    }

    /** History attribution, the legacy baseline mark, and the signal names a definition declares. */
    private static void exerciseAttributionAndDefinitionNames(CommandSteps steps) throws Exception {
        RuntimeComponent runtime = steps.runtime;
        PgWorkflowCommandRepository commands = steps.commands;
        PgWorkflowHistoryRepository history = runtime.pgWorkflowHistoryRepository();
        WorkflowInstance instance = steps.instance();
        WorkflowInstanceId id = instance.id();
        long start = steps.start(instance, unique("attribution-key"));
        WorkflowAttribution system = WorkflowAttribution.system("migration");

        // an entry that names a command takes that command's actor and the pinned definition
        String namesCommand = "{\"commandId\":" + start + "}";
        inTransaction(runtime, tx -> history.append(historyEntry(id, 1, WorkflowEntryType.START, namesCommand), tx));
        SegmentCommand segment = new SegmentCommand(start, id, "START", null, null, null);
        DefinitionKey pinned = new DefinitionKey(WorkflowScopes.DEFAULT, FORK_JOIN_DEFINITION.definitionId(), 1L);
        inTransaction(
                runtime, tx -> commands.attributeSegmentHistory(id, 1, CommandSteps.FALLBACK, segment, pinned, tx));
        assertEquals("USER", historyValue(id, 1, "actor_kind"));
        assertEquals("alice", historyValue(id, 1, "actor_id"));
        assertEquals(String.valueOf(start), historyValue(id, 1, "command_id"));
        assertEquals(pinned.definitionId(), historyValue(id, 1, "definition_id"));

        // an entry that names none takes the fallback, which pins no definition without a segment
        inTransaction(runtime, tx -> history.append(historyEntry(id, 2, WorkflowEntryType.RETRIED), tx));
        inTransaction(runtime, tx -> commands.attributeSegmentHistory(id, 2, CommandSteps.FALLBACK, tx));
        assertEquals("SERVICE", historyValue(id, 2, "actor_kind"));
        assertEquals("fallback-service", historyValue(id, 2, "actor_id"));
        assertNull(historyValue(id, 2, "definition_id"));

        // a plain attribution gives its actor to the entries from a sequence on that have none
        inTransaction(runtime, tx -> history.append(historyEntry(id, 3, WorkflowEntryType.COMPLETED), tx));
        inTransaction(runtime, tx -> commands.attributeHistory(id, 1, system, tx));
        assertEquals("SYSTEM", historyValue(id, 3, "actor_kind"));
        assertEquals("migration", historyValue(id, 3, "actor_reason"));
        assertEquals("USER", historyValue(id, 1, "actor_kind"), "an attributed entry is left as it was");

        // the legacy baseline mark is consumed once
        execute("UPDATE " + MAPPED_SCHEMA + ".workflow_instances SET baseline_logged = FALSE WHERE id = '" + id.value()
                + "'");
        assertTrue(flag(runtime, tx -> commands.consumeLegacyBaseline(id, tx)));
        assertFalse(flag(runtime, tx -> commands.consumeLegacyBaseline(id, tx)));
        assertEquals("true", mappedValue("workflow_instances", "id", id, "baseline_logged"));

        // the declared signal names come from the definition row, for the instance and for a branch
        execute("UPDATE " + MAPPED_SCHEMA + ".workflow_definitions SET signal_names_json ="
                + " '{\"instance\":[\"signal-a\"],\"branches\":{\"fork/b\":[\"signal-b\"]}}'::jsonb"
                + " WHERE definition_id = '" + REGISTRY_DEFINITION + "' AND definition_version = 1");
        DefinitionKey declared = new DefinitionKey(WorkflowScopes.DEFAULT, REGISTRY_DEFINITION, 1);
        assertEquals(
                Optional.of(List.of("signal-a")),
                inTransaction(runtime, tx -> commands.declaredSignalNames(declared, null, null, tx)));
        assertEquals(
                Optional.of(List.of("signal-b")),
                inTransaction(runtime, tx -> commands.declaredSignalNames(declared, FORK_STEP, "b", tx)));
        assertEquals(
                Optional.empty(),
                inTransaction(
                        runtime,
                        tx -> commands.declaredSignalNames(
                                new DefinitionKey(WorkflowScopes.DEFAULT, REGISTRY_DEFINITION, 2), null, null, tx)));
        assertEquals(
                Optional.empty(),
                inTransaction(
                        runtime,
                        tx -> commands.declaredSignalNames(
                                new DefinitionKey(WorkflowScopes.DEFAULT, "no-such-definition", 1), null, null, tx)));
    }

    /** The reads of the execution probe and the intake and wake-up counts, over what the scenarios left behind. */
    private static void exerciseCommandProbes(CommandSteps steps) throws Exception {
        PgWorkflowCommandRepository commands = steps.commands;

        // the heads are bounded by the limit, which must be positive
        List<CommandHead> heads = await(commands.peek(1000));
        assertFalse(heads.isEmpty());
        assertEquals(1, await(commands.peek(1)).size());
        assertThrows(IllegalArgumentException.class, () -> commands.peek(0));

        // the queue counts agree with the table
        QueueCounts counts = await(commands.countQueue(86_400));
        assertEquals(countCommands("state = 'PARKED'"), counts.parked());
        assertTrue(counts.parked() >= 1, "the branch signal stays parked");
        assertEquals(countCommands("state = 'DEAD'"), counts.deadLettered());
        assertTrue(counts.deadLettered() >= 1, "the spent signal stays dead-lettered");
        assertEquals(counts.deadLettered(), counts.stuck(), "nothing unsettled is a day old");
        assertTrue(counts.available() >= 1);

        // the runnable, due and listed reads cover every instance when none is given
        assertTrue(await(commands.countRunnable(null)) >= heads.size());
        long due = countCommands("state IN ('PENDING','DEFERRED') AND available_at <= now()");
        assertTrue(await(commands.countDue(1000)) >= due);
        assertEquals(1L, await(commands.countDue(1)));
        assertThrows(IllegalArgumentException.class, () -> commands.countDue(0));
        assertEquals(counts.parked(), await(commands.findParked(null)).size());
        assertFalse(await(commands.findDeferred(null)).isEmpty());
        assertFalse(await(commands.findNotYetAvailable(null)).isEmpty());
        List<PendingCommand> stuck = await(commands.findStuck(null, 86_400));
        assertEquals(counts.deadLettered(), stuck.size());
        assertTrue(stuck.stream().allMatch(command -> "DEAD".equals(command.state())));
        assertTrue(await(commands.findEarliestAvailabilityDelayMs()).isPresent());
    }

    private static long countCommands(String condition) throws SQLException {
        return Long.parseLong(column("SELECT count(*) FROM " + MAPPED_SCHEMA + ".workflow_commands WHERE " + condition)
                .get(0));
    }

    private static List<Long> parkedIds(List<ParkedSignal> signals) {
        return signals.stream().map(ParkedSignal::commandId).toList();
    }

    private static String historyValue(WorkflowInstanceId workflowId, long sequence, String expression)
            throws SQLException {
        List<String> values = column(
                "SELECT (" + expression + ")::text FROM " + MAPPED_SCHEMA
                        + ".workflow_history WHERE workflow_id = ?::uuid AND sequence = ?::bigint",
                workflowId.value(),
                sequence);
        assertEquals(1, values.size(), "exactly one mapped history entry " + sequence + " of " + workflowId.value());
        return values.get(0);
    }

    /** Fresh instances and the repository calls that put one command or task in a known state. */
    private static final class CommandSteps {

        static final String NODE = "schema-placement-node";
        static final WorkflowAttribution REQUESTER = new WorkflowAttribution(
                new WorkflowActor.User("alice"),
                new PrincipalRef(PrincipalType.USER, "manager", java.util.Map.of()),
                WorkflowAttribution.Status.VERIFIED);
        static final WorkflowAttribution FALLBACK = new WorkflowAttribution(
                new WorkflowActor.Service("fallback-service"), null, WorkflowAttribution.Status.VERIFIED);

        private static final int LEASE_SECONDS = 60;
        private static final int MAX_ATTEMPTS = 3;
        private static final int OPEN_SIGNAL_LIMIT = 100;
        private static final int PEEK_LIMIT = 1000;

        final RuntimeComponent runtime;
        final PgWorkflowCommandRepository commands;

        CommandSteps(RuntimeComponent runtime) {
            this.runtime = runtime;
            this.commands = runtime.pgWorkflowCommandRepository();
        }

        /** Inserts a running instance of the fan-out definition. */
        WorkflowInstance instance() throws Exception {
            WorkflowInstance instance = newInstance(FORK_JOIN_DEFINITION.definitionId(), null, null);
            inTransaction(runtime, tx -> runtime.pgWorkflowInstanceRepository().insert(instance, tx));
            return instance;
        }

        long start(WorkflowInstance instance, String dedupKey) throws Exception {
            return inTransaction(
                    runtime,
                    tx -> commands.recordStart(
                            instance.id(), dedupKey, "{\"order\":1}", "{\"definition\":\"fanout\"}", REQUESTER, tx));
        }

        long signal(WorkflowInstance instance, String name) throws Exception {
            return signal(instance, name, null, null, null);
        }

        long signal(WorkflowInstance instance, String name, String forkStepId, String branchId, String waitId)
                throws Exception {
            return signal(instance, name, forkStepId, branchId, waitId, unique("signal-key"));
        }

        /** Counts a signal against the instance's open-signal limit and records it. */
        long signal(
                WorkflowInstance instance,
                String name,
                String forkStepId,
                String branchId,
                String waitId,
                String dedupKey)
                throws Exception {
            return inTransaction(runtime, tx -> commands.raiseOpenSignals(instance.id(), OPEN_SIGNAL_LIMIT, tx)
                    .compose(raised -> commands.recordSignal(
                            instance.id(),
                            name,
                            forkStepId,
                            branchId,
                            waitId,
                            dedupKey,
                            "{\"value\":1}",
                            "{\"carrier\":true}",
                            "{\"source\":\"schema-placement\"}",
                            REQUESTER,
                            tx)));
        }

        /** Records a signal and claims it. */
        ClaimedCommand claimedSignal(WorkflowInstance instance) throws Exception {
            return claim(signal(instance, "signal-a"));
        }

        /** Inserts an open task of the instance. */
        TaskRecord task(WorkflowInstance instance) throws Exception {
            TaskRecord task = openTask(instance.id(), "review", new TaskAssignment.User("alice"), null);
            inTransaction(runtime, tx -> runtime.pgTaskStore().insertOpen(task, tx));
            return task;
        }

        /** Records a completion of the task and reserves the task for it in the same transaction. */
        long completion(WorkflowInstance instance, TaskRecord task) throws Exception {
            long completion = inTransaction(runtime, tx -> commands.recordCompletion(
                            instance.id(),
                            task.taskId(),
                            unique("completion-key"),
                            "{\"decision\":\"approve\"}",
                            "{\"decision\":\"approve\"}",
                            REQUESTER,
                            tx)
                    .compose(id -> runtime.pgTaskReservations()
                            .markCompleting(task.taskId().toString(), id, tx)
                            .map(reserved -> id)));
            assertEquals("COMPLETING", mappedValue("workflow_tasks", "task_id", task.taskId(), "status"));
            assertEquals(
                    String.valueOf(completion),
                    mappedValue("workflow_tasks", "task_id", task.taskId(), "reservation_command_id"));
            return completion;
        }

        RecordedTimer timer(
                WorkflowInstance instance,
                TimerPurpose purpose,
                String stepId,
                TimerCommandSpec.FireTime fireTime,
                UUID taskId,
                List<String> signalNames)
                throws Exception {
            TimerCommandSpec spec = new TimerCommandSpec(
                    instance.id(), purpose, stepId, taskId, null, null, null, fireTime, signalNames);
            return inTransaction(runtime, tx -> commands.recordTimer(spec, tx));
        }

        /** The peeked head of the command, which must be claimable now. */
        CommandHead head(long commandId) throws Exception {
            return await(commands.peek(PEEK_LIMIT)).stream()
                    .filter(head -> head.commandId() == commandId)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("command " + commandId + " is not a claimable head"));
        }

        Optional<ClaimedCommand> tryClaim(
                CommandHead head, int leaseSeconds, int maxAttempts, InstanceFailureListener<SqlClient> failures)
                throws Exception {
            return await(commands.claim(head, NODE, leaseSeconds, maxAttempts, failures));
        }

        /** Claims the command for a lease, which must succeed. */
        ClaimedCommand claim(long commandId) throws Exception {
            ClaimedCommand claim = tryClaim(
                            head(commandId), LEASE_SECONDS, MAX_ATTEMPTS, InstanceFailureListener.none())
                    .orElseThrow();
            assertTrue(claim.isClaim(), "claimed command " + commandId);
            return claim;
        }

        /**
         * Spends the command's only attempt under a lease that ends at once, then claims it again, which
         * dead-letters it.
         */
        void deadLetter(long commandId, InstanceFailureListener<SqlClient> failures) throws Exception {
            CommandHead head = head(commandId);
            assertTrue(tryClaim(head, 0, 1, failures).orElseThrow().isClaim());
            ClaimedCommand dead = tryClaim(head, 0, 1, failures).orElseThrow();
            assertEquals(ClaimedCommand.Result.DEAD_LETTERED, dead.result());
            assertTrue(dead.leaseLapsed());
        }

        String state(long commandId) throws SQLException {
            return commandValue(commandId, "state");
        }

        String commandValue(long commandId, String expression) throws SQLException {
            return mappedValue("workflow_commands", "id", commandId, expression);
        }

        int openSignals(WorkflowInstance instance) throws SQLException {
            return Integer.parseInt(
                    mappedValue("workflow_command_queues", "workflow_id", instance.id(), "open_signals"));
        }
    }

    // --- Shared caller transaction ---

    /** One unit of caller work: workflow rows, an outbox entry and an application order. */
    private record UnitOfWork(
            WorkflowInstance instance,
            BranchToken branch,
            TaskRecord task,
            TimerRecord timer,
            String idempotencyKey,
            String destination,
            UUID orderId) {

        static UnitOfWork create() {
            WorkflowInstance instance = newInstance(FORK_JOIN_DEFINITION.definitionId(), null, null);
            BranchToken branch = newBranch(
                    instance.id(), "a", BranchStatus.WAITING, WaitType.SIGNAL, "signal-a", null, Instant.now());
            return new UnitOfWork(
                    instance,
                    branch,
                    openTask(instance.id(), "wait-a", new TaskAssignment.User("alice"), branch),
                    scheduledTimer(
                            instance.id(),
                            Instant.now().plus(Duration.ofHours(1)),
                            TimerPurpose.STANDALONE,
                            null,
                            null),
                    unique("start"),
                    unique("destination"),
                    UUID.randomUUID());
        }
    }

    private static void assertCallerTransactionRollsBackEveryModule(RuntimeComponent runtime) throws Exception {
        // Given: identifiers unique to this unit of work
        UnitOfWork work = UnitOfWork.create();
        IllegalStateException forced = new IllegalStateException("forced failure after every module's writes");
        AtomicReference<List<Long>> visibleInside = new AtomicReference<>();

        // When: every write succeeds inside one caller transaction that is then failed
        Throwable failure = failureOf(runtime.pool().withTransaction(tx -> workflowAppAndOutboxWork(runtime, tx, work)
                .compose(visible -> {
                    visibleInside.set(visible);
                    return Future.<Void>failedFuture(forced);
                })));

        // Then: the forced failure, not a statement error, ended the transaction, and nothing survived it
        assertSame(forced, failure, "the transaction must end with the forced failure");
        assertEquals(
                List.of(1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L),
                visibleInside.get(),
                "every write was visible on the caller's connection before the failure");
        assertEquals(List.of(0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L), committedRows(work));
    }

    private static void assertCallerTransactionCommitsEveryModule(RuntimeComponent runtime) throws Exception {
        UnitOfWork work = UnitOfWork.create();

        // When: the same unit of work commits
        List<Long> visible = await(runtime.pool().withTransaction(tx -> workflowAppAndOutboxWork(runtime, tx, work)));

        // Then: every module's rows are kept together
        assertEquals(List.of(1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L), visible);
        assertEquals(List.of(1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L, 1L), committedRows(work));
    }

    /**
     * Writes through the workflow stores' transaction overloads, the outbox service and the application's own
     * statement on the caller's connection, then reports how many of each row that connection sees.
     */
    private static Future<List<Long>> workflowAppAndOutboxWork(
            RuntimeComponent runtime, SqlConnection tx, UnitOfWork work) {
        WorkflowInstance instance = work.instance();
        String appTable = PgTableNames.qualify(runtime.dbSchemas(), APP_INSTANCE, "orders");
        return runtime.workflowInstanceRepository()
                .insert(instance, tx)
                .compose(v -> runtime.workflowHistoryRepository()
                        .append(historyEntry(instance.id(), 1, WorkflowEntryType.START), tx))
                .compose(v -> runtime.workflowDedupRepository()
                        .claimOrResolveStart(
                                WorkflowScopes.DEFAULT,
                                instance.definitionId(),
                                work.idempotencyKey(),
                                instance.id(),
                                "fp-start",
                                tx))
                .compose(claim -> claim.didInsert()
                        ? Future.<Void>succeededFuture()
                        : Future.<Void>failedFuture(new AssertionError("the start claim must be new")))
                .compose(v -> runtime.branchTokenRepository().insert(work.branch(), tx))
                .compose(v -> runtime.joinStateRepository().insert(openJoin(instance.id(), Instant.now()), tx))
                .compose(v -> runtime.taskStore().insertOpen(work.task(), tx))
                .compose(v -> runtime.timerStore().insertScheduled(work.timer(), tx))
                .compose(v -> runtime.outboxService().publish(tx, outboxEntry(work.destination())))
                .compose(entryId -> tx.preparedQuery("INSERT INTO " + appTable + " (id, label) VALUES ($1, $2)")
                        .execute(Tuple.of(work.orderId(), "order " + work.orderId())))
                .compose(inserted -> tx.preparedQuery(rowCountsQuery("$1", "$2", "$3"))
                        .execute(Tuple.of(
                                work.orderId(),
                                work.destination(),
                                instance.id().value()))
                        .map(rows -> rowValues(rows.iterator().next())));
    }

    /** The same row counts as {@link #workflowAppAndOutboxWork}, read on a fresh connection after the end. */
    private static List<Long> committedRows(UnitOfWork work) throws SQLException {
        // Generated UUIDs and the generated destination contain no quote, so they are safe as literals here
        String query = rowCountsQuery(
                "'" + work.orderId() + "'::uuid",
                "'" + work.destination() + "'",
                "'" + work.instance().id().value() + "'::uuid");
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement()) {
            try (ResultSet rows = statement.executeQuery(query)) {
                assertTrue(rows.next());
                List<Long> counts = new ArrayList<>();
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                    counts.add(rows.getLong(i));
                }
                return counts;
            }
        }
    }

    /**
     * Counts the application order, the outbox entry and the seven workflow rows of one unit of work; the
     * arguments are SQL expressions, and the workflow expression is referenced by every workflow subquery.
     */
    private static String rowCountsQuery(String order, String destination, String workflow) {
        return "SELECT"
                + " (SELECT count(*) FROM " + APP_SCHEMA + ".orders WHERE id = " + order + "),"
                + " (SELECT count(*) FROM " + MESSAGING_SCHEMA + ".outbox WHERE destination = " + destination + "),"
                + " (SELECT count(*) FROM " + MAPPED_SCHEMA + ".workflow_instances WHERE id = " + workflow + "),"
                + Stream.of(
                                "workflow_history",
                                "workflow_dedup",
                                "workflow_branch_tokens",
                                "workflow_join_states",
                                "workflow_tasks",
                                "workflow_timers")
                        .map(table -> " (SELECT count(*) FROM " + MAPPED_SCHEMA + "." + table + " WHERE workflow_id = "
                                + workflow + ")")
                        .collect(Collectors.joining(","));
    }

    private static List<Long> rowValues(Row row) {
        List<Long> values = new ArrayList<>();
        for (int i = 0; i < row.size(); i++) {
            values.add(row.getLong(i));
        }
        return values;
    }

    // --- Decoy default schema ---

    private static void createDecoyDefaultSchema(Set<String> tables) throws SQLException {
        for (String table : tables) {
            execute("CREATE TABLE " + DECOY_SCHEMA + "." + table
                    + " (decoy_key UUID PRIMARY KEY, sentinel TEXT NOT NULL)");
            execute("INSERT INTO " + DECOY_SCHEMA + "." + table + " VALUES (gen_random_uuid(), 'decoy-" + table + "')");
        }

        assertEquals(tables, tablesIn(DECOY_SCHEMA), "the decoy carries no migration history");
        for (String table : tables) {
            assertEquals(DECOY_COLUMNS, columnsOf(DECOY_SCHEMA, table), "decoy shape of " + table);
        }
    }

    private static void assertSchemasDoNotExist(String... schemas) throws SQLException {
        for (String schema : schemas) {
            assertEquals(
                    List.of("0"),
                    column("SELECT count(*) FROM pg_namespace WHERE nspname = ?", schema),
                    "the mapped schema does not exist before the first migration: " + schema);
        }
    }

    /**
     * Every catalog entry of the decoy schema (relations, columns, functions, constraints and triggers) plus
     * the sentinel rows of its tables, as sorted text.
     */
    private static List<String> decoySnapshot(Set<String> tables) throws SQLException {
        List<String> snapshot = new ArrayList<>(column(
                "SELECT 'relation ' || c.relname || ' ' || c.relkind::text FROM pg_class c"
                        + " JOIN pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = ?"
                        + " UNION ALL SELECT 'column ' || table_name || '.' || column_name || ' ' || data_type"
                        + " FROM information_schema.columns WHERE table_schema = ?"
                        + " UNION ALL SELECT 'function ' || p.proname FROM pg_proc p"
                        + " JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = ?"
                        + " UNION ALL SELECT 'constraint ' || c.conname FROM pg_constraint c"
                        + " JOIN pg_namespace n ON n.oid = c.connamespace WHERE n.nspname = ?"
                        + " UNION ALL SELECT 'trigger ' || t.tgname FROM pg_trigger t"
                        + " JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
                        + " WHERE n.nspname = ? AND NOT t.tgisinternal",
                DECOY_SCHEMA,
                DECOY_SCHEMA,
                DECOY_SCHEMA,
                DECOY_SCHEMA,
                DECOY_SCHEMA));
        for (String table : tables) {
            for (String sentinel : column("SELECT sentinel FROM " + DECOY_SCHEMA + "." + table)) {
                snapshot.add("row " + table + " " + sentinel);
            }
        }
        snapshot.sort(null);
        return snapshot;
    }

    private static void assertDecoyUntouched(List<String> before, Set<String> tables) throws SQLException {
        assertEquals(before, decoySnapshot(tables), "the decoy schema keeps its objects, shape and rows");
        assertEquals(tables, tablesIn(DECOY_SCHEMA), "no table, history or object was added to the decoy");
    }

    // --- Catalog queries ---

    private static Set<String> tablesIn(String schema) throws SQLException {
        return Set.copyOf(column("SELECT table_name FROM information_schema.tables WHERE table_schema = ?", schema));
    }

    private static Set<String> schemasHoldingTable(String table) throws SQLException {
        return Set.copyOf(column("SELECT table_schema FROM information_schema.tables WHERE table_name = ?", table));
    }

    private static List<String> columnsOf(String schema, String table) throws SQLException {
        return column(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = ? AND table_name = ?"
                        + " ORDER BY ordinal_position",
                schema,
                table);
    }

    private static Set<String> sequencesIn(String schema) throws SQLException {
        return Set.copyOf(
                column("SELECT sequence_name FROM information_schema.sequences WHERE sequence_schema = ?", schema));
    }

    private static Set<String> indexesIn(String schema) throws SQLException {
        return Set.copyOf(column("SELECT indexname FROM pg_indexes WHERE schemaname = ?", schema));
    }

    private static Set<String> functionsIn(String schema) throws SQLException {
        return Set.copyOf(column(
                "SELECT p.proname FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace WHERE n.nspname = ?",
                schema));
    }

    // --- Mapped row access ---

    /** Reads one expression of one mapped row as text; fails unless exactly that row exists. */
    private static String mappedValue(String table, String idColumn, Object id, String expression) throws SQLException {
        List<String> values = column(
                "SELECT (" + expression + ")::text FROM " + MAPPED_SCHEMA + "." + table + " WHERE " + idColumn
                        + "::text = ?",
                idText(id));
        assertEquals(1, values.size(), "exactly one mapped " + table + " row with " + idColumn + " " + idText(id));
        return values.get(0);
    }

    private static int mappedRows(String table, String idColumn, Object id) throws SQLException {
        return Integer.parseInt(column(
                        "SELECT count(*) FROM " + MAPPED_SCHEMA + "." + table + " WHERE " + idColumn + "::text = ?",
                        idText(id))
                .get(0));
    }

    private static void seedInstanceTimestamp(WorkflowInstanceId id, String column, Instant at) throws SQLException {
        try (Connection connection = jdbc();
                PreparedStatement statement = connection.prepareStatement("UPDATE " + MAPPED_SCHEMA
                        + ".workflow_instances SET " + column + " = ?::timestamptz WHERE id = ?::uuid")) {
            statement.setString(1, at.toString());
            statement.setString(2, id.value().toString());
            assertEquals(1, statement.executeUpdate(), "seeded " + column + " of " + id.value());
        }
    }

    private static String idText(Object id) {
        return id instanceof WorkflowInstanceId workflowId ? workflowId.value().toString() : String.valueOf(id);
    }

    // --- Fixtures ---

    private static JsonObject runtimeConfig(Map<String, String> placement) {
        DbPoolConfig pool = db.toPoolConfig();
        return new JsonObject()
                .put(
                        "db",
                        new JsonObject()
                                .put("host", pool.host())
                                .put("port", pool.port())
                                .put("database", pool.database())
                                .put("user", pool.user())
                                .put("password", pool.password())
                                .put("schemas", placementObject(placement)));
    }

    private static JsonObject placementObject(Map<String, String> placement) {
        JsonObject schemas = new JsonObject();
        placement.forEach(schemas::put);
        return schemas;
    }

    private static JsonObject migrationConfig(Map<String, String> placement) {
        return runtimeConfig(placement)
                .put("flyway", new JsonObject().put("mode", "MIGRATE").put("named", true));
    }

    private static WorkflowInstance newInstance(String definitionId, String businessKey, WorkflowSubjectRef subject) {
        return newInstance(definitionId, businessKey, subject, WorkflowScopes.DEFAULT);
    }

    private static WorkflowInstance newInstance(
            String definitionId, String businessKey, WorkflowSubjectRef subject, String scopeKey) {
        Instant now = Instant.now();
        return new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                definitionId,
                1L,
                PLAN_HASH,
                0L,
                WorkflowStatus.RUNNING,
                businessKey,
                subject,
                FORK_STEP,
                null,
                null,
                null,
                "{}",
                null,
                null,
                now,
                now,
                null,
                scopeKey,
                WorkflowScopes.DEFAULT);
    }

    private static WorkflowInstance repinned(
            WorkflowInstance current, long definitionVersion, String planHash, long version, String stateJson) {
        return new WorkflowInstance(
                current.id(),
                current.definitionId(),
                definitionVersion,
                planHash,
                version,
                WorkflowStatus.RUNNING,
                current.businessKey(),
                current.subjectRef(),
                current.currentStepId(),
                null,
                null,
                null,
                stateJson,
                null,
                null,
                current.createdAt(),
                Instant.now(),
                current.metadata());
    }

    private static WorkflowHistoryEntry historyEntry(WorkflowInstanceId id, long sequence, WorkflowEntryType type) {
        return historyEntry(id, sequence, type, "{\"sequence\":" + sequence + "}");
    }

    private static WorkflowHistoryEntry historyEntry(
            WorkflowInstanceId id, long sequence, WorkflowEntryType type, String payloadJson) {
        return new WorkflowHistoryEntry(id, sequence, type, payloadJson, Instant.now());
    }

    private static BranchToken newBranch(
            WorkflowInstanceId workflowId,
            String branchId,
            BranchStatus status,
            WaitType waitType,
            String waitKey,
            Instant nextRetryAt,
            Instant updatedAt) {
        return new BranchToken(
                UUID.randomUUID(),
                workflowId,
                FORK_STEP,
                branchId,
                "wait-" + branchId,
                status,
                waitType,
                waitKey,
                null,
                null,
                null,
                null,
                0,
                3,
                nextRetryAt,
                null,
                null,
                null,
                0L,
                updatedAt,
                updatedAt,
                DurableMetadata.empty());
    }

    private static JoinState openJoin(WorkflowInstanceId workflowId, Instant now) {
        return new JoinState(
                workflowId,
                FORK_STEP,
                JOIN_STEP,
                JoinPolicyType.ALL_REQUIRED,
                JoinStateStatus.OPEN,
                null,
                null,
                0L,
                now,
                now);
    }

    private static TaskRecord openTask(
            WorkflowInstanceId workflowId, String stepId, TaskAssignment assignment, BranchToken branch) {
        return new TaskRecord(
                UUID.randomUUID(),
                workflowId,
                stepId,
                assignment,
                TaskStatus.OPEN,
                DECISIONS,
                null,
                null,
                null,
                null,
                null,
                Instant.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                branch == null ? null : branch.id(),
                branch == null ? null : branch.forkStepId(),
                branch == null ? null : branch.branchId());
    }

    private static TimerRecord scheduledTimer(
            WorkflowInstanceId workflowId, Instant fireAt, TimerPurpose purpose, UUID taskId, BranchToken branch) {
        return new TimerRecord(
                UUID.randomUUID(),
                workflowId,
                "timer-step",
                fireAt,
                TimerStatus.SCHEDULED,
                Instant.now(),
                null,
                null,
                null,
                null,
                purpose,
                taskId,
                branch == null ? null : branch.id(),
                branch == null ? null : branch.forkStepId(),
                branch == null ? null : branch.branchId(),
                DurableMetadata.empty());
    }

    private static OutboxEntry outboxEntry(String destination) {
        return OutboxEntry.builder()
                .destinationType(DestinationType.SERVICE)
                .destination(destination)
                .eventType("schema.placement.workflow")
                .payload(new JsonObject().put("destination", destination))
                .build();
    }

    private static WorkflowActor actor(String userId) {
        return new WorkflowActor.User(userId);
    }

    private static Set<UUID> instanceIds(PagedResult<WorkflowInstance> page) {
        return page.items().stream().map(instance -> instance.id().value()).collect(Collectors.toSet());
    }

    private static List<Long> sequences(List<WorkflowHistoryEntry> entries) {
        return entries.stream().map(WorkflowHistoryEntry::sequence).toList();
    }

    private static List<String> branchIds(List<BranchToken> tokens) {
        return tokens.stream().map(BranchToken::branchId).toList();
    }

    private static List<UUID> tokenIds(List<BranchToken> tokens) {
        return tokens.stream().map(BranchToken::id).toList();
    }

    private static Set<UUID> taskIds(PagedResult<TaskRecord> page) {
        return page.items().stream().map(TaskRecord::taskId).collect(Collectors.toSet());
    }

    private static List<UUID> taskIdList(List<TaskRecord> tasks) {
        return tasks.stream().map(TaskRecord::taskId).toList();
    }

    private static List<UUID> timerIds(List<TimerRecord> timers) {
        return timers.stream().map(TimerRecord::timerId).toList();
    }

    @SafeVarargs
    private static <T> Set<T> with(Set<T> values, T... extra) {
        Set<T> combined = new HashSet<>(values);
        combined.addAll(List.of(extra));
        return Set.copyOf(combined);
    }

    private static String unique(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    // --- Native database access and futures ---

    private static Connection jdbc() throws SQLException {
        return DriverManager.getConnection(db.jdbcUrl(), db.username(), db.password());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = jdbc();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    /** Runs a query with text parameters and returns its first column rendered as text, one entry per row. */
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

    private static <T> T inTransaction(RuntimeComponent runtime, Function<SqlConnection, Future<T>> work)
            throws Exception {
        return await(runtime.pool().withTransaction(work));
    }

    /** Runs an update in its own caller transaction and returns the number of rows it changed. */
    private static int rowsChanged(RuntimeComponent runtime, Function<SqlConnection, Future<Integer>> update)
            throws Exception {
        return inTransaction(runtime, update);
    }

    /** Runs a boolean-valued store call in its own caller transaction. */
    private static boolean flag(RuntimeComponent runtime, Function<SqlConnection, Future<Boolean>> call)
            throws Exception {
        return inTransaction(runtime, call);
    }

    /** Runs a dedup claim in its own caller transaction and returns whether it inserted a new claim. */
    private static boolean claimed(RuntimeComponent runtime, Function<SqlConnection, Future<Boolean>> claim)
            throws Exception {
        return inTransaction(runtime, claim);
    }

    /** Reads a history sequence number in its own caller transaction. */
    private static long sequenceNumber(RuntimeComponent runtime, Function<SqlConnection, Future<Long>> read)
            throws Exception {
        return inTransaction(runtime, read);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static Throwable failureOf(Future<?> future) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> await(future));
        return failure.getCause();
    }
}
