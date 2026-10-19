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
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.logging.LoggingContextModule;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowRecoveryBridge;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
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
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.ops.WorkflowTenantResolver;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.RaceSafety;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.registry.WorkflowRegistry;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Real PostgreSQL proof that the instance tenant is resolved from the captured durable context at
 * start, that every task takes its instance's tenant, and that the tenant survives migration and
 * resurrection.
 *
 * <p>The engine comes from the generated {@link TenantEngineHost} component, which binds a test
 * {@link WorkflowTenantResolver} reading the {@code tenant} namespace of the captured context and
 * registers the shared {@link TenantCtxCodec} fixture with the real propagator.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WorkflowTenantKeyIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_tenant_key_test")
            .withMigration("classpath:db/migration/workflow");

    /** Context content that must never surface in a failure. */
    private static final String SECRET = "SECRET-CTX-7f3a";

    private static final String SINGLE_ID = "tenant-single";
    private static final String FORK_ID = "tenant-fork";
    private static final String MIGRATE_ID = "tenant-migrate";

    static Vertx vertxRef;
    static Pool pool;
    static TenantEngineHost host;
    static WorkflowOperations operations;
    static TransactionalWorkflowOperations<SqlClient> transactional;
    static TransactionalTaskCallbacks<SqlClient> taskCallbacks;
    static WorkflowTransactionRunner<SqlClient> txRunner;
    static WorkflowInstanceRepository<SqlClient> instanceRepository;
    static ContextHolder holder;
    static PgWorkflowBranchRecoveryService recovery;

    // --- Workflow definitions ---

    record StartFlow(String key) {}

    record FlowState(String key) {}

    record SourceState(String name) {}

    record TargetState(String name, boolean migrated) {}

    interface SingleContract {}

    interface ForkContract {}

    interface MigrateContractV1 {}

    interface MigrateContractV2 {}

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
     * test parks branch b for recovery, so the sweep creates the second task.
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
    static final WorkflowDefinition<SourceState, MigrateContractV1> MIGRATE_V1 =
            new FlowDefinition<>(MigrateContractV1.class, SourceState.class, MIGRATE_ID, 1L, wf -> wf.init(
                            StartFlow.class, p -> new SourceState(p.key()))
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
    static final WorkflowDefinition<TargetState, MigrateContractV2> MIGRATE_V2 =
            new FlowDefinition<>(MigrateContractV2.class, TargetState.class, MIGRATE_ID, 2L, wf -> wf.init(
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
    static final WorkflowMigrationHandler<SourceState, TargetState> MIGRATION = new WorkflowMigrationHandler<>() {
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
        public Class<SourceState> sourceStateType() {
            return SourceState.class;
        }

        @Override
        public Class<TargetState> targetStateType() {
            return TargetState.class;
        }

        @Override
        public MigrationResult<TargetState> migrate(SourceState sourceState, MigrationContext ctx) {
            return MigrationResult.continueAt(new TargetState(sourceState.name(), true), "review-v2");
        }
    };

    // --- Resolver and host ---

    /**
     * Resolver reading the tenant namespace of the captured context. A tenant starting with
     * {@code boom} makes it throw, with a message that carries the tenant.
     */
    static final class ContextTenantResolver implements WorkflowTenantResolver {

        static final AtomicInteger CALLS = new AtomicInteger();
        static volatile RuntimeException lastThrown;

        @Override
        public String tenantOf(DurableMetadata captured) {
            CALLS.incrementAndGet();
            String tenantId = captured.body(TenantCtxCodec.NAMESPACE)
                    .map(body -> body.getString("tenantId"))
                    .orElse(null);
            if (tenantId != null && tenantId.startsWith("boom")) {
                lastThrown = new IllegalStateException("resolver failed for " + tenantId);
                throw lastThrown;
            }
            return tenantId;
        }
    }

    /** Test module: definitions, migration handler, resolver and the tenant codec for the propagator. */
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
        @Singleton
        static WorkflowTenantResolver tenantResolver() {
            return new ContextTenantResolver();
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
     * Generated application graph with the PostgreSQL engine, the migration registry, the recovery
     * bridge, the real propagator and the test resolver.
     */
    @Singleton
    @Component(
            modules = {
                WorkflowPostgresqlModule.class,
                WorkflowMigrationModule.class,
                ContextRuntimeModule.class,
                LoggingContextModule.class,
                TenantEngineHostModule.class
            })
    interface TenantEngineHost {

        WorkflowOperations workflowOperations();

        TransactionalWorkflowOperations<SqlClient> transactionalOperations();

        TransactionalTaskCallbacks<SqlClient> taskCallbacks();

        WorkflowTransactionRunner<SqlClient> transactionRunner();

        BranchTokenRepository<SqlClient> branchTokenRepository();

        WorkflowInstanceRepository<SqlClient> instanceRepository();

        WorkflowRecoveryBridge recoveryBridge();

        WorkflowRegistry workflowRegistry();

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
        host = DaggerWorkflowTenantKeyIT_TenantEngineHost.factory().create(pool, Clock.systemUTC());
        operations = host.workflowOperations();
        transactional = host.transactionalOperations();
        taskCallbacks = host.taskCallbacks();
        txRunner = host.transactionRunner();
        instanceRepository = host.instanceRepository();
        holder = host.contextHolder();
        recovery = new PgWorkflowBranchRecoveryService(
                txRunner,
                host.branchTokenRepository(),
                instanceRepository,
                host.recoveryBridge(),
                host.workflowRegistry(),
                Clock.systemUTC());
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables() throws Exception {
        ContextTenantResolver.CALLS.set(0);
        ContextTenantResolver.lastThrown = null;
        await(pool.query("TRUNCATE TABLE workflow_branch_tokens, workflow_join_states, workflow_timers,"
                        + " workflow_tasks, workflow_history, workflow_dedup, workflow_instances"
                        + " RESTART IDENTITY CASCADE")
                .execute());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("a started instance and all of its tasks carry the resolved tenant")
    void startedInstanceAndAllItsTasksCarryTheResolvedTenant() throws Exception {
        // Given a definition whose start creates a task and whose signal resume creates another
        // When a workflow starts under a context carrying t1
        WorkflowInstanceId single = await(inContext(
                "t1", () -> operations.start(new StartCommand(SINGLE_ID, new StartFlow("a"), "k-single", null, null))));

        // Then the row and the record carry t1, and so does the start's task
        assertThat(instanceTenantColumn(single)).isEqualTo("t1");
        assertThat(await(instanceRepository.findById(single)).orElseThrow().tenantKey())
                .isEqualTo("t1");
        assertThat(taskTenants(single)).containsExactly("t1");

        // When the task is completed and the signal resumes the instance, under a context without a tenant
        UUID firstTask = openTaskId(single);
        assertThat((TaskMutationResult) await(inContext(
                        null,
                        () -> pool.withTransaction(tx -> taskCallbacks.taskCompleted(
                                new TaskCompletionCommand(
                                        firstTask,
                                        "approve",
                                        null,
                                        "k-complete",
                                        new WorkflowActor.User("user-1"),
                                        null),
                                tx)))))
                .isEqualTo(TaskMutationResult.APPLIED);
        await(inContext(null, () -> operations.signal(single, "go", "payload", "k-signal")));

        // Then the signal-resumed task carries the instance's t1, not the ambient absence of a tenant
        assertThat(taskTenants(single)).containsExactly("t1", "t1");

        // When a workflow starts without a tenant context
        WorkflowInstanceId unscoped = await(inContext(
                null, () -> operations.start(new StartCommand(SINGLE_ID, new StartFlow("b"), "k-none", null, null))));

        // Then the instance and its task carry no tenant
        assertThat(instanceTenantColumn(unscoped)).isNull();
        assertThat(await(instanceRepository.findById(unscoped)).orElseThrow().tenantKey())
                .isNull();
        assertThat(taskTenants(unscoped)).hasSize(1).containsOnlyNulls();

        // When a forking workflow starts under t1
        WorkflowInstanceId forked = await(inContext(
                "t1", () -> operations.start(new StartCommand(FORK_ID, new StartFlow("c"), "k-fork", null, null))));

        // Then the instance and branch a's task carry t1
        assertThat(instanceTenantColumn(forked)).isEqualTo("t1");
        assertThat(taskTenants(forked)).containsExactly("t1");

        // When branch b is parked for recovery at its task step and the recovery sweep runs
        await(pool.preparedQuery("UPDATE workflow_branch_tokens SET status = 'RETRY_SCHEDULED',"
                        + " current_step_id = 'task-b', wait_type = NULL, wait_key = NULL,"
                        + " next_retry_at = $1, version = version + 1"
                        + " WHERE workflow_id = $2 AND branch_id = 'b'")
                .execute(Tuple.of(
                        OffsetDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC), forked.value())));
        await(inContext(null, () -> recovery.sweepOnce(Instant.now(), 10)));

        // Then the recovered branch's task carries the instance's t1 as well
        assertThat(taskTenants(forked)).containsExactly("t1", "t1");
    }

    @Test
    @DisplayName("an invalid tenant rolls the start back and a replay does not resolve")
    void invalidTenantRollsBackTheStartAndReplayDoesNotResolve() throws Exception {
        StartCommand start = new StartCommand(SINGLE_ID, new StartFlow("d"), "k-rollback", null, null);

        // Given a resolver that returns a value with a separator for one context and throws for another
        for (String badContext : List.of(SECRET + ":x", "boom-" + SECRET)) {
            int callsBefore = ContextTenantResolver.CALLS.get();

            // When a start inside a transaction fails and the transaction owner rolls it back
            Throwable failure = failureOf(
                    inContext(badContext, () -> txRunner.inTransaction(null, tx -> transactional.start(start, tx))));

            // Then no message in the cause chain holds the context content, the resolver's own exception is
            // not attached, and no dedup, instance, history or task row exists
            assertThat(ContextTenantResolver.CALLS.get()).isEqualTo(callsBefore + 1);
            int depth = 0;
            for (Throwable link = failure; link != null && depth < 32; link = link.getCause(), depth++) {
                assertThat(String.valueOf(link.getMessage())).doesNotContain(SECRET);
                assertThat(link.toString()).doesNotContain(SECRET);
                assertThat(link).isNotSameAs(ContextTenantResolver.lastThrown);
            }
            for (String table : List.of("workflow_dedup", "workflow_instances", "workflow_history", "workflow_tasks")) {
                assertThat(rowCount(table))
                        .as("%s after a rolled back start", table)
                        .isZero();
            }
        }
        int callsAfterFailures = ContextTenantResolver.CALLS.get();
        assertThat(callsAfterFailures).isEqualTo(2);

        // When the same start is repeated under a valid context
        WorkflowInstanceId started = await(inContext("t1", () -> operations.start(start)));

        // Then it succeeds on the freed idempotency key and the instance carries t1
        assertThat(ContextTenantResolver.CALLS.get()).isEqualTo(callsAfterFailures + 1);
        assertThat(instanceTenantColumn(started)).isEqualTo("t1");
        assertThat(rowCount("workflow_instances")).isEqualTo(1);

        // When the successful start is replayed under another tenant's context
        WorkflowInstanceId replayed = await(inContext("t2", () -> operations.start(start)));

        // Then the existing id is returned, the resolver is not called again and the tenant is unchanged
        assertThat(replayed).isEqualTo(started);
        assertThat(ContextTenantResolver.CALLS.get()).isEqualTo(callsAfterFailures + 1);
        assertThat(rowCount("workflow_instances")).isEqualTo(1);
        assertThat(instanceTenantColumn(started)).isEqualTo("t1");
    }

    @Test
    @DisplayName("migration and resurrection keep the instance tenant")
    void migrationAndResurrectionKeepTheInstanceTenant() throws Exception {
        // Given a waiting instance and a failed instance of version 1, both stamped t1 through the repository
        String sourceHash =
                host.workflowRegistry().resolvePinned(MIGRATE_ID, 1L).plan().planHash();
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
                "t1");
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
                "t1");
        await(pool.withTransaction(tx -> instanceRepository.insert(runningRow, tx)));
        await(pool.withTransaction(tx -> instanceRepository.insert(finishedRow, tx)));
        assertThat(instanceTenantColumn(running)).isEqualTo("t1");
        assertThat(instanceTenantColumn(finished)).isEqualTo("t1");

        // When the waiting instance is migrated to version 2 and the failed instance is retried
        await(inContext(null, () -> operations.migrate(running, 2L)));
        await(inContext(null, () -> operations.retry(finished)));

        // Then the migrated instance moved to version 2 and still carries t1 in its row and its record
        assertThat(await(instanceRepository.findById(running)).orElseThrow().definitionVersion())
                .isEqualTo(2L);
        assertThat(instanceTenantColumn(running)).isEqualTo("t1");
        assertThat(await(instanceRepository.findById(running)).orElseThrow().tenantKey())
                .isEqualTo("t1");

        // And the resurrected instance is running again and still carries t1 in its row and its record
        assertThat(await(instanceRepository.findById(finished)).orElseThrow().status())
                .isNotEqualTo(WorkflowStatus.FAILED);
        assertThat(instanceTenantColumn(finished)).isEqualTo("t1");
        assertThat(await(instanceRepository.findById(finished)).orElseThrow().tenantKey())
                .isEqualTo("t1");

        // And the task each of them created afterwards carries t1
        assertThat(taskTenants(running)).containsExactly("t1");
        assertThat(taskTenants(finished)).containsExactly("t1");

        // And the resolver was never consulted, because neither operation creates an instance
        assertThat(ContextTenantResolver.CALLS.get()).isZero();
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

    private static Throwable failureOf(Future<?> future) throws Exception {
        CompletableFuture<Throwable> settled = new CompletableFuture<>();
        future.onComplete(ar -> settled.complete(ar.failed() ? ar.cause() : null));
        Throwable failure = settled.get(10, TimeUnit.SECONDS);
        assertThat(failure).as("the start must fail").isNotNull();
        return failure;
    }

    private static String instanceTenantColumn(WorkflowInstanceId id) throws Exception {
        return await(pool.preparedQuery("SELECT tenant_key FROM workflow_instances WHERE id = $1")
                .execute(Tuple.of(id.value()))
                .map(rows -> rows.iterator().next().getString("tenant_key")));
    }

    private static List<String> taskTenants(WorkflowInstanceId id) throws Exception {
        return await(pool.preparedQuery(
                        "SELECT tenant_key FROM workflow_tasks WHERE workflow_id = $1 ORDER BY created_at, task_id")
                .execute(Tuple.of(id.value()))
                .map(rows -> {
                    List<String> tenants = new ArrayList<>();
                    for (Row row : rows) {
                        tenants.add(row.getString("tenant_key"));
                    }
                    return tenants;
                }));
    }

    private static UUID openTaskId(WorkflowInstanceId id) throws Exception {
        return await(pool.preparedQuery(
                        "SELECT task_id FROM workflow_tasks WHERE workflow_id = $1 AND status = 'OPEN' LIMIT 1")
                .execute(Tuple.of(id.value()))
                .map(rows -> rows.iterator().next().getUUID("task_id")));
    }

    private static long rowCount(String table) throws Exception {
        return await(pool.query("SELECT count(*) AS n FROM " + table)
                .execute()
                .map(rows -> rows.iterator().next().getLong("n")));
    }
}
