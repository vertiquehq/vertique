// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static dev.vertique.workflow.postgresql.engine.ScopedApprovalFixture.ACME;
import static dev.vertique.workflow.postgresql.engine.ScopedApprovalFixture.APPROVAL_ID;
import static dev.vertique.workflow.postgresql.engine.ScopedApprovalFixture.FORK_ID;
import static dev.vertique.workflow.postgresql.engine.ScopedApprovalFixture.MIGRATION_ID;
import static org.assertj.core.api.Assertions.assertThat;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.logging.LoggingContextModule;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.engine.WorkflowRecoveryBridge;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.migration.WorkflowMigrationHandler;
import dev.vertique.workflow.migration.WorkflowMigrationModule;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TimerFiringResult;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import dev.vertique.workflow.ops.TransactionalTimerCallbacks;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.Tuple;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Real PostgreSQL proof that every runtime lookup of a pinned plan uses the full pin of the instance: its
 * definition scope, id and version.
 *
 * <p>Each definition exists in the default scope and in {@code acme} with different step graphs (see
 * {@link ScopedApprovalFixture}). The engine has no scoped start yet, so an instance pinned to {@code acme} is made
 * by starting it in the default scope and then stamping its row with the {@code acme} definition scope and plan
 * hash. The graphs share their step ids and initial state up to the first wait, so the stamped row is a valid row
 * for the {@code acme} plan. The graph holds no definition store, so both scopes may hold one id.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class ScopedPinLookupIT {

    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(10);

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_scoped_pin_test")
            .withMigration("classpath:db/migration/workflow");

    static Vertx vertxRef;
    static Pool pool;

    // --- Host ---

    /** Test module: the fixture definitions, the migration handler and a recorder that stores standalone timers. */
    @Module
    static class ApprovalHostModule {

        @Provides
        @IntoSet
        static WorkflowContributor definitions() {
            return ScopedApprovalFixture.contributor();
        }

        @Provides
        @IntoSet
        static WorkflowMigrationHandler<?, ?> migration() {
            return ScopedApprovalFixture.MIGRATION;
        }

        @Provides
        @IntoSet
        static WorkflowMigrationHandler<?, ?> acmeMigration() {
            return ScopedApprovalFixture.ACME_MIGRATION;
        }

        @Provides
        @IntoSet
        @WorkflowRecorders
        static WorkflowSideEffectRecorder<SqlClient> timerRecorder(PgTimerStore timerStore) {
            return new WorkflowSideEffectRecorder<>() {
                @Override
                public IntentKind kind() {
                    return IntentKind.WORKFLOW_TIMER;
                }

                @Override
                public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
                    UUID timerId = UUID.randomUUID();
                    Instant fireAt = intent.payload() instanceof Instant instant
                            ? instant
                            : Instant.now().plusSeconds(300);
                    TimerRecord record = new TimerRecord(
                            timerId,
                            (WorkflowInstanceId) intent.correlation().workflowId(),
                            intent.targetId(),
                            fireAt,
                            TimerStatus.SCHEDULED,
                            UUID.randomUUID(),
                            Instant.now(),
                            null,
                            null,
                            null,
                            null,
                            TimerPurpose.STANDALONE,
                            null,
                            null,
                            null,
                            null,
                            DurableMetadata.empty());
                    return timerStore.insertScheduled(record, tx).map(v -> RecorderResult.ofTimer(timerId));
                }
            };
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

    /** One generated engine generation over the shared database. */
    @Singleton
    @Component(
            modules = {
                WorkflowPostgresqlModule.class,
                WorkflowMigrationModule.class,
                ContextRuntimeModule.class,
                LoggingContextModule.class,
                ApprovalHostModule.class
            })
    interface ApprovalHost {

        WorkflowOperations workflowOperations();

        WorkflowExecutionProbe workflowExecutionProbe();

        TransactionalTaskCallbacks<SqlClient> taskCallbacks();

        TransactionalTimerCallbacks<SqlClient> timerCallbacks();

        WorkflowTransactionRunner<SqlClient> transactionRunner();

        BranchTokenRepository<SqlClient> branchTokenRepository();

        WorkflowInstanceRepository<SqlClient> instanceRepository();

        WorkflowRecoveryBridge recoveryBridge();

        WorkflowRegistry workflowRegistry();

        dev.vertique.workflow.postgresql.registry.PgWorkflowDefinitionRegistryStore pgWorkflowDefinitionRegistryStore();

        dev.vertique.workflow.registry.store.WorkflowDefinitionLifecycle definitionLifecycle();

        @Component.Factory
        interface Factory {
            ApprovalHost create(@BindsInstance Pool pool, @BindsInstance Clock clock);
        }
    }

    /** An engine generation and the recovery sweep wired over it. */
    private record Engine(ApprovalHost host, PgWorkflowBranchRecoveryService recovery) {}

    private static Engine newEngine() {
        ApprovalHost host = DaggerScopedPinLookupIT_ApprovalHost.factory().create(pool, Clock.systemUTC());
        DefaultVersionSeed.recordAndActivate(
                host.pgWorkflowDefinitionRegistryStore(),
                host.definitionLifecycle(),
                host.workflowRegistry(),
                APPROVAL_ID,
                FORK_ID);
        PgWorkflowBranchRecoveryService recovery = new PgWorkflowBranchRecoveryService(
                host.transactionRunner(),
                host.branchTokenRepository(),
                host.instanceRepository(),
                host.recoveryBridge(),
                host.workflowRegistry(),
                Clock.systemUTC());
        return new Engine(host, recovery);
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
        ctx.completeNow();
    }

    @BeforeEach
    void truncateTables() throws Exception {
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
    @DisplayName("signal, task, timer and resume follow the definition scope")
    void signalTaskTimerAndResumeFollowTheDefinitionScope() throws Exception {
        // Given: instance D started in the default scope and instance A started and stamped as pinned to the acme
        // plan of the same id and version, both waiting for submit
        Engine first = newEngine();
        WorkflowInstanceId defaultInstance = start(first, APPROVAL_ID, "d", null);
        WorkflowInstanceId acmeInstance = start(first, APPROVAL_ID, "a", null);
        String acmeHash = planHash(first, ACME, APPROVAL_ID, 1L);
        assertThat(acmeHash).isNotEqualTo(planHash(first, WorkflowScopes.DEFAULT, APPROVAL_ID, 1L));
        ScopedApprovalFixture.stampScope(pool, acmeInstance, ACME, acmeHash);

        // When: both receive submit, the engine is rebuilt on the same database, both tasks complete with
        // approve, and both timers fire
        signal(first, defaultInstance, "submit", "k-submit-d");
        signal(first, acmeInstance, "submit", "k-submit-a");
        String defaultRole = openTaskRole(defaultInstance);
        String acmeRole = openTaskRole(acmeInstance);
        Engine second = newEngine();
        assertThat(completeTask(second, openTaskId(defaultInstance), "k-approve-d"))
                .isEqualTo(TaskMutationResult.APPLIED);
        assertThat(completeTask(second, openTaskId(acmeInstance), "k-approve-a"))
                .isEqualTo(TaskMutationResult.APPLIED);
        assertThat(fireTimer(second, defaultInstance)).isEqualTo(TimerFiringResult.APPLIED);
        assertThat(fireTimer(second, acmeInstance)).isEqualTo(TimerFiringResult.APPLIED);

        // Then: each task had the role of its own plan
        assertThat(defaultRole).isEqualTo("clerk");
        assertThat(acmeRole).isEqualTo("finance");

        // and: D ran only the default plan to its end
        WorkflowInstance defaultRow = row(second, defaultInstance);
        assertThat(defaultRow.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(trail(defaultRow)).containsExactly("default:submit", "default:review");

        // and: A ran only the acme plan and waits for finance-ok, which only that plan has
        WorkflowInstance acmeRow = row(second, acmeInstance);
        assertThat(acmeRow.status()).isEqualTo(WorkflowStatus.WAITING);
        assertThat(acmeRow.waitType()).isEqualTo(WaitType.SIGNAL);
        assertThat(acmeRow.currentStepId()).isEqualTo("finance-ok");
        assertThat(trail(acmeRow)).containsExactly("acme:submit", "acme:review");

        // and: both rows keep their definition scope
        assertThat(defaultRow.definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(acmeRow.definitionScopeKey()).isEqualTo(ACME);
        assertThat(definitionScopeColumn(defaultInstance)).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(definitionScopeColumn(acmeInstance)).isEqualTo(ACME);
        assertThat(acmeRow.planHash()).isEqualTo(acmeHash);
    }

    @Test
    @DisplayName("fork/join recovery and migration follow the definition scope")
    void forkJoinRecoveryAndMigrationFollowTheDefinitionScope() throws Exception {
        // Given: fork instances D and A parked for branch recovery, A stamped as pinned to the acme plan
        Engine engine = newEngine();
        WorkflowInstanceId defaultFork = start(engine, FORK_ID, "fd", null);
        WorkflowInstanceId acmeFork = start(engine, FORK_ID, "fa", null);
        ScopedApprovalFixture.stampScope(pool, acmeFork, ACME, planHash(engine, ACME, FORK_ID, 1L));
        parkBranchB(defaultFork);
        parkBranchB(acmeFork);

        // and: instances D2 and A2 on version 1 of the migrated definition, A2 stamped as pinned to the acme plan,
        // with version 2 present in both scopes and a handler from version 1 to version 2
        WorkflowInstanceId defaultMigrating = start(engine, MIGRATION_ID, "md", 1L);
        WorkflowInstanceId acmeMigrating = start(engine, MIGRATION_ID, "ma", 1L);
        ScopedApprovalFixture.stampScope(pool, acmeMigrating, ACME, planHash(engine, ACME, MIGRATION_ID, 1L));
        String defaultV2Hash = planHash(engine, WorkflowScopes.DEFAULT, MIGRATION_ID, 2L);
        String acmeV2Hash = planHash(engine, ACME, MIGRATION_ID, 2L);
        assertThat(acmeV2Hash).isNotEqualTo(defaultV2Hash);

        // When: the branch recovery sweep runs, and then D2 and A2 are migrated to version 2
        await(inContext(() -> engine.recovery().sweepOnce(Instant.now(), 10)));
        await(inContext(() -> engine.host().workflowOperations().migrate(defaultMigrating, 2L)));
        await(engine.host().workflowExecutionProbe().awaitIdle(defaultMigrating, IDLE_TIMEOUT));
        await(inContext(() -> engine.host().workflowOperations().migrate(acmeMigrating, 2L)));
        await(engine.host().workflowExecutionProbe().awaitIdle(acmeMigrating, IDLE_TIMEOUT));

        // Then: D joined into notify-default and A into notify-acme
        WorkflowInstance defaultForkRow = row(engine, defaultFork);
        assertThat(defaultForkRow.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(defaultForkRow.currentStepId()).isEqualTo("notify-default");
        assertThat(trail(defaultForkRow)).containsExactly("default:join");
        WorkflowInstance acmeForkRow = row(engine, acmeFork);
        assertThat(acmeForkRow.status()).isEqualTo(WorkflowStatus.COMPLETED);
        assertThat(acmeForkRow.currentStepId()).isEqualTo("notify-acme");
        assertThat(trail(acmeForkRow)).containsExactly("acme:join");

        // and: D2 carries the default version 2 plan hash and A2 the acme version 2 plan hash
        WorkflowInstance defaultMigratedRow = row(engine, defaultMigrating);
        assertThat(defaultMigratedRow.definitionVersion()).isEqualTo(2L);
        assertThat(defaultMigratedRow.planHash()).isEqualTo(defaultV2Hash);
        WorkflowInstance acmeMigratedRow = row(engine, acmeMigrating);
        assertThat(acmeMigratedRow.definitionVersion()).isEqualTo(2L);
        assertThat(acmeMigratedRow.planHash()).isEqualTo(acmeV2Hash);

        // and: every row keeps its definition scope
        assertThat(defaultForkRow.definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(acmeForkRow.definitionScopeKey()).isEqualTo(ACME);
        assertThat(defaultMigratedRow.definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(acmeMigratedRow.definitionScopeKey()).isEqualTo(ACME);
        assertThat(definitionScopeColumn(acmeFork)).isEqualTo(ACME);
        assertThat(definitionScopeColumn(acmeMigrating)).isEqualTo(ACME);
    }

    // --- Helpers ---

    /**
     * Runs the action on a duplicated Vert.x context, as every production dispatch entry does.
     */
    private static <T> Future<T> inContext(Supplier<Future<T>> action) {
        Promise<T> result = Promise.promise();
        ContextInternal duplicated = ((ContextInternal) vertxRef.getOrCreateContext()).duplicate();
        duplicated.runOnContext(v -> {
            try {
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

    private static WorkflowInstanceId start(Engine engine, String definitionId, String key, Long version)
            throws Exception {
        StartCommand command = new StartCommand(
                definitionId,
                ScopedApprovalFixture.ApprovalContract.class.getName(),
                new ScopedApprovalFixture.StartApproval(key),
                "k-start-" + definitionId + "-" + key,
                null,
                null,
                version,
                null,
                null);
        WorkflowInstanceId started =
                await(inContext(() -> engine.host().workflowOperations().start(command)));
        await(engine.host().workflowExecutionProbe().awaitIdle(started, IDLE_TIMEOUT));
        return started;
    }

    private static void signal(Engine engine, WorkflowInstanceId id, String signalName, String dedupKey)
            throws Exception {
        await(inContext(() -> engine.host().workflowOperations().signal(id, signalName, "payload", dedupKey)));
        await(engine.host().workflowExecutionProbe().awaitIdle(id, IDLE_TIMEOUT));
    }

    private static TaskMutationResult completeTask(Engine engine, UUID taskId, String idempotencyKey) throws Exception {
        TaskCompletionCommand command = new TaskCompletionCommand(
                taskId, "approve", null, idempotencyKey, new WorkflowActor.User("user-1"), null);
        TaskMutationResult completed = await(inContext(
                () -> pool.withTransaction(tx -> engine.host().taskCallbacks().taskCompleted(command, tx))));
        await(engine.host().workflowExecutionProbe().awaitAllIdle(IDLE_TIMEOUT));
        return completed;
    }

    private static TimerFiringResult fireTimer(Engine engine, WorkflowInstanceId id) throws Exception {
        WorkflowInstance waiting = row(engine, id);
        assertThat(waiting.waitType()).isEqualTo(WaitType.TIMER);
        UUID timerId = UUID.fromString(waiting.waitKey());
        TimerFiringResult fired = await(inContext(
                () -> pool.withTransaction(tx -> engine.host().timerCallbacks().timerFired(id, timerId, tx))));
        await(engine.host().workflowExecutionProbe().awaitIdle(id, IDLE_TIMEOUT));
        return fired;
    }

    private static String planHash(Engine engine, String scopeKey, String definitionId, long version) {
        return engine.host()
                .workflowRegistry()
                .resolve(new DefinitionKey(scopeKey, definitionId, version))
                .plan()
                .planHash();
    }

    private static WorkflowInstance row(Engine engine, WorkflowInstanceId id) throws Exception {
        return await(engine.host().instanceRepository().findById(id)).orElseThrow();
    }

    private static List<String> trail(WorkflowInstance instance) {
        return new JsonObject(instance.stateJson())
                .getJsonArray("trail").stream().map(String::valueOf).toList();
    }

    private static void parkBranchB(WorkflowInstanceId id) throws Exception {
        await(pool.preparedQuery("UPDATE workflow_branch_tokens SET status = 'RETRY_SCHEDULED',"
                        + " current_step_id = 'b-done', wait_type = NULL, wait_key = NULL,"
                        + " next_retry_at = $1, version = version + 1"
                        + " WHERE workflow_id = $2 AND branch_id = 'b'")
                .execute(Tuple.of(
                        OffsetDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC), id.value())));
    }

    private static String definitionScopeColumn(WorkflowInstanceId id) throws Exception {
        return await(pool.preparedQuery("SELECT definition_scope_key FROM workflow_instances WHERE id = $1")
                .execute(Tuple.of(id.value()))
                .map(rows -> rows.iterator().next().getString("definition_scope_key")));
    }

    private static UUID openTaskId(WorkflowInstanceId id) throws Exception {
        return await(pool.preparedQuery(
                        "SELECT task_id FROM workflow_tasks WHERE workflow_id = $1 AND status = 'OPEN' LIMIT 1")
                .execute(Tuple.of(id.value()))
                .map(rows -> rows.iterator().next().getUUID("task_id")));
    }

    private static String openTaskRole(WorkflowInstanceId id) throws Exception {
        return await(pool.preparedQuery(
                        "SELECT assignee_key FROM workflow_tasks WHERE workflow_id = $1 AND status = 'OPEN' LIMIT 1")
                .execute(Tuple.of(id.value()))
                .map(rows -> rows.iterator().next().getString("assignee_key")));
    }
}
