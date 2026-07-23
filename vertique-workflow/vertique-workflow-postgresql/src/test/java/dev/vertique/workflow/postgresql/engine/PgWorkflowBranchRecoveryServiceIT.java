// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.BindsInstance;
import dagger.Component;
import dagger.Module;
import dagger.Provides;
import dagger.multibindings.IntoSet;
import dev.vertique.context.ContextRuntimeModule;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableContextMetadataEncoder;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.correlation.CorrelationContextModule;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.inboxoutbox.DestinationType;
import dev.vertique.inboxoutbox.OutboxEntry;
import dev.vertique.inboxoutbox.OutboxMetadata;
import dev.vertique.inboxoutbox.OutboxService;
import dev.vertique.inboxoutbox.postgresql.PgInboxOutboxRepository;
import dev.vertique.logging.LoggingContextModule;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowRecoveryBridge;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.TenantCtx;
import dev.vertique.workflow.engine.testsupport.TenantCtxCodec;
import dev.vertique.workflow.events.binding.WorkflowEventOutboxBinding;
import dev.vertique.workflow.events.compose.WorkflowEventsComposeValidator;
import dev.vertique.workflow.events.di.WorkflowEventsModule;
import dev.vertique.workflow.events.recorder.WorkflowEventSideEffectRecorder;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.plan.DefaultRaceSafetyTargetRegistry;
import dev.vertique.workflow.plan.WorkflowPlanValidator;
import dev.vertique.workflow.postgresql.recovery.WorkflowBranchRecoveryConfig;
import dev.vertique.workflow.postgresql.recovery.WorkflowBranchRecoveryServiceImpl;
import dev.vertique.workflow.registry.WorkflowContributor;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowRecorders;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WorkflowStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.Row;
import io.vertx.sqlclient.RowSet;
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
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Verifies the branch recovery service evaluates the join after a recovered branch reaches a
 * terminal state (PRD-WF-002 §A.4.3, FR-WF-PAR-046), and that recovery-time context binding is
 * carrier-authoritative with instance-fill (AC-2, PRD FR-WF-CTX-020/024/026).
 *
 * <p>Before the fix, {@code PgWorkflowBranchRecoveryService.demoteOne} marked a stale RUNNING
 * branch FAILED and returned without invoking {@code evaluateJoin}; the parent instance stayed
 * parked at {@link dev.vertique.workflow.state.WaitType#JOIN} forever, even though an
 * ALL_REQUIRED join would have taken the failure route. This IT pins the regression: once a
 * stale RUNNING branch is demoted to FAILED with no retry budget, the join must be evaluated
 * and the instance must terminate (FAILED, since this definition declares no failure route).
 *
 * <p>The AC-2 tests (below the pre-existing join-evaluation tests) use a second Dagger component
 * ({@link Ac2TestComponent}) wired with a real {@link DurableContextPropagator} (tenant + real
 * {@code CorrelationContext} namespaces), a real outbox (via {@link PgInboxOutboxRepository}) for
 * the {@code WORKFLOW_EVENT} recorder, and a capturing {@code SERVICE} recorder that also writes
 * to the real {@code outbox} table — so the assertions read the actual persisted
 * {@code outbox.metadata->'context'} document, proving carrier authority + recovery-time instance
 * fill end to end.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class PgWorkflowBranchRecoveryServiceIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_branch_recovery_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowOperations engine;
    static PgWorkflowBranchRecoveryService recovery;
    static Vertx vertxRef;

    // --- AC-2 fixture ---

    /** Recovery service wired with a real propagator, used only by the AC-2 tests. */
    static PgWorkflowBranchRecoveryService ac2Recovery;

    /** Engine handle wired for the AC-2 definition (real outbox + real correlation/tenant context). */
    static WorkflowOperations ac2Engine;

    /** Captures every SERVICE {@link WorkflowSideEffectIntent} routed during an AC-2 test's sweep. */
    static final List<WorkflowSideEffectIntent> ac2CapturedServiceIntents = new ArrayList<>();

    /** Context holder backing the AC-2 fixture's real {@link DurableContextPropagator}. */
    static dev.vertique.core.context.ContextHolder ac2Holder;

    /** Correlation factory used to mint a concrete {@code CorrelationContext} for AC-2 tests. */
    static dev.vertique.correlation.CorrelationContextFactory ac2CorrelationFactory;

    record StartFanout(String orderId) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "recovery-" + orderId;
        }
    }

    record State(String orderId) {}

    interface FanoutContract {}

    /**
     * Fork with two signal-wait branches under {@code ALL_REQUIRED}. After {@code start}, both
     * branches are WAITING and the instance is parked at JOIN. The test then forces branch
     * {@code a} into a stale-RUNNING state to exercise the demote → FAILED → evaluateJoin chain.
     */
    static final WorkflowDefinition<State, FanoutContract> DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<FanoutContract> contract() {
            return FanoutContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "recovery-fanout";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(StartFanout.class, p -> new State(p.orderId()))
                    .fork("fork")
                    .branch("a", "wait-a")
                    .branch("b", "wait-b")
                    .join("join")
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
                    .join("join")
                    .allRequired((s, results) -> s)
                    .toStep("done")
                    .endJoin()
                    .complete("done");
            wf.initialStep("fork");
        }
    };

    /** Idempotency-keyed start payload for the AC-2 single-branch fanout. */
    record Ac2Start(String orderId) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "ac2-" + orderId;
        }
    }

    /** State for the AC-2 single-branch fanout. */
    record Ac2State(String orderId) {}

    /** Contract marker for the AC-2 single-branch fanout. */
    interface Ac2Contract {}

    /** Contract marker for the AC-2 live-fork-absorption fanout (distinct from {@link Ac2Contract}
     *  because {@link dev.vertique.workflow.registry.DefaultWorkflowRegistry#register} keys plans
     *  by contract type, not just definitionId). */
    interface Ac2LiveContract {}

    /**
     * Fork with a single {@code ALL_REQUIRED} branch: {@code wait-a} (signal wait) →
     * {@code dispatch-a} ({@link dev.vertique.workflow.plan.ServiceDispatchNode}, emitting a
     * SERVICE outbox entry) → {@code complete-a}. The join's success route advances the parent to
     * {@code done} (a {@link dev.vertique.workflow.plan.CompleteNode}), which emits
     * {@code WORKFLOW_COMPLETED} — a {@code WORKFLOW_EVENT} outbox entry — via
     * {@code ForkJoinCoordinator.advanceInstanceAfterJoin}'s continuation into
     * {@code WorkflowTransitionDriver}.
     *
     * <p>Recovery tests never let the branch reach {@code dispatch-a} live — they seed a
     * degraded token (correlation only, no tenant) and force the branch row to
     * {@code RETRY_SCHEDULED} at {@code current_step_id = "dispatch-a"} directly, per AC-2's
     * documented test-setup technique (simulating the §2-gap-1 degraded/pre-feature fork).
     */
    static final WorkflowDefinition<Ac2State, Ac2Contract> AC2_DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<Ac2Contract> contract() {
            return Ac2Contract.class;
        }

        @Override
        public Class<Ac2State> stateType() {
            return Ac2State.class;
        }

        @Override
        public String definitionId() {
            return "ac2-carrier-row-fill-fanout";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<Ac2State> wf) {
            wf.init(Ac2Start.class, p -> new Ac2State(p.orderId()))
                    .fork("fork")
                    .branch("a", "wait-a")
                    .join("join")
                    .waitForSignal("wait-a", "signal-a", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("dispatch-a")
                    .build()
                    .dispatch(
                            "dispatch-a",
                            "ac2.service.a",
                            st -> java.util.Map.of("orderId", st.orderId()),
                            "complete-a")
                    .complete("complete-a")
                    .join("join")
                    .allRequired((s, results) -> s)
                    .toStep("done")
                    .endJoin()
                    .complete("done");
            wf.initialStep("fork");
        }
    };

    /**
     * Instance-level {@code wait-start} signal that leads into the same single-branch fork/join
     * shape as {@link #AC2_DEFINITION}. Used only by the "inline branch-drive fill" test: the
     * instance-level signal delivery is a binder row (S4) — the binder installs the effective
     * (base + instance-fill) document before the drive proceeds, so the fork dispatch reached
     * later in the SAME drive captures the branch token's metadata from that already-filled
     * holder state (the "fork absorption" consequence documented in FR-WF-CTX-024 / ADR-0147).
     */
    static final WorkflowDefinition<Ac2State, Ac2LiveContract> AC2_LIVE_DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<Ac2LiveContract> contract() {
            return Ac2LiveContract.class;
        }

        @Override
        public Class<Ac2State> stateType() {
            return Ac2State.class;
        }

        @Override
        public String definitionId() {
            return "ac2-live-fork-absorption-fanout";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<Ac2State> wf) {
            wf.init(Ac2Start.class, p -> new Ac2State(p.orderId()))
                    .waitForSignal("wait-start", "signal-start", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("fork")
                    .build()
                    .fork("fork")
                    .branch("a", "dispatch-a")
                    .join("join")
                    .dispatch(
                            "dispatch-a",
                            "ac2.service.a",
                            st -> java.util.Map.of("orderId", st.orderId()),
                            "complete-a")
                    .complete("complete-a")
                    .join("join")
                    .allRequired((s, results) -> s)
                    .toStep("done")
                    .endJoin()
                    .complete("done");
            wf.initialStep("wait-start");
        }
    };

    /** Contract marker for the AC-2 demote-terminal-evaluation fanout (distinct from
     *  {@link Ac2Contract} / {@link Ac2LiveContract} for the same registry-keying reason). */
    interface Ac2DemoteContract {}

    /**
     * Fork with a single {@code ALL_REQUIRED} branch and a <b>declared failure route</b>
     * (unlike {@link #DEFINITION}, whose join has no {@code failureStepId}): {@code wait-a}
     * (signal wait) &rarr; {@code join.onFailure("failed-a")} &rarr; {@code failed-a} (a
     * {@link dev.vertique.workflow.plan.FailNode} — {@code CompleteNode} always transitions to
     * {@code COMPLETED} and always emits {@code WORKFLOW_COMPLETED} regardless of step name, so a
     * {@code FailNode} is required to reach {@code WORKFLOW_FAILED}), which emits
     * {@code WORKFLOW_FAILED} — a {@code WORKFLOW_EVENT} outbox entry — via
     * {@code ForkJoinCoordinator.advanceInstanceAfterJoin}'s continuation into
     * {@code WorkflowTransitionDriver.handleFail}.
     *
     * <p>Used only by the demote-path AC-2 test: because the join has a real failure route, the
     * {@code demoteOne} &rarr; {@code evaluateRecoveredBranchIfTerminal} chain drives all the way to
     * an outbox write, giving that test the same observable seam (an
     * {@code outbox.metadata->'context'} document) the other AC-2 tests use for the resume/drive
     * path — proof that the terminal-evaluation work following a demote runs under the merged
     * {@code token.metadata().merge(instance.metadata(), CALLER_WINS)} bind, not just that the
     * demote completed.
     */
    static final WorkflowDefinition<Ac2State, Ac2DemoteContract> AC2_DEMOTE_DEFINITION = new WorkflowDefinition<>() {
        @Override
        public Class<Ac2DemoteContract> contract() {
            return Ac2DemoteContract.class;
        }

        @Override
        public Class<Ac2State> stateType() {
            return Ac2State.class;
        }

        @Override
        public String definitionId() {
            return "ac2-demote-terminal-eval-fanout";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<Ac2State> wf) {
            wf.init(Ac2Start.class, p -> new Ac2State(p.orderId()))
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
                    .onFailure("failed-a")
                    .endJoin()
                    .complete("done")
                    .fail("failed-a", "demote_terminal_evaluation_failure", s -> "join decided FAILED");
            wf.initialStep("fork");
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

        // Wire the engine + its recovery collaborators through Dagger (WorkflowPostgresqlModule
        // transitively includes WorkflowEngineModule, which provides the WorkflowRecoveryBridge
        // binding the recovery sweep needs — wired to the same BranchTransitionEngine /
        // ForkJoinCoordinator the engine itself uses). The DEFINITION is registered via a
        // WorkflowContributor so WorkflowCoreModule pre-populates the WorkflowRegistry, exactly as
        // production composition does.
        RecoveryTestComponent component = DaggerPgWorkflowBranchRecoveryServiceIT_RecoveryTestComponent.factory()
                .create(pool, Clock.systemUTC());

        engine = component.workflowOperations();

        // Construct the recovery service through the engine-graph-provided WorkflowRecoveryBridge.
        // Use the null-propagator public ctor (no DurableContextPropagator) so the per-branch
        // durable-metadata bind is skipped — exactly as the IT did before, avoiding the FR-CTX-157b
        // duplicated-context write guard while the sweep runs off a JUnit-driven async chain.
        recovery = new PgWorkflowBranchRecoveryService(
                component.transactionRunner(),
                component.branchTokenRepository(),
                component.instanceRepository(),
                component.recoveryBridge(),
                component.workflowRegistry(),
                Clock.systemUTC());

        // --- AC-2 fixture: real outbox table + real propagator (tenant + correlation) ---
        // Runs the inbox-outbox migration (separate Flyway history table) alongside the workflow
        // migration already applied by PostgresContainer.withMigration, mirroring
        // WorkflowEventEmissionIT's dual-migration setup.
        Flyway.configure()
                .dataSource(db.jdbcUrl(), db.username(), db.password())
                .locations("classpath:db/migration/inbox-outbox")
                .table("flyway_inbox_outbox_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
                .migrate();

        Ac2TestComponent ac2Component = DaggerPgWorkflowBranchRecoveryServiceIT_Ac2TestComponent.factory()
                .create(pool, Clock.systemUTC());
        ac2Engine = ac2Component.workflowOperations();
        ac2Holder = ac2Component.contextHolder();
        ac2CorrelationFactory = ac2Component.correlationContextFactory();
        ac2Recovery = new PgWorkflowBranchRecoveryService(
                ac2Component.transactionRunner(),
                ac2Component.branchTokenRepository(),
                ac2Component.instanceRepository(),
                ac2Component.recoveryBridge(),
                ac2Component.workflowRegistry(),
                Clock.systemUTC(),
                ac2Component.durableContextPropagator());
        ctx.completeNow();
    }

    // --- Dagger test wiring ---

    /**
     * Test-only Dagger module that registers {@link #DEFINITION} into the engine's
     * {@link dev.vertique.workflow.registry.WorkflowRegistry} via a {@link WorkflowContributor},
     * and supplies the {@link WorkflowPlanValidator} and {@link PgDbExceptionMapper} the
     * PostgreSQL repository / engine graph needs. The {@link Pool} and {@link Clock} are bound
     * per-test through the component factory's {@link BindsInstance} parameters.
     */
    @Module
    static class RecoveryTestModule {

        /**
         * Contributes {@link #DEFINITION} as the sole workflow definition for this component.
         *
         * @return a contributor that registers the recovery-fanout definition
         */
        @Provides
        @IntoSet
        static WorkflowContributor recoveryFanoutContributor() {
            return registry -> registry.register(DEFINITION);
        }

        /**
         * Provides the {@link WorkflowPlanValidator} that checks fork/join pairing.
         *
         * @return the plan validator
         */
        @Provides
        @Singleton
        static WorkflowPlanValidator planValidator() {
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.of()));
        }

        /**
         * Provides the PostgreSQL exception mapper used by the repository / runner stack.
         *
         * @return the exception mapper
         */
        @Provides
        @Singleton
        static PgDbExceptionMapper pgDbExceptionMapper() {
            return new PgDbExceptionMapper();
        }
    }

    /**
     * Dagger component for the branch-recovery IT.
     *
     * <p>Installs {@link WorkflowPostgresqlModule} (which transitively includes
     * {@link dev.vertique.workflow.engine.WorkflowEngineModule}, providing the engine, the repository
     * SPIs bound to their PostgreSQL implementations, and the {@link WorkflowRecoveryBridge}
     * binding), {@link dev.vertique.context.ContextRuntimeModule} and {@link LoggingContextModule}
     * for the durable-context substrate, and {@link RecoveryTestModule} for the test definition and
     * infrastructure providers. The {@link Pool} and {@link Clock} are supplied per-test via the
     * factory.
     */
    @Singleton
    @Component(
            modules = {
                WorkflowPostgresqlModule.class,
                dev.vertique.context.ContextRuntimeModule.class,
                LoggingContextModule.class,
                RecoveryTestModule.class
            })
    interface RecoveryTestComponent {

        /**
         * Returns the workflow operations facade the IT drives via {@code start} / {@code query}.
         *
         * @return workflow operations
         */
        WorkflowOperations workflowOperations();

        /**
         * Returns the transaction runner the recovery service opens its per-branch transactions on.
         *
         * @return the SqlClient-typed transaction runner
         */
        WorkflowTransactionRunner<SqlClient> transactionRunner();

        /**
         * Returns the branch-token repository the recovery service reads recoverable branches from.
         *
         * @return the SqlClient-typed branch-token repository
         */
        BranchTokenRepository<SqlClient> branchTokenRepository();

        /**
         * Returns the instance repository the recovery service resolves parent instances through.
         *
         * @return the SqlClient-typed instance repository
         */
        WorkflowInstanceRepository<SqlClient> instanceRepository();

        /**
         * Returns the Dagger-bound {@link WorkflowRecoveryBridge} so the IT can construct the
         * recovery service under test.
         *
         * @return the recovery bridge
         */
        WorkflowRecoveryBridge recoveryBridge();

        /**
         * Returns the registry pre-populated with {@link #DEFINITION}.
         *
         * @return the workflow registry
         */
        dev.vertique.workflow.registry.WorkflowRegistry workflowRegistry();

        /**
         * Factory binding the per-test {@link Pool} and {@link Clock} instances into the graph.
         */
        @Component.Factory
        interface Factory {

            /**
             * Builds the component over the given pool and clock.
             *
             * @param pool  the per-test PostgreSQL connection pool
             * @param clock the clock used by the engine and repositories
             * @return the assembled component
             */
            RecoveryTestComponent create(@BindsInstance Pool pool, @BindsInstance Clock clock);
        }
    }

    /**
     * SERVICE-kind recorder for the AC-2 tests: writes a real {@code outbox} row (so the test can
     * read {@code outbox.metadata->'context'} back), captures every routed intent for
     * per-row-isolation assertions, and — crucially — reads the durable context via
     * {@link OutboxService#publish}'s {@code mergeCaptured} exactly like the production
     * {@code OutboxSideEffectRecorder}, without needing the full service-target-resolution
     * machinery this IT does not otherwise wire.
     */
    static final class Ac2CapturingServiceRecorder implements WorkflowSideEffectRecorder<SqlClient> {
        private final OutboxService outboxService;

        Ac2CapturingServiceRecorder(OutboxService outboxService) {
            this.outboxService = outboxService;
        }

        @Override
        public IntentKind kind() {
            return IntentKind.SERVICE;
        }

        @Override
        public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
            ac2CapturedServiceIntents.add(intent);
            OutboxEntry entry = OutboxEntry.builder()
                    .destinationType(DestinationType.SERVICE)
                    .destination(intent.targetId())
                    .eventType(intent.targetId())
                    .aggregateType("WorkflowInstance")
                    .aggregateId(intent.correlation().workflowId().value().toString())
                    .payload(intent.payload())
                    .headers(java.util.Map.of())
                    .build();
            return outboxService.publish(tx, entry).map(v -> RecorderResult.empty());
        }
    }

    /**
     * Test-only Dagger module wiring the AC-2 fixture: {@link #AC2_DEFINITION}, the real outbox
     * (via {@link PgInboxOutboxRepository}) backing both the {@code SERVICE} and
     * {@code WORKFLOW_EVENT} recorders, and the {@link TenantCtxCodec} durable-context codec pair
     * contributed into {@link ContextRuntimeModule}'s multibinding sets. {@link CorrelationContextModule}
     * (included on the component) supplies the real {@code CorrelationContext} namespace.
     */
    @Module
    static class Ac2TestModule {

        @Provides
        @IntoSet
        static WorkflowContributor ac2Contributor() {
            return registry -> {
                registry.register(AC2_DEFINITION);
                registry.register(AC2_LIVE_DEFINITION);
                registry.register(AC2_DEMOTE_DEFINITION);
            };
        }

        @Provides
        @Singleton
        static WorkflowPlanValidator ac2PlanValidator() {
            return new WorkflowPlanValidator(new DefaultRaceSafetyTargetRegistry(Set.of()));
        }

        @Provides
        @Singleton
        static PgDbExceptionMapper ac2PgDbExceptionMapper() {
            return new PgDbExceptionMapper();
        }

        @Provides
        @Singleton
        static PgInboxOutboxRepository ac2OutboxRepository(Pool pool, PgDbExceptionMapper exMapper) {
            return new PgInboxOutboxRepository(pool, exMapper);
        }

        /**
         * Mirrors {@code DefaultOutboxService.publish}: captures the currently bound durable
         * context via {@link DurableContextPropagator#mergeCaptured} under the
         * {@link dev.vertique.core.context.DispatchBoundary#OUTBOX} boundary and persists it in the
         * outbox row's {@code metadata.context} section, so the AC-2 assertions read the real
         * carrier-authoritative + instance-filled document off the {@code outbox} table.
         *
         * @param repo       the outbox repository backing the insert
         * @param propagator the real Dagger-wired propagator (tenant + correlation namespaces)
         * @return the outbox service used by both the SERVICE and WORKFLOW_EVENT recorders
         */
        @Provides
        @Singleton
        static OutboxService ac2OutboxService(PgInboxOutboxRepository repo, DurableContextPropagator propagator) {
            return (tx, entry) -> {
                DurableMetadata context = propagator.mergeCaptured(
                        DurableMetadata.empty(), dev.vertique.core.context.DispatchBoundary.OUTBOX);
                OutboxMetadata metadata =
                        new OutboxMetadata(context, dev.vertique.inboxoutbox.OutboxDeliveryMetadata.empty());
                return repo.insert(entry, metadata, UUID.randomUUID(), tx);
            };
        }

        @Provides
        @Singleton
        static WorkflowEventOutboxBinding ac2EventBinding() {
            return new WorkflowEventOutboxBinding(DestinationType.SERVICE, "ac2-event-target");
        }

        @Provides
        @Singleton
        @IntoSet
        static dev.vertique.inboxoutbox.OutboxDestinationHandler ac2EventHandler() {
            return new dev.vertique.inboxoutbox.OutboxDestinationHandler() {
                @Override
                public DestinationType destinationType() {
                    return DestinationType.SERVICE;
                }

                @Override
                public dev.vertique.inboxoutbox.ClaimScope claimScope() {
                    return dev.vertique.inboxoutbox.ClaimScope.all();
                }

                @Override
                public Future<dev.vertique.inboxoutbox.OutboxPublishResult> publish(
                        dev.vertique.inboxoutbox.OutboxEnvelope envelope) {
                    return Future.succeededFuture(dev.vertique.inboxoutbox.OutboxPublishResult.success());
                }
            };
        }

        @Provides
        @Singleton
        static WorkflowEventsComposeValidator ac2EventsValidator(
                Set<dev.vertique.inboxoutbox.OutboxDestinationHandler> handlers, WorkflowEventOutboxBinding binding) {
            return new WorkflowEventsComposeValidator(handlers, binding);
        }

        @Provides
        @IntoSet
        @WorkflowRecorders
        static WorkflowSideEffectRecorder<SqlClient> ac2ServiceRecorder(OutboxService outboxService) {
            return new Ac2CapturingServiceRecorder(outboxService);
        }

        @Provides
        @IntoSet
        static DurableContextMetadataEncoder<?> ac2TenantEncoder() {
            return TenantCtxCodec.encoder();
        }

        @Provides
        @IntoSet
        static DurableContextMetadataDecoder<?> ac2TenantDecoder() {
            return TenantCtxCodec.decoder();
        }
    }

    /**
     * Dagger component for the AC-2 fixture. Installs {@link WorkflowPostgresqlModule},
     * {@link ContextRuntimeModule}, {@link LoggingContextModule}, {@link CorrelationContextModule}
     * (real {@code CorrelationContext} namespace), {@link WorkflowEventsModule} (contributes
     * {@link WorkflowEventSideEffectRecorder} into {@code @WorkflowRecorders}), and
     * {@link Ac2TestModule} for the definition + outbox + tenant-codec wiring.
     */
    @Singleton
    @Component(
            modules = {
                WorkflowPostgresqlModule.class,
                ContextRuntimeModule.class,
                LoggingContextModule.class,
                CorrelationContextModule.class,
                WorkflowEventsModule.class,
                Ac2TestModule.class
            })
    interface Ac2TestComponent {

        WorkflowOperations workflowOperations();

        WorkflowTransactionRunner<SqlClient> transactionRunner();

        BranchTokenRepository<SqlClient> branchTokenRepository();

        WorkflowInstanceRepository<SqlClient> instanceRepository();

        WorkflowRecoveryBridge recoveryBridge();

        dev.vertique.workflow.registry.WorkflowRegistry workflowRegistry();

        DurableContextPropagator durableContextPropagator();

        dev.vertique.core.context.ContextHolder contextHolder();

        dev.vertique.correlation.CorrelationContextFactory correlationContextFactory();

        @Component.Factory
        interface Factory {
            Ac2TestComponent create(@BindsInstance Pool pool, @BindsInstance Clock clock);
        }
    }

    @BeforeEach
    void truncateTables(VertxTestContext ctx) {
        ac2CapturedServiceIntents.clear();
        pool.query("TRUNCATE TABLE workflow_branch_tokens, workflow_join_states, workflow_timers,"
                        + " workflow_history, workflow_dedup, workflow_instances, outbox"
                        + " RESTART IDENTITY CASCADE")
                .execute()
                .onComplete(ar -> ctx.completeNow());
    }

    @AfterAll
    static void tearDown() {
        if (pool != null) {
            pool.close();
        }
    }

    /**
     * Runs a test's async chain on a duplicated Vert.x context.
     *
     * <p>The engine and recovery sweep now obtain a real {@link dev.vertique.context.DurableContextPropagator}
     * from the Dagger graph (the production wiring), so branch drive calls
     * {@code bindFrom(..., DispatchBoundary.WORKFLOW)} which, per FR-CTX-157b, MUST run on a
     * duplicated context. Production dispatch entries (event-bus consumer, Kafka per-record, cron
     * callback, service-method invoker) always reach the engine via a duplicate; this IT calls the
     * engine directly from a JUnit thread, so it must establish the duplicate itself — mirroring
     * {@code DocumentDefinitionFanOutIT}.
     *
     * @param body the test chain to run; its terminal {@code onComplete} signals {@code ctx}
     */
    private static void runOnDuplicatedContext(Runnable body) {
        io.vertx.core.internal.ContextInternal dup =
                ((io.vertx.core.internal.ContextInternal) vertxRef.getOrCreateContext()).duplicate();
        dup.runOnContext(unused -> body.run());
    }

    @Test
    @DisplayName("AC #9 — stale RUNNING branch with no retries left is demoted to FAILED and the join is evaluated")
    void recoverySweepDemotesStaleRunningAndEvaluatesJoin(VertxTestContext ctx) {
        StartFanout payload = new StartFanout("o-rec");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);
        runOnDuplicatedContext(() -> engine.start(cmd)
                // Force branch 'a' into stale RUNNING (no retry budget left).
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET status = 'RUNNING',"
                                + " updated_at = $1, attempt_count = max_attempts - 1 WHERE workflow_id = $2"
                                + " AND branch_id = 'a'")
                        .execute(Tuple.of(
                                OffsetDateTime.ofInstant(Instant.now().minusSeconds(3600), ZoneOffset.UTC), id.value()))
                        .map(id))
                // Run one sweep with a stale threshold of "now" so the row is picked up.
                .compose(id -> recovery.sweepOnce(Instant.now(), 10).map(id))
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "recovery sweep should succeed: " + ar.cause());
                    var view = ar.result();
                    assertEquals(
                            WorkflowStatus.FAILED,
                            view.instance().status(),
                            "instance must terminate FAILED — ALL_REQUIRED with no failureStepId fails the workflow");
                    assertEquals(
                            "join_failed_no_failure_route",
                            view.instance().errorType(),
                            "error must come from the join's no-route fail path, proving evaluateJoin ran");

                    pool.preparedQuery("SELECT status FROM workflow_join_states WHERE workflow_id = $1")
                            .execute(Tuple.of(view.instance().id().value()))
                            .onComplete(jr -> ctx.verify(() -> {
                                int rows = 0;
                                for (var row : jr.result()) {
                                    rows++;
                                    assertEquals(
                                            "FAILED",
                                            row.getString("status"),
                                            "join state must transition OPEN → FAILED via evaluateJoin");
                                }
                                assertEquals(1, rows, "exactly one join_state row must be FAILED");
                                ctx.completeNow();
                            }));
                })));
    }

    @Test
    @DisplayName("Recovery resumes a RETRY_SCHEDULED branch, drives it to COMPLETED, and decides the open join")
    void recoverySweepResumesScheduledBranchAndEvaluatesJoin(VertxTestContext ctx) {
        // Both branches start WAITING at signal nodes; instance is parked at WaitType.JOIN.
        // Then mutate the DB so the join is one drive away from being decided:
        //   branch a: RETRY_SCHEDULED at complete-a (CompleteNode), next_retry_at in the past.
        //   branch b: COMPLETED already.
        // Sweep should drive branch a through CompleteNode → COMPLETED, then evaluateJoin
        // sees both terminal, ALL_REQUIRED decides COMPLETED, instance advances past join.
        StartFanout payload = new StartFanout("o-resume");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);
        runOnDuplicatedContext(() -> engine.start(cmd)
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET status = 'RETRY_SCHEDULED',"
                                + " current_step_id = 'complete-a', wait_type = NULL, wait_key = NULL,"
                                + " next_retry_at = $1, version = version + 1"
                                + " WHERE workflow_id = $2 AND branch_id = 'a'")
                        .execute(Tuple.of(
                                OffsetDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC), id.value()))
                        .map(id))
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET status = 'COMPLETED',"
                                + " wait_type = NULL, wait_key = NULL, version = version + 1"
                                + " WHERE workflow_id = $1 AND branch_id = 'b'")
                        .execute(Tuple.of(id.value()))
                        .map(id))
                .compose(id -> recovery.sweepOnce(Instant.now(), 10).map(id))
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "recovery sweep should succeed: " + ar.cause());
                    var view = ar.result();
                    assertEquals(
                            WorkflowStatus.COMPLETED,
                            view.instance().status(),
                            "ALL_REQUIRED with both branches terminal-success must complete the instance");
                    assertEquals(
                            "done",
                            view.instance().currentStepId(),
                            "instance must advance past the join to the success route");

                    pool.preparedQuery("SELECT status, branch_id FROM workflow_branch_tokens"
                                    + " WHERE workflow_id = $1 ORDER BY branch_id")
                            .execute(Tuple.of(view.instance().id().value()))
                            .compose(branchRows -> {
                                ctx.verify(() -> {
                                    int rows = 0;
                                    for (var row : branchRows) {
                                        rows++;
                                        assertEquals(
                                                "COMPLETED",
                                                row.getString("status"),
                                                "every branch must end COMPLETED");
                                    }
                                    assertEquals(2, rows);
                                });
                                return pool.preparedQuery(
                                                "SELECT status FROM workflow_join_states WHERE workflow_id = $1")
                                        .execute(Tuple.of(view.instance().id().value()));
                            })
                            .onComplete(jr -> ctx.verify(() -> {
                                int rows = 0;
                                for (var row : jr.result()) {
                                    rows++;
                                    assertEquals(
                                            "COMPLETED",
                                            row.getString("status"),
                                            "join state must transition OPEN → COMPLETED via evaluateJoinForRecoveredBranch");
                                }
                                assertEquals(1, rows);
                                ctx.completeNow();
                            }));
                })));
    }

    @Test
    @DisplayName("Recovery demote keeps a stale RUNNING branch RETRY_SCHEDULED when retry budget remains")
    void recoverySweepDemotesStaleRunningWhenRetriesRemain(VertxTestContext ctx) {
        // Bump max_attempts at the row level so canRetry = (0+1 < 3) is true. The runtime policy
        // declared on the fork is irrelevant — recovery reads max_attempts directly from the row.
        StartFanout payload = new StartFanout("o-retry");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);
        Instant past = Instant.now().minusSeconds(3600);
        runOnDuplicatedContext(() -> engine.start(cmd)
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET status = 'RUNNING',"
                                + " updated_at = $1, attempt_count = 0, max_attempts = 3, version = version + 1"
                                + " WHERE workflow_id = $2 AND branch_id = 'a'")
                        .execute(Tuple.of(OffsetDateTime.ofInstant(past, ZoneOffset.UTC), id.value()))
                        .map(id))
                .compose(id -> recovery.sweepOnce(Instant.now(), 10).map(id))
                .compose(id -> pool.preparedQuery("SELECT status, attempt_count, next_retry_at, last_error_type"
                                + " FROM workflow_branch_tokens WHERE workflow_id = $1 AND branch_id = 'a'")
                        .execute(Tuple.of(id.value()))
                        .map(rs -> {
                            var row = rs.iterator().next();
                            return new Object[] {
                                row.getString("status"),
                                row.getInteger("attempt_count"),
                                row.getOffsetDateTime("next_retry_at"),
                                row.getString("last_error_type"),
                                id
                            };
                        }))
                .compose(snapshot -> {
                    var id = (dev.vertique.workflow.ops.WorkflowInstanceId) snapshot[4];
                    ctx.verify(() -> {
                        assertEquals(
                                "RETRY_SCHEDULED",
                                snapshot[0],
                                "demote with retry budget remaining must promote to RETRY_SCHEDULED, not FAILED");
                        assertEquals(1, snapshot[1], "attempt_count must be incremented by one");
                        assertTrue(
                                snapshot[2] != null
                                        && ((OffsetDateTime) snapshot[2]).isAfter(OffsetDateTime.now(ZoneOffset.UTC)),
                                "next_retry_at must be in the future (~now+5s)");
                        assertEquals("stale_running", snapshot[3], "last_error_type marks the demote reason");
                    });
                    return engine.query(id);
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "engine.query should succeed: " + ar.cause());
                    // The instance must remain parked at JOIN (no FAN_IN_* entries) because
                    // demote-with-retries does NOT trigger evaluateJoin.
                    long fanIn = ar.result().recentHistory().stream()
                            .filter(h -> h.entryType().name().startsWith("FAN_IN_"))
                            .count();
                    assertEquals(0L, fanIn, "demote-with-retries must NOT evaluate the join");
                    assertEquals(
                            WorkflowStatus.RUNNING,
                            ar.result().instance().status(),
                            "parent instance must still be RUNNING (parked at JOIN)");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Recovery rejects resume when the instance plan_hash has drifted")
    void recoverySweepRejectsResumeWhenPlanHashDrifts(VertxTestContext ctx) {
        // Stage a RETRY_SCHEDULED branch that would otherwise resume cleanly, then corrupt the
        // instance's plan_hash to force requirePlanHashMatchesForRecovery to throw. The outer
        // processDue.recover handler must swallow the exception; the branch row must remain
        // RETRY_SCHEDULED (no resume happened) and no FAN_IN_* entry should be appended.
        StartFanout payload = new StartFanout("o-drift");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);
        runOnDuplicatedContext(() -> engine.start(cmd)
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET status = 'RETRY_SCHEDULED',"
                                + " current_step_id = 'complete-a', wait_type = NULL, wait_key = NULL,"
                                + " next_retry_at = $1, version = version + 1"
                                + " WHERE workflow_id = $2 AND branch_id = 'a'")
                        .execute(Tuple.of(
                                OffsetDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC), id.value()))
                        .map(id))
                .compose(id -> pool.preparedQuery(
                                "UPDATE workflow_instances SET plan_hash = 'corrupted-hash'" + " WHERE id = $1")
                        .execute(Tuple.of(id.value()))
                        .map(id))
                .compose(id -> recovery.sweepOnce(Instant.now(), 10).map(id))
                .compose(id -> pool.preparedQuery("SELECT status FROM workflow_branch_tokens"
                                + " WHERE workflow_id = $1 AND branch_id = 'a'")
                        .execute(Tuple.of(id.value()))
                        .map(rs -> new Object[] {rs.iterator().next().getString("status"), id}))
                .compose(snapshot -> {
                    ctx.verify(() -> assertEquals(
                            "RETRY_SCHEDULED",
                            snapshot[0],
                            "branch must remain RETRY_SCHEDULED after drift-rejected resume"));
                    return engine.query((dev.vertique.workflow.ops.WorkflowInstanceId) snapshot[1]);
                })
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "engine.query should succeed even after drift rejection");
                    long fanIn = ar.result().recentHistory().stream()
                            .filter(h -> h.entryType().name().startsWith("FAN_IN_"))
                            .count();
                    assertEquals(0L, fanIn, "drift-rejected resume must not append any FAN_IN_* entry");
                    assertEquals(
                            WorkflowStatus.RUNNING,
                            ar.result().instance().status(),
                            "instance must still be parked at JOIN (RUNNING)");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("WorkflowBranchRecoveryServiceImpl.reconcile() drives the same demote→evaluateJoin path"
            + " as direct sweepOnce")
    void reconcileAdapterDrivesStaleRunningDemote(VertxTestContext ctx) {
        WorkflowBranchRecoveryServiceImpl adapter = new WorkflowBranchRecoveryServiceImpl(
                () -> recovery, new WorkflowBranchRecoveryConfig(10, java.time.Duration.ofSeconds(1)));

        StartFanout payload = new StartFanout("o-adapter");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);
        runOnDuplicatedContext(() -> engine.start(cmd)
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET status = 'RUNNING',"
                                + " updated_at = $1, attempt_count = max_attempts - 1 WHERE workflow_id = $2"
                                + " AND branch_id = 'a'")
                        .execute(Tuple.of(
                                OffsetDateTime.ofInstant(Instant.now().minusSeconds(3600), ZoneOffset.UTC), id.value()))
                        .map(id))
                .compose(id -> adapter.reconcile().map(id))
                .compose(id -> engine.query(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "adapter reconcile should succeed: " + ar.cause());
                    assertEquals(
                            WorkflowStatus.FAILED,
                            ar.result().instance().status(),
                            "ALL_REQUIRED join must take the failure route once branch 'a' is demoted to FAILED");
                    assertEquals(
                            "join_failed_no_failure_route",
                            ar.result().instance().errorType(),
                            "error must come from evaluateJoin's no-route path, proving the adapter reached the engine");
                    ctx.completeNow();
                })));
    }

    // --- AC-2 tests ---

    /**
     * Binds a concrete {@link TenantCtx} and real {@link dev.vertique.core.correlation.CorrelationContext}
     * on the AC-2 holder for the duration of {@code body}, then restores the prior (empty) bindings.
     * Used to start an instance "under tenant T + correlation A" per AC-2's exact wording.
     *
     * @param tenantId      the tenant id to bind
     * @param correlationId the correlation id to bind (both {@code requestId} and {@code correlationId}
     *                      use this value; only {@code correlationId} is asserted downstream)
     * @param body          the action to run with both namespaces bound
     * @return a {@link Future} that completes with {@code body}'s result after the bindings are restored
     * @param <T> the result type of {@code body}
     */
    private static <T> Future<T> withAc2AmbientTenantAndCorrelation(
            String tenantId, String correlationId, java.util.function.Supplier<Future<T>> body) {
        dev.vertique.core.context.ContextHolder.Scope tenantScope =
                ac2Holder.bind(TenantCtx.class, new TenantCtx(tenantId));
        return withAc2AmbientCorrelation(correlationId, body).eventually(() -> {
            tenantScope.close();
            return Future.succeededFuture();
        });
    }

    /**
     * Binds only a real {@link dev.vertique.core.correlation.CorrelationContext} (no tenant) on the
     * AC-2 holder for the duration of {@code body}, then restores the prior binding. Used to prove
     * that a namespace absent from ambient (tenant) is filled from the instance's persisted
     * metadata rather than leaking from a stray ambient bind.
     *
     * @param correlationId the correlation id to bind
     * @param body          the action to run with the correlation namespace bound
     * @return a {@link Future} that completes with {@code body}'s result after the binding is restored
     * @param <T> the result type of {@code body}
     */
    private static <T> Future<T> withAc2AmbientCorrelation(
            String correlationId, java.util.function.Supplier<Future<T>> body) {
        dev.vertique.core.correlation.CorrelationIdentifier id =
                new dev.vertique.core.correlation.CorrelationIdentifier(correlationId, "ac2-it");
        dev.vertique.core.correlation.CorrelationContext correlation = ac2CorrelationFactory.create(id, id);
        dev.vertique.core.context.ContextHolder.Scope correlationScope =
                ac2Holder.bind(dev.vertique.core.correlation.CorrelationContext.class, correlation);
        return body.get().eventually(() -> {
            correlationScope.close();
            return Future.succeededFuture();
        });
    }

    /**
     * Reads all {@code outbox} rows for the given workflow instance, ordered by insertion.
     *
     * @param workflowId the workflow instance id
     * @return a {@link Future} of the matching rows
     */
    private Future<List<Row>> readAc2OutboxRowsForWorkflow(java.util.UUID workflowId) {
        return pool.preparedQuery("SELECT * FROM outbox WHERE aggregate_id = $1 ORDER BY id ASC")
                .execute(Tuple.of(workflowId.toString()))
                .map(PgWorkflowBranchRecoveryServiceIT::toRowList);
    }

    private static List<Row> toRowList(RowSet<Row> rs) {
        List<Row> rows = new ArrayList<>();
        for (Row row : rs) {
            rows.add(row);
        }
        return rows;
    }

    /**
     * Reads the {@code context} section of an {@code outbox.metadata} JSONB column value.
     *
     * @param row the outbox row
     * @return the {@code context} sub-object, or an empty {@link JsonObject} if absent
     */
    private static JsonObject contextOf(Row row) {
        JsonObject metadata = (JsonObject) row.getValue("metadata");
        JsonObject context = metadata == null ? null : metadata.getJsonObject("context");
        return context == null ? new JsonObject() : context;
    }

    /**
     * Builds a {@code {"context": {"correlation": {...}}}} carrier document, matching the real
     * {@link dev.vertique.correlation.CorrelationContextDurableEncoder}'s wire shape, for direct
     * insertion into a {@code metadata JSONB} column via a JsonObject-typed bind parameter (never a
     * pre-encoded string — the production insert/update codepaths always bind a {@link JsonObject}
     * or {@code null}, per {@code PgBranchTokenRepository.toMetadataJson}).
     *
     * @param correlationId the correlation id value to embed
     * @return the carrier document
     */
    private static JsonObject correlationOnlyMetadataCarrier(String correlationId) {
        return new JsonObject()
                .put(
                        "context",
                        new JsonObject()
                                .put(
                                        "correlation",
                                        new JsonObject()
                                                .put(
                                                        "correlationId",
                                                        new JsonObject()
                                                                .put("value", correlationId)
                                                                .put("source", "ac2-it"))
                                                .put(
                                                        "requestId",
                                                        new JsonObject()
                                                                .put("value", correlationId)
                                                                .put("source", "ac2-it"))
                                                .put("schemaVersion", 1)));
    }

    /**
     * Builds a {@code {"context": {"tenant": {...}}}} carrier document for direct insertion into a
     * {@code metadata JSONB} column.
     *
     * @param tenantId the tenant id value to embed
     * @return the carrier document
     */
    private static JsonObject tenantOnlyMetadataCarrier(String tenantId) {
        return new JsonObject()
                .put(
                        "context",
                        new JsonObject().put(TenantCtxCodec.NAMESPACE, new JsonObject().put("tenantId", tenantId)));
    }

    /**
     * Extracts the tenant id from a durable-context {@code context} document, or {@code null} when
     * the {@code tenant} namespace is absent.
     */
    private static String tenantIdOf(JsonObject context) {
        JsonObject tenant = context.getJsonObject(TenantCtxCodec.NAMESPACE);
        return tenant == null ? null : tenant.getString("tenantId");
    }

    /**
     * Extracts the correlation id value from a durable-context {@code context} document, or
     * {@code null} when the {@code correlation} namespace is absent.
     */
    private static String correlationIdOf(JsonObject context) {
        JsonObject correlation = context.getJsonObject("correlation");
        if (correlation == null) {
            return null;
        }
        JsonObject correlationId = correlation.getJsonObject("correlationId");
        return correlationId == null ? null : correlationId.getString("value");
    }

    @Test
    @DisplayName("AC-2 headline — recovery binds carrier-authoritative correlation C + recovery-time tenant"
            + " T fill on both the SERVICE and WORKFLOW_EVENT outbox entries")
    void recoverDueBranch_carrierAuthoritativePlusInstanceFill_headlineAc2(VertxTestContext ctx) {
        Ac2Start payload = new Ac2Start("ac2-headline");
        StartCommand cmd =
                new StartCommand(AC2_DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);
        runOnDuplicatedContext(() -> withAc2AmbientTenantAndCorrelation(
                        "T", "IGNORED-AT-START", () -> ac2Engine.start(cmd))
                // Seed a degraded branch token: correlation C only, no tenant — simulating the
                // §2-gap-1 degraded/pre-feature fork (AC-2's documented test-setup technique).
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET metadata = $1"
                                + " WHERE workflow_id = $2 AND branch_id = 'a'")
                        .execute(Tuple.of(correlationOnlyMetadataCarrier("C"), id.value()))
                        .map(id))
                // Pre-drive assertion: the seeded token metadata lacks the tenant namespace.
                .compose(id -> pool.preparedQuery(
                                "SELECT metadata FROM workflow_branch_tokens WHERE workflow_id = $1 AND branch_id = 'a'")
                        .execute(Tuple.of(id.value()))
                        .map(rs -> {
                            JsonObject preContext = contextOf(rs.iterator().next());
                            assertFalse(
                                    preContext.containsKey(TenantCtxCodec.NAMESPACE),
                                    "pre-drive token metadata must NOT carry the tenant namespace"
                                            + " (this is the red-today assertion)");
                            return id;
                        }))
                // Force the branch to RETRY_SCHEDULED at dispatch-a so the sweep re-drives it
                // through the service dispatch and on to the join → parent WORKFLOW_COMPLETED.
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET status = 'RETRY_SCHEDULED',"
                                + " current_step_id = 'dispatch-a', wait_type = NULL, wait_key = NULL,"
                                + " next_retry_at = $1, version = version + 1"
                                + " WHERE workflow_id = $2 AND branch_id = 'a'")
                        .execute(Tuple.of(
                                OffsetDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC), id.value()))
                        .map(id))
                .compose(id -> ac2Recovery.sweepOnce(Instant.now(), 10).map(id))
                .compose(id -> readAc2OutboxRowsForWorkflow(id.value()))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "recovery sweep should succeed: " + ar.cause());
                    List<Row> rows = ar.result();

                    // Two SERVICE-destination rows exist: WORKFLOW_STARTED (captured at start()
                    // under the test's own ambient correlation, unrelated to this test's subject)
                    // and the ac2.service.a dispatch (the branch's recovery-driven service
                    // dispatch, the row under test). Filter on event_type, not destination_type
                    // alone, to isolate the dispatch row.
                    Row serviceRow = rows.stream()
                            .filter(r -> "ac2.service.a".equals(r.getString("event_type")))
                            .findFirst()
                            .orElseThrow(() ->
                                    new AssertionError("no SERVICE dispatch outbox row found; rows=" + rows.size()));
                    Row eventRow = rows.stream()
                            .filter(r -> "WORKFLOW_COMPLETED".equals(r.getString("event_type")))
                            .findFirst()
                            .orElseThrow(() ->
                                    new AssertionError("no WORKFLOW_COMPLETED outbox row found; rows=" + rows.size()));

                    JsonObject serviceContext = contextOf(serviceRow);
                    JsonObject eventContext = contextOf(eventRow);

                    assertEquals(
                            "C",
                            correlationIdOf(serviceContext),
                            "SERVICE outbox entry must carry correlation C (carrier authoritative)");
                    assertEquals(
                            "T",
                            tenantIdOf(serviceContext),
                            "SERVICE outbox entry must carry tenant T (recovery-time instance fill)");
                    assertEquals(
                            "C",
                            correlationIdOf(eventContext),
                            "WORKFLOW_EVENT outbox entry must carry correlation C (carrier authoritative)");
                    assertEquals(
                            "T",
                            tenantIdOf(eventContext),
                            "WORKFLOW_EVENT outbox entry must carry tenant T (recovery-time instance fill)");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Inline branch-drive fill — a live instance-level signal that forks absorbs the binder's"
            + " instance-filled effective document into the freshly-created branch token")
    void liveSignalForkDrive_bindsEffectiveDocumentBeforeForkCapture_branchTokenAbsorbsInstanceFill(
            VertxTestContext ctx) {
        // Start with NO ambient context at all — instance.metadata() persists NULL (dark path).
        // The fork-time capture will therefore see nothing to absorb UNLESS the instance-level
        // signal delivery (a binder row, S4) later fills a namespace the instance carries. To
        // exercise "token-carrier-wins + instance-fill" on the live path, seed the instance's
        // *persisted* metadata directly (simulating a start-time capture) after start, then
        // deliver the signal with an ambient correlation bound but no tenant — the binder's
        // instance-fill supplies tenant T (absent from ambient) before the fork dispatch runs in
        // the same drive, so the freshly-created branch token's fork-time capture absorbs it.
        Ac2Start payload = new Ac2Start("ac2-live-fill");
        StartCommand cmd =
                new StartCommand(AC2_LIVE_DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);
        runOnDuplicatedContext(() -> ac2Engine
                .start(cmd)
                .compose(id -> pool.preparedQuery("UPDATE workflow_instances SET metadata = $1 WHERE id = $2")
                        .execute(Tuple.of(tenantOnlyMetadataCarrier("T-LIVE"), id.value()))
                        .map(id))
                // Deliver the fork-triggering signal with ambient correlation bound (no tenant) —
                // the binder fills tenant T-LIVE from the instance's persisted metadata before the
                // drive (including the fork dispatch reached later in the same drive) proceeds.
                .compose(id -> withAc2AmbientCorrelation(
                                "LIVE-CORR", () -> ac2Engine.signal(id, "signal-start", "go", "ac2-live-fill-dedup"))
                        .map(v -> id))
                .compose(id -> readAc2OutboxRowsForWorkflow(id.value()))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "live signal fork-drive should succeed: " + ar.cause());
                    List<Row> rows = ar.result();
                    // Filter on event_type (not destination_type alone) to isolate the branch's
                    // dispatch row from any WORKFLOW_EVENT rows sharing the SERVICE destination.
                    Row serviceRow = rows.stream()
                            .filter(r -> "ac2.service.a".equals(r.getString("event_type")))
                            .findFirst()
                            .orElseThrow(() ->
                                    new AssertionError("no SERVICE dispatch outbox row found; rows=" + rows.size()));
                    JsonObject context = contextOf(serviceRow);
                    assertEquals(
                            "LIVE-CORR",
                            correlationIdOf(context),
                            "the branch's dispatch must carry the ambient correlation (base authoritative)");
                    assertEquals(
                            "T-LIVE",
                            tenantIdOf(context),
                            "the branch's dispatch must carry tenant T-LIVE — absorbed via the binder's"
                                    + " instance-fill into the effective document the fork captured from"
                                    + " (fork-absorption consequence, FR-WF-CTX-024)");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("No-regression — NULL instance metadata contributes no fill; branch carrier is bound unchanged")
    void recoverBranch_nullInstanceMetadata_noFillContributed_branchCarrierUnchanged(VertxTestContext ctx) {
        // Start with NO ambient context at all so instance.metadata() persists NULL (FR-WF-CTX-010).
        Ac2Start payload = new Ac2Start("ac2-null-instance");
        StartCommand cmd =
                new StartCommand(AC2_DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);
        runOnDuplicatedContext(() -> ac2Engine
                .start(cmd)
                // Seed the branch token with its OWN carrier (correlation D only) — instance
                // metadata stays NULL, so the merge must be a pure no-op.
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET metadata = $1"
                                + " WHERE workflow_id = $2 AND branch_id = 'a'")
                        .execute(Tuple.of(correlationOnlyMetadataCarrier("D"), id.value()))
                        .map(id))
                .compose(id -> pool.preparedQuery("SELECT metadata FROM workflow_instances WHERE id = $1")
                        .execute(Tuple.of(id.value()))
                        .map(rs -> {
                            assertTrue(
                                    rs.iterator().next().getValue("metadata") == null,
                                    "instance metadata must be NULL (no ambient context at start)");
                            return id;
                        }))
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET status = 'RETRY_SCHEDULED',"
                                + " current_step_id = 'dispatch-a', wait_type = NULL, wait_key = NULL,"
                                + " next_retry_at = $1, version = version + 1"
                                + " WHERE workflow_id = $2 AND branch_id = 'a'")
                        .execute(Tuple.of(
                                OffsetDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC), id.value()))
                        .map(id))
                .compose(id -> ac2Recovery.sweepOnce(Instant.now(), 10).map(id))
                .compose(id -> readAc2OutboxRowsForWorkflow(id.value()))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "recovery sweep should succeed: " + ar.cause());
                    // Filter on event_type (not destination_type alone) to isolate the branch's
                    // dispatch row from the WORKFLOW_STARTED row sharing the SERVICE destination.
                    Row serviceRow = ar.result().stream()
                            .filter(r -> "ac2.service.a".equals(r.getString("event_type")))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError("no SERVICE dispatch outbox row found"));
                    JsonObject context = contextOf(serviceRow);
                    assertEquals(
                            "D",
                            correlationIdOf(context),
                            "branch carrier alone (correlation D) must be bound unchanged");
                    assertFalse(
                            context.containsKey(TenantCtxCodec.NAMESPACE),
                            "NULL instance metadata must contribute no fill — no tenant namespace present");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Per-row isolation — a bind/drive failure on one recoverable branch does not stop the sweep"
            + " from processing the next row")
    void recoverBranch_bindFailureIsolatedToThatRow_sweepContinues(VertxTestContext ctx) {
        Ac2Start payloadBad = new Ac2Start("ac2-isolation-bad");
        Ac2Start payloadGood = new Ac2Start("ac2-isolation-good");
        StartCommand cmdBad =
                new StartCommand(AC2_DEFINITION.definitionId(), payloadBad, payloadBad.idempotencyKey(), null, null);
        StartCommand cmdGood =
                new StartCommand(AC2_DEFINITION.definitionId(), payloadGood, payloadGood.idempotencyKey(), null, null);
        runOnDuplicatedContext(() -> ac2Engine
                .start(cmdBad)
                .compose(badId -> ac2Engine.start(cmdGood).map(goodId -> new WorkflowInstanceId[] {badId, goodId}))
                // Corrupt the "bad" instance's plan_hash so loadEffectiveBaseForBind's later
                // loadInstanceAndRuntime (inside resumeOne) rejects it via the plan-hash drift
                // guard — this row's advance fails, but must not abort the sweep's remaining rows.
                .compose(ids -> pool.preparedQuery(
                                "UPDATE workflow_instances SET plan_hash = 'corrupted'" + " WHERE id = $1")
                        .execute(Tuple.of(ids[0].value()))
                        .map(v -> ids))
                .compose(ids -> pool.preparedQuery("UPDATE workflow_branch_tokens SET status = 'RETRY_SCHEDULED',"
                                + " current_step_id = 'dispatch-a', wait_type = NULL, wait_key = NULL,"
                                + " next_retry_at = $1, version = version + 1"
                                + " WHERE workflow_id = ANY($2)")
                        .execute(Tuple.of(
                                OffsetDateTime.ofInstant(Instant.now().minusSeconds(60), ZoneOffset.UTC),
                                (Object) new java.util.UUID[] {ids[0].value(), ids[1].value()}))
                        .map(v -> ids))
                .compose(ids -> ac2Recovery.sweepOnce(Instant.now(), 10).map(ids))
                .compose(ids -> readAc2OutboxRowsForWorkflow(ids[1].value()).map(rows -> new Object[] {ids, rows}))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "sweep should succeed overall despite one row failing: " + ar.cause());
                    @SuppressWarnings("unchecked")
                    List<Row> goodRows = (List<Row>) ar.result()[1];
                    assertTrue(
                            goodRows.stream().anyMatch(r -> "ac2.service.a".equals(r.getString("event_type"))),
                            "the good row's branch must still have been driven to its SERVICE dispatch"
                                    + " despite the bad row's plan-hash-drift failure; rows=" + goodRows.size());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("AC-2 demote path — the terminal-evaluation work following a stale-RUNNING demote to FAILED"
            + " runs under the carrier-authoritative + instance-filled bind (WORKFLOW_FAILED outbox entry)")
    void recoverBranch_demoteToFailedTerminalEvaluation_bindsCarrierAuthoritativePlusInstanceFill(
            VertxTestContext ctx) {
        // Uses AC2_DEMOTE_DEFINITION (unlike DEFINITION / AC #9's fixture) because its join
        // declares a real failureStepId ("failed-a" -> a CompleteNode). demoteOne's terminal path
        // (RUNNING with no retry budget -> FAILED -> evaluateRecoveredBranchIfTerminal) therefore
        // drives the join's failure route all the way into WorkflowTransitionDriver, which emits
        // WORKFLOW_FAILED as a WORKFLOW_EVENT outbox entry — the same observable seam AC-2's other
        // tests use for the resume/drive path (readAc2OutboxRowsForWorkflow + contextOf). This is
        // the only asserted proof available: the join's no-failure-route variant (AC #9's DEFINITION)
        // never reaches an outbox write on this path, so binding cannot be observed through it.
        Ac2Start payload = new Ac2Start("ac2-demote");
        StartCommand cmd =
                new StartCommand(AC2_DEMOTE_DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);
        runOnDuplicatedContext(() -> withAc2AmbientTenantAndCorrelation(
                        "T-DEMOTE", "IGNORED-AT-START", () -> ac2Engine.start(cmd))
                // Seed a degraded branch token: correlation C-DEMOTE only, no tenant — simulating
                // the §2-gap-1 degraded/pre-feature fork, same technique as the headline AC-2 test.
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET metadata = $1"
                                + " WHERE workflow_id = $2 AND branch_id = 'a'")
                        .execute(Tuple.of(correlationOnlyMetadataCarrier("C-DEMOTE"), id.value()))
                        .map(id))
                // Force branch 'a' into stale RUNNING with no retry budget left (AC #9's exact
                // technique: attempt_count = max_attempts - 1, updated_at far in the past) so the
                // sweep's stale-RUNNING side demotes it straight to FAILED.
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET status = 'RUNNING',"
                                + " updated_at = $1, attempt_count = max_attempts - 1 WHERE workflow_id = $2"
                                + " AND branch_id = 'a'")
                        .execute(Tuple.of(
                                OffsetDateTime.ofInstant(Instant.now().minusSeconds(3600), ZoneOffset.UTC), id.value()))
                        .map(id))
                .compose(id -> ac2Recovery.sweepOnce(Instant.now(), 10).map(id))
                .compose(id -> readAc2OutboxRowsForWorkflow(id.value()))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(ar.succeeded(), "recovery sweep should succeed: " + ar.cause());
                    List<Row> rows = ar.result();

                    Row eventRow = rows.stream()
                            .filter(r -> "WORKFLOW_FAILED".equals(r.getString("event_type")))
                            .findFirst()
                            .orElseThrow(() -> new AssertionError(
                                    "no WORKFLOW_FAILED outbox row found — evaluateRecoveredBranchIfTerminal"
                                            + " did not reach the join's failure route; rows=" + rows.size()));

                    JsonObject eventContext = contextOf(eventRow);
                    assertEquals(
                            "C-DEMOTE",
                            correlationIdOf(eventContext),
                            "WORKFLOW_FAILED outbox entry must carry correlation C-DEMOTE (branch carrier"
                                    + " authoritative — proves the demote path's terminal evaluation ran under"
                                    + " token.metadata(), not an unbound/ambient context)");
                    assertEquals(
                            "T-DEMOTE",
                            tenantIdOf(eventContext),
                            "WORKFLOW_FAILED outbox entry must carry tenant T-DEMOTE (recovery-time instance"
                                    + " fill — proves the bind is the CALLER_WINS merge of the branch carrier with"
                                    + " the parent instance's persisted metadata, not the carrier alone)");
                    ctx.completeNow();
                })));
    }
}
