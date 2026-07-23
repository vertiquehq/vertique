// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowRecoveryBridge;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.postgresql.recovery.WorkflowBranchRecoveryConfig;
import dev.vertique.workflow.postgresql.recovery.WorkflowBranchRecoveryServiceImpl;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Vertx;
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
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Dark-mode end-to-end proof for the workflow-007 durable-context feature (PRD AC-8): with zero
 * registered durable namespaces (no encoders, decoders, or
 * {@link dev.vertique.core.context.InboundContextInitializer}s — the bare
 * {@link dev.vertique.context.ContextRuntimeModule} multibinding defaults, contributed transitively
 * by {@link WorkflowPostgresqlModule} with no additional context-provider module installed), the
 * full lifecycle — start, live signal, a parked branch, and a recovery sweep — runs exactly as it
 * did before PRD-WF-007: {@code workflow_instances.metadata} is {@code NULL} throughout (every
 * capture is empty per FR-WF-CTX-010), no bind-related failure occurs (the FR-CTX-157b strict-context
 * guard never trips because the bind gate — explicit carrier {@code null} AND instance metadata
 * {@code null} — always skips the bind, per C2), and every side effect the definition emits still
 * completes. This proves NFR-WF-CTX-005's "no kill switch needed, the NULL-skip chain is the dark
 * path" claim end to end.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class WorkflowDarkModeIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_dark_mode_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowOperations engine;
    static PgWorkflowBranchRecoveryService recovery;
    static Vertx vertxRef;

    /** Idempotency-keyed start payload for the dark-mode fanout. */
    record Start(String orderId) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "dark-mode-" + orderId;
        }
    }

    /** State for the dark-mode fanout. */
    record State(String orderId) {}

    /** Contract marker for the dark-mode fanout definition. */
    interface DarkModeContract {}

    /**
     * Fork with a single {@code ALL_REQUIRED} branch waiting on a signal. After {@code start}, the
     * single branch is WAITING and the parent instance is parked at JOIN. The test delivers the
     * fork-triggering signal live (an instance-owned binder-row drive per S4), then forces the
     * branch into a stale-scheduled state so the recovery sweep re-drives it to completion,
     * exercising the S5 branch-recovery carrier seam with zero registered namespaces.
     */
    static final WorkflowDefinition<State, DarkModeContract> DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<DarkModeContract> contract() {
            return DarkModeContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "dark-mode-fanout";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(Start.class, p -> new State(p.orderId()))
                    .waitForSignal("wait-start", "signal-start", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("fork")
                    .build()
                    .fork("fork")
                    .branch("a", "wait-a")
                    .join("join")
                    .waitForSignal("wait-a", "signal-a", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("complete-a")
                    .build()
                    .complete("complete-a")
                    .join("join")
                    .allRequired((s, results) -> s)
                    .toStep("done")
                    .endJoin()
                    .complete("done");
            wf.initialStep("wait-start");
        }
    };

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

        // DarkModeTestComponent installs ONLY WorkflowPostgresqlModule + DarkModeTestModule — no
        // ContextRuntimeModule multibinding contributor, no CorrelationContextModule, no tenant
        // codec. WorkflowEngineModule (included transitively) still includes ContextRuntimeModule,
        // whose @Multibinds declarations supply the empty encoder/decoder/initializer sets — that
        // absence IS the dark-mode fixture (zero registered durable namespaces).
        DarkModeTestComponent component =
                DaggerWorkflowDarkModeIT_DarkModeTestComponent.factory().create(pool, Clock.systemUTC());

        engine = component.workflowOperations();
        recovery = new PgWorkflowBranchRecoveryService(
                component.transactionRunner(),
                component.branchTokenRepository(),
                component.instanceRepository(),
                component.recoveryBridge(),
                component.workflowRegistry(),
                Clock.systemUTC());
        ctx.completeNow();
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    /**
     * Test-only Dagger module registering {@link #DEFINITION} and the infrastructure providers the
     * PostgreSQL repository/engine graph needs. Deliberately contributes NO durable-context codec —
     * this is the dark-mode fixture.
     */
    @Module
    static class DarkModeTestModule {

        @Provides
        @IntoSet
        static WorkflowContributor darkModeContributor() {
            return registry -> registry.register(DEFINITION);
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
     * Dagger component for the dark-mode IT. Installs only {@link WorkflowPostgresqlModule} (which
     * transitively includes {@link dev.vertique.workflow.engine.WorkflowEngineModule} and,
     * through it, {@link dev.vertique.context.ContextRuntimeModule}'s empty multibinding defaults)
     * and {@link DarkModeTestModule} — no additional context-provider module is installed, so zero
     * durable namespaces are registered.
     */
    @Singleton
    @Component(modules = {WorkflowPostgresqlModule.class, DarkModeTestModule.class})
    interface DarkModeTestComponent {

        WorkflowOperations workflowOperations();

        WorkflowTransactionRunner<SqlClient> transactionRunner();

        BranchTokenRepository<SqlClient> branchTokenRepository();

        WorkflowInstanceRepository<SqlClient> instanceRepository();

        WorkflowRecoveryBridge recoveryBridge();

        dev.vertique.workflow.registry.WorkflowRegistry workflowRegistry();

        @Component.Factory
        interface Factory {
            DarkModeTestComponent create(@BindsInstance Pool pool, @BindsInstance Clock clock);
        }
    }

    /**
     * Runs a test's async chain on a duplicated Vert.x context.
     *
     * <p>Even in dark mode, {@link dev.vertique.context.DefaultContextHolder} write paths (bind /
     * install) require a duplicated context (per FR-CTX-157b) — production dispatch entries always
     * reach the engine via a duplicate; this IT calls the engine directly from a JUnit thread, so it
     * must establish the duplicate itself, mirroring {@code PgWorkflowBranchRecoveryServiceIT}.
     *
     * @param body the test chain to run; its terminal {@code onComplete} signals the test context
     */
    private static void runOnDuplicatedContext(Runnable body) {
        io.vertx.core.internal.ContextInternal dup =
                ((io.vertx.core.internal.ContextInternal) vertxRef.getOrCreateContext()).duplicate();
        dup.runOnContext(unused -> body.run());
    }

    /**
     * Reads {@code workflow_instances.metadata} for the given instance id.
     *
     * @param id the workflow instance id
     * @return a future completing with {@code true} when the {@code metadata} column is SQL NULL
     */
    private static io.vertx.core.Future<Boolean> metadataIsNull(java.util.UUID id) {
        return pool.preparedQuery("SELECT metadata FROM workflow_instances WHERE id = $1")
                .execute(Tuple.of(id))
                .map(rs -> rs.iterator().next().getValue("metadata") == null);
    }

    @Test
    @DisplayName("AC-8 — dark mode: start, live signal, and recovery sweep leave metadata NULL throughout"
            + " with no bind-related failure")
    void darkMode_startSignalRecovery_metadataAlwaysNullAndBehaviorByteIdenticalToToday(VertxTestContext ctx) {
        Start payload = new Start("dm-1");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);

        runOnDuplicatedContext(() -> engine.start(cmd)
                // Post-start: metadata must be NULL (empty ambient capture per FR-WF-CTX-010).
                .compose(id -> metadataIsNull(id.value()).compose(isNull -> {
                    assertTrue(
                            isNull, "post-start workflow_instances.metadata must be NULL (no namespaces registered)");
                    return io.vertx.core.Future.succeededFuture(id);
                }))
                // Live signal: instance-owned binder-row drive (S4) triggers the fork.
                .compose(id -> engine.signal(id, "signal-start", "go", "dark-mode-dedup-start")
                        .map(v -> id))
                .compose(id -> metadataIsNull(id.value()).compose(isNull -> {
                    assertTrue(isNull, "post-signal workflow_instances.metadata must remain NULL");
                    return io.vertx.core.Future.succeededFuture(id);
                }))
                // Park the fork's single branch: force it into RETRY_SCHEDULED so the recovery
                // sweep re-drives it through completion and the join.
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET status = 'RETRY_SCHEDULED',"
                                + " current_step_id = 'complete-a', wait_type = NULL, wait_key = NULL,"
                                + " next_retry_at = $1, version = version + 1"
                                + " WHERE workflow_id = $2 AND branch_id = 'a'")
                        .execute(Tuple.of(
                                OffsetDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC), id.value()))
                        .map(v -> id))
                .compose(id -> recovery.sweepOnce(Instant.now(), 10).map(id))
                .compose(id -> metadataIsNull(id.value()).compose(isNull -> {
                    assertTrue(isNull, "post-recovery-sweep workflow_instances.metadata must remain NULL");
                    return io.vertx.core.Future.succeededFuture(id);
                }))
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(
                            ar.succeeded(),
                            "dark-mode lifecycle must complete without any bind-related" + " failure: " + ar.cause());
                    assertEquals(
                            WorkflowStatus.COMPLETED,
                            ar.result().instance().status(),
                            "the recovered branch must drive the ALL_REQUIRED join to completion,"
                                    + " exactly as it did before PRD-WF-007 with no context registered");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("AC-8 — the WorkflowBranchRecoveryServiceImpl adapter also completes cleanly in dark mode")
    void darkMode_reconcileAdapterCompletesCleanly(VertxTestContext ctx) {
        WorkflowBranchRecoveryServiceImpl adapter = new WorkflowBranchRecoveryServiceImpl(
                () -> recovery, new WorkflowBranchRecoveryConfig(10, java.time.Duration.ofSeconds(1)));

        Start payload = new Start("dm-2");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);

        runOnDuplicatedContext(() -> engine.start(cmd)
                .compose(id -> engine.signal(id, "signal-start", "go", "dark-mode-dedup-adapter")
                        .map(v -> id))
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET status = 'RETRY_SCHEDULED',"
                                + " current_step_id = 'complete-a', wait_type = NULL, wait_key = NULL,"
                                + " next_retry_at = $1, version = version + 1"
                                + " WHERE workflow_id = $2 AND branch_id = 'a'")
                        .execute(Tuple.of(
                                OffsetDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC), id.value()))
                        .map(v -> id))
                .compose(id -> adapter.reconcile().map(id))
                .compose(id -> metadataIsNull(id.value()).compose(isNull -> {
                    assertTrue(isNull, "workflow_instances.metadata must remain NULL through the adapter path too");
                    return io.vertx.core.Future.succeededFuture(id);
                }))
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "adapter reconcile should succeed in dark mode: " + ar.cause());
                    assertEquals(
                            WorkflowStatus.COMPLETED,
                            ar.result().instance().status(),
                            "adapter-driven recovery must complete the join exactly as before PRD-WF-007");
                    ctx.completeNow();
                })));
    }
}
