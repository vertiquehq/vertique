// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.application.VertiqueApplicationBootstrap;
import dev.vertique.application.VertiqueApplicationHandle;
import dev.vertique.core.VertiqueRuntime;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.definition.source.WorkflowDefinitionSource;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.postgresql.executor.PgExecutionProbeSupport;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.AppComponent;
import dev.vertique.workflow.postgresql.registry.ManagedDefinitionDatabase.BootObservation;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Upgrades a database that holds rows written by the previous release and shows that those rows keep working: the
 * instances resume in the default scope, an open task is completed with its stored decision, a waiting instance
 * takes its signal, and a replayed start returns the original instance. Each scenario owns its database and writes
 * its rows with raw SQL in the previous schema's shape before the migration runs.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class ReleasedDataUpgradeIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    private static final String DEFINITION_ID = "released-approval";

    static final PostgresContainer hashProbeDb = new PostgresContainer()
            .withDatabaseName("wf_released_hash_it")
            .withMigration("classpath:db/migration/workflow");

    static final PostgresContainer withoutStoreDb = new PostgresContainer().withDatabaseName("wf_released_plain_it");

    static final PostgresContainer withStoreDb = new PostgresContainer().withDatabaseName("wf_released_store_it");

    // --- Fixture types: nothing here carries a name annotation, so every name is the binary class name ---

    /** Declares the awaited signal; the contract carries no name annotation, so its name is the binary class name. */
    interface ReleasedContract {

        @WorkflowSignal("ship")
        Future<Void> ship(WorkflowInstanceId id, ShipSignal payload, @SignalDedupKey String dedupKey);
    }

    record ReleasedStart(String id) {}

    record ReleasedState(String id, String note) {}

    record ApproveDecision(String comment) {}

    record ShipSignal(String carrier) {}

    /** Start, then a human task {@code review} deciding {@code approve}, then a wait for {@code ship}, then done. */
    static final class ReleasedApprovalDefinition implements WorkflowDefinition<ReleasedState, ReleasedContract> {

        @Override
        public Class<ReleasedContract> contract() {
            return ReleasedContract.class;
        }

        @Override
        public Class<ReleasedState> stateType() {
            return ReleasedState.class;
        }

        @Override
        public String definitionId() {
            return DEFINITION_ID;
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<ReleasedState> wf) {
            wf.init(ReleasedStart.class, start -> new ReleasedState(start.id(), "started"))
                    .initialStep("review")
                    .task("review")
                    .assignToRole("reviewers")
                    .decision("approve", ApproveDecision.class)
                    .onDecision((state, decision) -> new ReleasedState(state.id(), decision.comment()))
                    .toStep("await-ship")
                    .build()
                    .waitFor(
                            "await-ship",
                            "ship",
                            ShipSignal.class,
                            (state, signal) -> new ReleasedState(state.id(), signal.carrier()),
                            "done")
                    .complete("done");
        }
    }

    @Test
    void releasedInstancesResumeInTheDefaultScopeWithoutStore(Vertx vertx) throws Exception {
        // Given a database at the first version holding instance A at its open task, instance B waiting for the
        // signal, and A's start dedup row, all pinned to the plan hash the current code computes
        String planHash = currentPlanHash(vertx);
        flyway(withoutStoreDb).target("1").load().migrate();
        UUID instanceA = UUID.randomUUID();
        UUID instanceB = UUID.randomUUID();
        UUID taskA = UUID.randomUUID();
        seedReleasedRows(instanceA, instanceB, taskA, planHash, withoutStoreDb);

        // When the database is migrated to the latest version and an engine without a registry store starts
        flyway(withoutStoreDb).load().migrate();
        Pool pool = openPool(vertx, withoutStoreDb);
        WorkflowInstanceId replayed;
        try {
            // The released instances were not started through this engine, so its executor must run every command
            WorkflowEngineHandle engine = runningEngineOver(vertx, pool);
            await(engine.executor().start());
            try {
                replayed = resume(engine, engine, engine.executionProbe(), pool, instanceA, instanceB, taskA);
            } finally {
                await(engine.executor().stop());
            }
        } finally {
            await(pool.close());
        }

        // Then both instances completed, the replay returned A, and nothing else changed
        assertResumed(withoutStoreDb, replayed, instanceA, instanceB);
    }

    @Test
    void releasedInstancesResumeInTheDefaultScopeWithStore(Vertx vertx) throws Exception {
        // Given a database at the first version holding the same released rows
        String planHash = currentPlanHash(vertx);
        flyway(withStoreDb).target("1").load().migrate();
        UUID instanceA = UUID.randomUUID();
        UUID instanceB = UUID.randomUUID();
        UUID taskA = UUID.randomUUID();
        seedReleasedRows(instanceA, instanceB, taskA, planHash, withStoreDb);

        // When the database is migrated and an application with the durable registry starts, so the bootstrap
        // records the code definition and pin admission checks every write
        flyway(withStoreDb).load().migrate();
        Pool pool = ManagedDefinitionDatabase.openPool(vertx, withStoreDb);
        WorkflowDefinitionSource noDocuments = List::of;
        AppComponent app = ManagedDefinitionDatabase.appWithCode(
                vertx,
                pool,
                new JsonObject(),
                new BootObservation(DEFINITION_ID, 1, "unused", false),
                noDocuments,
                List.of(new ReleasedApprovalDefinition()));
        VertiqueApplicationHandle<AppComponent> handle =
                await(VertiqueApplicationBootstrap.start(VertiqueRuntime.of(vertx, new JsonObject()), ignored -> app));
        WorkflowInstanceId replayed;
        try {
            replayed = resume(
                    app.workflowOperations(),
                    app.taskCallbacks(),
                    PgExecutionProbeSupport.probe(app.pool(), vertx),
                    app.pool(),
                    instanceA,
                    instanceB,
                    taskA);
        } finally {
            await(handle.shutdown());
            await(pool.close());
        }

        // Then both instances completed, the replay returned A, and nothing else changed
        assertResumed(withStoreDb, replayed, instanceA, instanceB);

        // And the registry holds the code definition in the default scope with its contract and state type names
        try (Connection connection = connection(withStoreDb)) {
            assertThat(rows(connection, """
                            SELECT scope_key || '|' || definition_id || '|' || definition_version || '|' || kind
                                   || '|' || contract_name || '|' || state_type_name
                            FROM workflow_definitions WHERE definition_id = '%s'
                            """.formatted(DEFINITION_ID)))
                    .containsExactly("default|" + DEFINITION_ID + "|1|CODE|" + ReleasedContract.class.getName() + "|"
                            + ReleasedState.class.getName());
        }
    }

    // --- Scenario steps ---

    /** Writes A at its task, B at the signal wait and A's start dedup row, in the previous schema's shape. */
    private static void seedReleasedRows(
            UUID instanceA, UUID instanceB, UUID taskA, String planHash, PostgresContainer database) throws Exception {
        try (Connection connection = connection(database)) {
            ReleasedRows.instanceAtTask(
                    connection,
                    DEFINITION_ID,
                    instanceA,
                    taskA,
                    planHash,
                    ApproveDecision.class.getName(),
                    "await-ship");
            ReleasedRows.instanceAtSignal(connection, DEFINITION_ID, instanceB, "await-ship", "ship", planHash);
            ReleasedRows.startDedup(connection, DEFINITION_ID, "idem-a", instanceA);
        }
    }

    /**
     * Completes A's task with its stored decision, signals both instances and replays A's start. Returns the id the
     * replayed start resolved to.
     */
    private static WorkflowInstanceId resume(
            WorkflowOperations operations,
            TransactionalTaskCallbacks<SqlClient> taskCallbacks,
            WorkflowExecutionProbe probe,
            Pool pool,
            UUID instanceA,
            UUID instanceB,
            UUID taskA)
            throws Exception {
        TaskCompletionCommand approve = new TaskCompletionCommand(
                taskA, "approve", Map.of("comment", "looks fine"), "approve-a", new WorkflowActor.User("alice"), null);
        TaskMutationResult completed = await(pool.withTransaction(tx -> taskCallbacks.taskCompleted(approve, tx)));
        await(probe.awaitIdle(new WorkflowInstanceId(instanceA), IDLE_TIMEOUT));
        assertThat(completed).isEqualTo(TaskMutationResult.APPLIED);
        await(operations.signal(new WorkflowInstanceId(instanceA), "ship", Map.of("carrier", "post"), "ship-a"));
        await(probe.awaitIdle(new WorkflowInstanceId(instanceA), IDLE_TIMEOUT));
        await(operations.signal(new WorkflowInstanceId(instanceB), "ship", Map.of("carrier", "rail"), "ship-b"));
        await(probe.awaitIdle(new WorkflowInstanceId(instanceB), IDLE_TIMEOUT));
        WorkflowInstanceId replayedStart = await(operations.start(new StartCommand(
                DEFINITION_ID,
                ReleasedContract.class.getName(),
                new ReleasedStart("a"),
                "idem-a",
                null,
                null,
                1L,
                null,
                null)));
        await(probe.awaitIdle(replayedStart, IDLE_TIMEOUT));
        return replayedStart;
    }

    private static void assertResumed(
            PostgresContainer database, WorkflowInstanceId replayed, UUID instanceA, UUID instanceB) throws Exception {
        try (Connection connection = connection(database)) {
            // Both instances completed and read the default scope as their start scope and definition scope
            assertThat(rows(connection, """
                            SELECT id || '|' || status || '|' || scope_key || '|' || definition_scope_key
                            FROM workflow_instances ORDER BY id
                            """))
                    .containsExactlyInAnyOrder(
                            instanceA + "|COMPLETED|default|default", instanceB + "|COMPLETED|default|default");

            // And the replayed start returned A's id and created no instance
            assertThat(replayed.value()).isEqualTo(instanceA);
            assertThat(rows(connection, "SELECT count(*) FROM workflow_instances"))
                    .containsExactly("2");

            // And A's start dedup row kept its namespace and its version fingerprint
            assertThat(rows(connection, """
                            SELECT scope_key || '|' || dedup_namespace || '|' || key || '|' || fingerprint
                            FROM workflow_dedup WHERE kind = 'start'
                            """))
                    .containsExactly("default|" + DEFINITION_ID + "|idem-a|definitionVersion=1");
        }
    }

    // --- Fixtures ---

    /** The plan hash the current code computes for the definition, read from an instance a fresh database stores. */
    private static String currentPlanHash(Vertx vertx) throws Exception {
        Pool pool = openPool(vertx, hashProbeDb);
        try {
            WorkflowEngineHandle engine = engineOver(pool);
            WorkflowInstanceId probed = await(engine.start(new StartCommand(
                    DEFINITION_ID,
                    ReleasedContract.class.getName(),
                    new ReleasedStart("probe"),
                    "idem-probe",
                    null,
                    null,
                    null,
                    null,
                    null)));
            await(engine.executionProbe().awaitIdle(probed, IDLE_TIMEOUT));
        } finally {
            await(pool.close());
        }
        try (Connection connection = connection(hashProbeDb)) {
            return rows(connection, "SELECT plan_hash FROM workflow_instances").get(0);
        }
    }

    /** An engine without a registry store that knows only the released definition. */
    private static WorkflowEngineHandle engineOver(Pool pool) {
        PgDbExceptionMapper mapper = new PgDbExceptionMapper();
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(new ReleasedApprovalDefinition());
        return PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                new PgWorkflowInstanceRepository(pool, mapper),
                new PgWorkflowHistoryRepository(pool, mapper),
                new PgWorkflowDedupRepository(pool, mapper),
                Set.of(),
                Set.of(IntentKind.WORKFLOW_EVENT),
                new PgTimerStore(mapper),
                new PgTaskStore(pool, mapper),
                Clock.systemUTC());
    }

    /** An engine whose executor, which the test starts, runs every command of the database. */
    private static WorkflowEngineHandle runningEngineOver(Vertx vertx, Pool pool) {
        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(new ReleasedApprovalDefinition());
        return PgWorkflowEngineTestSupport.executorEngine(pool, vertx, registry)
                .executorConfig(new WorkflowExecutorConfig(1, 8, 100, 60))
                .build();
    }

    private static Pool openPool(Vertx vertx, PostgresContainer database) {
        DbPoolConfig config = database.toPoolConfig();
        return PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(4))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
    }

    private static FluentConfiguration flyway(PostgresContainer container) {
        return Flyway.configure()
                .dataSource(container.jdbcUrl(), container.username(), container.password())
                .locations("classpath:db/migration/workflow");
    }

    private static Connection connection(PostgresContainer container) throws SQLException {
        return DriverManager.getConnection(container.jdbcUrl(), container.username(), container.password());
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        try (Statement query = connection.createStatement();
                ResultSet rows = query.executeQuery(sql)) {
            List<String> values = new ArrayList<>();
            while (rows.next()) {
                values.add(rows.getString(1));
            }
            return values;
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
