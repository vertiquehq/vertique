// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.assertj.core.api.Assertions.assertThat;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.logging.LoggingContextModule;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.contract.SignalDedupKey;
import dev.vertique.workflow.contract.WorkflowSignal;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.TenantCtx;
import dev.vertique.workflow.engine.testsupport.TenantCtxCodec;
import dev.vertique.workflow.migration.MigrationContext;
import dev.vertique.workflow.migration.MigrationResult;
import dev.vertique.workflow.migration.WorkflowMigrationHandler;
import dev.vertique.workflow.migration.WorkflowMigrationModule;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Real PostgreSQL proof that the engine starts every instance in the default scope whatever the
 * ambient context holds, that every task takes the start scope of its instance row once another writer
 * has stamped one, and that the stamped scope survives migration and resurrection.
 *
 * <p>The engine comes from the generated {@link TenantEngineHost} component, which registers the
 * shared {@link TenantCtxCodec} fixture with the real propagator so a scope-looking value can be
 * bound as the ambient context.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WorkflowScopeKeyIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_scope_key_test")
            .withMigration("classpath:db/migration/workflow");

    private static final String SINGLE_ID = "scope-single";
    private static final String FORK_ID = "scope-fork";
    private static final String MIGRATE_ID = "scope-migrate";

    static Vertx vertxRef;
    static ExecutorDeployment executor;
    static Pool pool;
    static TenantEngineHost host;
    static WorkflowOperations operations;
    static TransactionalTaskCallbacks<SqlClient> taskCallbacks;
    static WorkflowTransactionRunner<SqlClient> txRunner;
    static WorkflowInstanceRepository<SqlClient> instanceRepository;
    static ContextHolder holder;
    static TransactionalWorkflowOperations<SqlClient> txOperations;

    // --- Workflow definitions ---

    record StartFlow(String key) {}

    record FlowState(String key) {}

    record TargetState(String name, boolean migrated) {}

    interface SingleContract {
        @WorkflowSignal("go")
        Future<Void> go(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    interface ForkContract {
        @WorkflowSignal("sig-b")
        Future<Void> sigB(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    interface MigrateContract {
        @WorkflowSignal("go")
        Future<Void> go(WorkflowInstanceId id, String payload, @SignalDedupKey String dedupKey);
    }

    /** A code-first definition whose body is supplied by the declaration. */
    static final class FlowDefinition<S, C> implements WorkflowDefinition<S, C> {
        private final Class<C> contract;
        private final Class<S> stateType;
        private final String definitionId;
        private final long definitionVersion;
        private final Consumer<WorkflowBuilder<S>> body;

        FlowDefinition(
                Class<C> contract,
                Class<S> stateType,
                String definitionId,
                long definitionVersion,
                Consumer<WorkflowBuilder<S>> body) {
            this.contract = contract;
            this.stateType = stateType;
            this.definitionId = definitionId;
            this.definitionVersion = definitionVersion;
            this.body = body;
        }

        @Override
        public Class<C> contract() {
            return contract;
        }

        @Override
        public Class<S> stateType() {
            return stateType;
        }

        @Override
        public String definitionId() {
            return definitionId;
        }

        @Override
        public long definitionVersion() {
            return definitionVersion;
        }

        @Override
        public void define(WorkflowBuilder<S> workflow) {
            body.accept(workflow);
        }
    }

    /**
     * review (task) -> hold (signal "go") -> review-late (task) -> done. The first task is created by
     * the start, the second by the signal that resumes the instance.
     */
    static final WorkflowDefinition<FlowState, SingleContract> SINGLE =
            new FlowDefinition<>(SingleContract.class, FlowState.class, SINGLE_ID, 1L, wf -> wf.init(
                            StartFlow.class, p -> new FlowState(p.key()))
                    .initialStep("review")
                    .task("review")
                    .assignToRole("reviewers")
                    .decision("approve", void.class)
                    .onDecision((s, p) -> s)
                    .toStep("hold")
                    .build()
                    .waitForSignal("hold", "go", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("review-late")
                    .build()
                    .task("review-late")
                    .assignToRole("reviewers")
                    .decision("approve", void.class)
                    .onDecision((s, p) -> s)
                    .toStep("done")
                    .build()
                    .complete("done"));

    /**
     * fork: branch a creates a task at once, branch b waits for a signal and then creates a task. The
     * test signals branch b, which creates the second task.
     */
    static final WorkflowDefinition<FlowState, ForkContract> FORK =
            new FlowDefinition<>(ForkContract.class, FlowState.class, FORK_ID, 1L, wf -> {
                wf.init(StartFlow.class, p -> new FlowState(p.key()))
                        .fork("fork")
                        .branch("a", "task-a", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .branch("b", "wait-b", RaceSafety.IGNORE_LATE_RESULT_SAFE)
                        .join("join")
                        .task("task-a")
                        .assignToRole("reviewers")
                        .decision("approve", void.class)
                        .onDecision((s, p) -> s)
                        .toStep("complete-a")
                        .build()
                        .complete("complete-a")
                        .waitForSignal("wait-b", "sig-b", String.class)
                        .onSignal((s, p) -> s)
                        .toStepOnSignal("task-b")
                        .build()
                        .task("task-b")
                        .assignToRole("reviewers")
                        .decision("approve", void.class)
                        .onDecision((s, p) -> s)
                        .toStep("complete-b")
                        .build()
                        .complete("complete-b")
                        .join("join")
                        .allRequired((s, results) -> s)
                        .toStep("done")
                        .onFailure("done")
                        .endJoin()
                        .complete("done");
                wf.initialStep("fork");
            });

    /** Version 1 of the migrated definition: hold (signal) -> review-v1 (task) -> done-v1. */
    static final WorkflowDefinition<TargetState, MigrateContract> MIGRATE_V1 =
            new FlowDefinition<>(MigrateContract.class, TargetState.class, MIGRATE_ID, 1L, wf -> wf.init(
                            StartFlow.class, p -> new TargetState(p.key(), false))
                    .initialStep("hold-v1")
                    .waitForSignal("hold-v1", "go", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("review-v1")
                    .build()
                    .task("review-v1")
                    .assignToRole("reviewers")
                    .decision("approve", void.class)
                    .onDecision((s, p) -> s)
                    .toStep("done-v1")
                    .build()
                    .complete("done-v1"));

    /** Version 2 of the migrated definition: review-v2 (task) -> done-v2. */
    static final WorkflowDefinition<TargetState, MigrateContract> MIGRATE_V2 =
            new FlowDefinition<>(MigrateContract.class, TargetState.class, MIGRATE_ID, 2L, wf -> wf.init(
                            StartFlow.class, p -> new TargetState(p.key(), false))
                    .initialStep("review-v2")
                    .task("review-v2")
                    .assignToRole("reviewers")
                    .decision("approve", void.class)
                    .onDecision((s, p) -> s)
                    .toStep("done-v2")
                    .build()
                    .complete("done-v2"));

    /** Migrates version 1 to version 2, continuing at the version 2 task. */
    static final WorkflowMigrationHandler<TargetState, TargetState> MIGRATION = new WorkflowMigrationHandler<>() {
        @Override
        public String definitionId() {
            return MIGRATE_ID;
        }

        @Override
        public long fromDefinitionVersion() {
            return 1L;
        }

        @Override
        public long targetDefinitionVersion() {
            return 2L;
        }

        @Override
        public Class<TargetState> sourceStateType() {
            return TargetState.class;
        }

        @Override
        public Class<TargetState> targetStateType() {
            return TargetState.class;
        }

        @Override
        public MigrationResult<TargetState> migrate(TargetState sourceState, MigrationContext ctx) {
            return MigrationResult.continueAt(new TargetState(sourceState.name(), true), "review-v2");
        }
    };

    // --- Host ---

    /** Test module: definitions, migration handler and the tenant codec for the propagator. */
    @Module
    static class TenantEngineHostModule {

        @Provides
        @IntoSet
        static WorkflowContributor definitions() {
            return registry -> {
                registry.register(SINGLE);
                registry.register(FORK);
                registry.register(MIGRATE_V1);
                registry.register(MIGRATE_V2);
            };
        }

        @Provides
        @IntoSet
        static WorkflowMigrationHandler<?, ?> migration() {
            return MIGRATION;
        }

        @Provides
        @IntoSet
        static DurableContextMetadataEncoder<?> tenantEncoder() {
            return TenantCtxCodec.encoder();
        }

        @Provides
        @IntoSet
        static DurableContextMetadataDecoder<?> tenantDecoder() {
            return TenantCtxCodec.decoder();
        }

        @Provides
        @Singleton
        static WorkflowPlanValidator planValidator() {
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.of()));
        }

        @Provides
        @Singleton
        static PgDbExceptionMapper pgDbExceptionMapper() {
            return new PgDbExceptionMapper();
        }
    }

    /**
     * Generated application graph with the PostgreSQL engine, the migration registry and the real
     * propagator.
     */
    @Singleton
    @Component(
            modules = {
                WorkflowPostgresqlModule.class,
                WorkflowMigrationModule.class,
                ContextRuntimeModule.class,
                LoggingContextModule.class,
                ExecutorDeployment.FastPolling.class,
                TenantEngineHostModule.class
            })
    interface TenantEngineHost {

        java.util.Set<dev.vertique.deploy.VerticleDeployment> verticleDeployments();

        WorkflowOperations workflowOperations();

        WorkflowExecutionProbe workflowExecutionProbe();

        TransactionalTaskCallbacks<SqlClient> taskCallbacks();

        TransactionalWorkflowOperations<SqlClient> txWorkflowOperations();

        WorkflowTransactionRunner<SqlClient> transactionRunner();

        BranchTokenRepository<SqlClient> branchTokenRepository();

        WorkflowCommandRepository<SqlClient> commandRepository();

        WorkflowInstanceRepository<SqlClient> instanceRepository();

        WorkflowRegistry workflowRegistry();

        dev.vertique.workflow.postgresql.registry.PgWorkflowDefinitionRegistryStore pgWorkflowDefinitionRegistryStore();

        dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycle definitionLifecycle();

        ContextHolder contextHolder();

        @Component.Factory
        interface Factory {
            TenantEngineHost create(@BindsInstance Pool pool, @BindsInstance Clock clock);
        }
    }

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        vertxRef = vertx;
        DbPoolConfig config = db.toPoolConfig();
        pool = PgBuilder.pool()
                .with(new PoolOptions().setMaxSize(10))
                .connectingTo(new PgConnectOptions()
                        .setHost(config.host())
                        .setPort(config.port())
                        .setDatabase(config.database())
                        .setUser(config.user())
                        .setPassword(config.password()))
                .using(vertx)
                .build();
        host = DaggerWorkflowScopeKeyIT_TenantEngineHost.factory().create(pool, Clock.systemUTC());
        // The graph runs no deployer: only its workflow executor is deployed
        executor = ExecutorDeployment.deploy(vertx, host.verticleDeployments())
                .toCompletionStage()
                .toCompletableFuture()
                .join();
        DefaultVersionSeed.recordAndActivate(
                host.pgWorkflowDefinitionRegistryStore(), host.definitionLifecycle(), host.workflowRegistry());
        operations = host.workflowOperations();
        taskCallbacks = host.taskCallbacks();
        txRunner = host.transactionRunner();
        instanceRepository = host.instanceRepository();
        holder = host.contextHolder();
        txOperations = host.txWorkflowOperations();
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables() throws Exception {
        await(WorkflowTestTables.truncating(
                        pool,
                        "TRUNCATE TABLE workflow_branch_tokens, workflow_join_states, workflow_timers,"
                                + " workflow_tasks, workflow_history, workflow_dedup, workflow_instances"
                                + " RESTART IDENTITY CASCADE")
                .execute());
    }

    @AfterAll
    static void tearDown() {
        if (executor != null) {
            executor.undeploy().toCompletionStage().toCompletableFuture().join();
        }
        if (pool != null) {
            pool.close();
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("engine-started rows are in the default scope and a later stamp hands its scope to later tasks")
    void engineStartedRowsAreInTheDefaultScopeAndALaterStampHandsItToLaterTasks() throws Exception {
        // Given single and forking workflows started under an ambient context carrying t1, two of them
        // left alone and two stamped t1 by another writer right after the start
        WorkflowInstanceId singleUnstamped = await(inContext(
                "t1",
                () -> operations.start(new StartCommand(
                        SINGLE_ID,
                        SingleContract.class.getName(),
                        new StartFlow("a"),
                        "k-single-unstamped",
                        null,
                        null,
                        null,
                        null,
                        null))));
        await(host.workflowExecutionProbe().awaitIdle(singleUnstamped, IDLE_TIMEOUT));
        WorkflowInstanceId forkUnstamped = await(inContext(
                "t1",
                () -> operations.start(new StartCommand(
                        FORK_ID,
                        ForkContract.class.getName(),
                        new StartFlow("b"),
                        "k-fork-unstamped",
                        null,
                        null,
                        null,
                        null,
                        null))));
        await(host.workflowExecutionProbe().awaitIdle(forkUnstamped, IDLE_TIMEOUT));
        WorkflowInstanceId singleStamped = await(inContext(
                "t1",
                () -> operations.start(new StartCommand(
                        SINGLE_ID,
                        SingleContract.class.getName(),
                        new StartFlow("c"),
                        "k-single-stamped",
                        null,
                        null,
                        null,
                        null,
                        null))));
        await(host.workflowExecutionProbe().awaitIdle(singleStamped, IDLE_TIMEOUT));
        WorkflowInstanceId forkStamped = await(inContext(
                "t1",
                () -> operations.start(new StartCommand(
                        FORK_ID,
                        ForkContract.class.getName(),
                        new StartFlow("d"),
                        "k-fork-stamped",
                        null,
                        null,
                        null,
                        null,
                        null))));
        await(host.workflowExecutionProbe().awaitIdle(forkStamped, IDLE_TIMEOUT));

        // Then the ambient tenant was not taken: every instance row and start task is in the default scope
        for (WorkflowInstanceId started : List.of(singleUnstamped, forkUnstamped, singleStamped, forkStamped)) {
            assertThat(instanceScopeColumn(started)).isEqualTo(WorkflowScopes.DEFAULT);
            assertThat(taskScopes(started)).hasSize(1).containsOnly(WorkflowScopes.DEFAULT);
        }

        // When another writer stamps two of the instances t1
        stampInstanceScope(singleStamped, "t1");
        stampInstanceScope(forkStamped, "t1");

        // And every single-task workflow has its first task completed and is signalled, and every forking
        // workflow has branch b signalled, all under a context without a tenant
        completeFirstTaskThenSignalGo(singleUnstamped, "unstamped");
        completeFirstTaskThenSignalGo(singleStamped, "stamped");
        signalBranchB(forkUnstamped);
        signalBranchB(forkStamped);

        // Then the unstamped instances and all of their tasks are still in the default scope
        for (WorkflowInstanceId unstamped : List.of(singleUnstamped, forkUnstamped)) {
            assertThat(instanceScopeColumn(unstamped)).isEqualTo(WorkflowScopes.DEFAULT);
            assertThat(await(instanceRepository.findById(unstamped))
                            .orElseThrow()
                            .scopeKey())
                    .isEqualTo(WorkflowScopes.DEFAULT);
            assertThat(taskScopes(unstamped)).hasSize(2).containsOnly(WorkflowScopes.DEFAULT);
        }

        // And the stamped instances keep t1, the tasks created before the stamp stay in the default scope, and the
        // signal-resumed task and the branch b task carry t1
        for (WorkflowInstanceId stamped : List.of(singleStamped, forkStamped)) {
            assertThat(instanceScopeColumn(stamped)).isEqualTo("t1");
            assertThat(await(instanceRepository.findById(stamped)).orElseThrow().scopeKey())
                    .isEqualTo("t1");
            assertThat(taskScopes(stamped)).containsExactly(WorkflowScopes.DEFAULT, "t1");
        }
    }

    @Test
    @DisplayName("migration and resurrection keep the instance scopes")
    void migrationAndResurrectionKeepTheInstanceScopes() throws Exception {
        // Given a waiting instance and a failed instance of version 1, both started in t1 through the repository
        String sourceHash = host.workflowRegistry()
                .resolve(new DefinitionKey(WorkflowScopes.DEFAULT, MIGRATE_ID, 1L))
                .plan()
                .planHash();
        Instant created = Instant.parse("2026-05-08T09:00:00Z");
        WorkflowInstanceId running = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstanceId finished = new WorkflowInstanceId(UUID.randomUUID());
        WorkflowInstance runningRow = new WorkflowInstance(
                running,
                MIGRATE_ID,
                1L,
                sourceHash,
                0L,
                WorkflowStatus.WAITING,
                null,
                null,
                "hold-v1",
                WaitType.SIGNAL,
                "go",
                null,
                "{\"name\":\"alice\"}",
                null,
                null,
                created,
                created,
                null,
                "t1",
                WorkflowScopes.DEFAULT);
        WorkflowInstance finishedRow = new WorkflowInstance(
                finished,
                MIGRATE_ID,
                1L,
                sourceHash,
                0L,
                WorkflowStatus.FAILED,
                null,
                null,
                "review-v1",
                null,
                null,
                null,
                "{\"name\":\"bob\"}",
                "IllegalStateException",
                "failed",
                created,
                created,
                null,
                "t1",
                WorkflowScopes.DEFAULT);
        await(pool.withTransaction(tx -> instanceRepository.insert(runningRow, tx)));
        await(pool.withTransaction(tx -> instanceRepository.insert(finishedRow, tx)));
        assertThat(instanceScopeColumn(running)).isEqualTo("t1");
        assertThat(instanceScopeColumn(finished)).isEqualTo("t1");

        // When the waiting instance is migrated to version 2 and the failed instance is retried
        await(inContext(null, () -> operations.migrate(running, 2L)));
        await(host.workflowExecutionProbe().awaitIdle(running, IDLE_TIMEOUT));
        await(inContext(null, () -> operations.retry(finished)));
        await(host.workflowExecutionProbe().awaitIdle(finished, IDLE_TIMEOUT));

        // Then the migrated instance moved to version 2 and still carries t1 as its start scope in its row and its
        // record
        assertThat(await(instanceRepository.findById(running)).orElseThrow().definitionVersion())
                .isEqualTo(2L);
        assertThat(instanceScopeColumn(running)).isEqualTo("t1");
        assertThat(await(instanceRepository.findById(running)).orElseThrow().scopeKey())
                .isEqualTo("t1");

        // And the resurrected instance is running again and still carries t1 as its start scope in its row and its
        // record
        assertThat(await(instanceRepository.findById(finished)).orElseThrow().status())
                .isNotEqualTo(WorkflowStatus.FAILED);
        assertThat(instanceScopeColumn(finished)).isEqualTo("t1");
        assertThat(await(instanceRepository.findById(finished)).orElseThrow().scopeKey())
                .isEqualTo("t1");

        // And the task each of them created afterwards carries t1
        assertThat(taskScopes(running)).containsExactly("t1");
        assertThat(taskScopes(finished)).containsExactly("t1");
    }

    // --- Helpers ---

    /**
     * Runs the action on a duplicated Vert.x context with the given tenant bound as the ambient
     * context first (none when {@code null}), as every production dispatch entry does.
     */
    private static <T> Future<T> inContext(String ambientTenant, Supplier<Future<T>> action) {
        Promise<T> result = Promise.promise();
        ContextInternal duplicated = ((ContextInternal) vertxRef.getOrCreateContext()).duplicate();
        duplicated.runOnContext(v -> {
            try {
                if (ambientTenant != null) {
                    holder.bind(TenantCtx.class, new TenantCtx(ambientTenant));
                }
                action.get().onComplete(result);
            } catch (Throwable t) {
                result.tryFail(t);
            }
        });
        return result.future();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static void stampInstanceScope(WorkflowInstanceId id, String scopeKey) throws Exception {
        await(pool.preparedQuery("UPDATE workflow_instances SET scope_key = $1 WHERE id = $2")
                .execute(Tuple.of(scopeKey, id.value())));
    }

    private static void completeFirstTaskThenSignalGo(WorkflowInstanceId id, String keySuffix) throws Exception {
        UUID firstTask = openTaskId(id);
        TaskMutationResult completed = await(inContext(
                null,
                () -> pool.withTransaction(tx -> taskCallbacks.taskCompleted(
                        new TaskCompletionCommand(
                                firstTask,
                                "approve",
                                null,
                                "k-complete-" + keySuffix,
                                new WorkflowActor.User("user-1"),
                                null),
                        tx))));
        await(host.workflowExecutionProbe().awaitIdle(id, IDLE_TIMEOUT));
        assertThat(completed).isEqualTo(TaskMutationResult.APPLIED);
        await(inContext(null, () -> operations.signal(id, "go", "payload", "k-signal-" + keySuffix)));
        await(host.workflowExecutionProbe().awaitIdle(id, IDLE_TIMEOUT));
    }

    private static void signalBranchB(WorkflowInstanceId id) throws Exception {
        await(inContext(
                null,
                () -> pool.withTransaction(
                        tx -> txOperations.signal(id, "sig-b", "go", "k-sig-b-" + id.value(), "fork", "b", tx))));
        await(host.workflowExecutionProbe().awaitIdle(id, IDLE_TIMEOUT));
    }

    private static String instanceScopeColumn(WorkflowInstanceId id) throws Exception {
        return await(pool.preparedQuery("SELECT scope_key FROM workflow_instances WHERE id = $1")
                .execute(Tuple.of(id.value()))
                .map(rows -> rows.iterator().next().getString("scope_key")));
    }

    private static List<String> taskScopes(WorkflowInstanceId id) throws Exception {
        return await(pool.preparedQuery(
                        "SELECT scope_key FROM workflow_tasks WHERE workflow_id = $1 ORDER BY created_at, task_id")
                .execute(Tuple.of(id.value()))
                .map(rows -> {
                    List<String> scopes = new ArrayList<>();
                    for (Row row : rows) {
                        scopes.add(row.getString("scope_key"));
                    }
                    return scopes;
                }));
    }

    private static UUID openTaskId(WorkflowInstanceId id) throws Exception {
        return await(pool.preparedQuery(
                        "SELECT task_id FROM workflow_tasks WHERE workflow_id = $1 AND status = 'OPEN' LIMIT 1")
                .execute(Tuple.of(id.value()))
                .map(rows -> rows.iterator().next().getUUID("task_id")));
    }
}
