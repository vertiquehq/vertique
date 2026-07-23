// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.db.DbPoolConfig;
import dev.vertique.db.postgresql.PgDbExceptionMapper;
import dev.vertique.db.test.DatabaseExtension;
import dev.vertique.db.test.PostgresContainer;
import dev.vertique.workflow.contract.IdempotencyKeyed;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.engine.testsupport.TenantCtxCodec;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.postgresql.repository.PgBranchTokenRepository;
import dev.vertique.workflow.postgresql.repository.PgJoinStateRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowDedupRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowHistoryRepository;
import dev.vertique.workflow.postgresql.repository.PgWorkflowInstanceRepository;
import dev.vertique.workflow.postgresql.tasks.PgTaskStore;
import dev.vertique.workflow.postgresql.timer.PgTimerStore;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.pgclient.PgBuilder;
import io.vertx.pgclient.PgConnectOptions;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.PoolOptions;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Branch-targeted explicit-carrier signal tests (PRD-WF-007, Contract Appendix C3, AC-4
 * branch-targeted case) exercised end-to-end through the real {@code SignalHandler} →
 * {@code BranchTransitionEngine} path.
 *
 * <p>Exercises {@link WorkflowEngine}'s 8-arg {@code signal(...)} override for a branch-targeted
 * (non-null {@code forkStepId}/{@code branchId}) request carrying a non-null explicit carrier: the
 * carrier is the drive's authoritative base (winning over the branch token's persisted metadata for
 * that transition only), with instance metadata filling only namespaces absent from the carrier.
 *
 * <p>Uses a single-branch fork ({@code wait-a} signal wait → {@code dispatch-a} service dispatch
 * → {@code complete-a} → join → {@code done}), mirroring {@code PgWorkflowBranchRecoveryServiceIT}'s
 * {@code AC2_DEFINITION} shape. A capturing {@code SERVICE} recorder reads the currently-bound
 * durable context via {@link DurableContextPropagator#mergeCaptured} — exactly like
 * {@code Ac2CapturingServiceRecorder} — so each test can assert on the effective document the
 * branch's dispatch actually saw.
 */
@ExtendWith({VertxExtension.class, DatabaseExtension.class})
@Timeout(value = 60, unit = TimeUnit.SECONDS)
public class WorkflowBranchTargetedSignalCarrierIT {

    static final PostgresContainer db = new PostgresContainer()
            .withDatabaseName("workflow_branch_targeted_carrier_test")
            .withMigration("classpath:db/migration/workflow");

    static Pool pool;
    static WorkflowEngineHandle engine;
    static DurableContextPropagator propagator;
    static Vertx vertxRef;

    /** Second test namespace (distinct from {@code tenant}) used to prove instance-fill of a
     * namespace absent from both the explicit carrier and the branch token metadata. */
    private static final String REGION_NAMESPACE = "region";

    /** Minimal test-local {@link dev.vertique.core.context.ContextValue} for the {@code region}
     * namespace, registered alongside {@link TenantCtxCodec} so {@link DurableContextPropagator
     * #mergeCaptured} can round-trip it (a namespace with no registered codec is invisible to
     * {@code mergeCaptured}, which re-derives its document from typed encoders reading the
     * currently-bound values — it does not echo back the raw carrier that was bound).
     *
     * @param regionId the region identifier
     */
    record RegionCtx(String regionId) implements dev.vertique.core.context.ContextValue {}

    /** Captures every {@code SERVICE} {@link WorkflowSideEffectIntent} routed during a test. */
    static final List<WorkflowSideEffectIntent> capturedServiceIntents = new ArrayList<>();

    /** The effective durable-context document bound at the moment the last SERVICE intent
     * was recorded — read via {@link DurableContextPropagator#mergeCaptured}, mirroring
     * {@code Ac2CapturingServiceRecorder}'s production-accurate capture technique. */
    static DurableMetadata lastCapturedContext;

    record StartFanout(String orderId) implements IdempotencyKeyed {
        @Override
        public String idempotencyKey() {
            return "branch-carrier-" + orderId;
        }
    }

    record State(String orderId) {}

    interface FanoutContract {}

    /**
     * Single-branch {@code ALL_REQUIRED} fork: {@code wait-a} (signal wait, branch-targeted) →
     * {@code dispatch-a} (SERVICE dispatch, the row under test) → {@code complete-a} → join →
     * {@code done}.
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
            return "branch-targeted-carrier-fanout";
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
                    .join("join")
                    .waitForSignal("wait-a", "signal-a", String.class)
                    .onSignal((s, p) -> s)
                    .toStepOnSignal("dispatch-a")
                    .build()
                    .dispatch("dispatch-a", "branch.service.a", st -> Map.of("orderId", st.orderId()), "complete-a")
                    .complete("complete-a")
                    .join("join")
                    .allRequired((s, results) -> s)
                    .toStep("done")
                    .endJoin()
                    .complete("done");
            wf.initialStep("fork");
        }
    };

    /** Capturing {@code SERVICE} recorder — records the intent and the effective durable context
     * bound at record time (via {@code mergeCaptured}), then completes as a no-op (no real outbox
     * needed for these assertions). */
    static final class CapturingServiceRecorder implements WorkflowSideEffectRecorder<SqlClient> {
        @Override
        public IntentKind kind() {
            return IntentKind.SERVICE;
        }

        @Override
        public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
            capturedServiceIntents.add(intent);
            lastCapturedContext = propagator.mergeCaptured(DurableMetadata.empty(), DispatchBoundary.OUTBOX);
            return Future.succeededFuture(RecorderResult.empty());
        }
    }

    /** No-op {@code WORKFLOW_EVENT} recorder — satisfies the join's completion route without a
     * real outbox; not the subject of these assertions. */
    static final class NoopEventRecorder implements WorkflowSideEffectRecorder<SqlClient> {
        @Override
        public IntentKind kind() {
            return IntentKind.WORKFLOW_EVENT;
        }

        @Override
        public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient tx) {
            return Future.succeededFuture(RecorderResult.empty());
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

        PgDbExceptionMapper ex = new PgDbExceptionMapper();
        PgWorkflowInstanceRepository instances = new PgWorkflowInstanceRepository(pool, ex);
        PgWorkflowHistoryRepository historyRepo = new PgWorkflowHistoryRepository(pool, ex);
        PgWorkflowDedupRepository dedup = new PgWorkflowDedupRepository(pool, ex);
        PgBranchTokenRepository branchRepo = new PgBranchTokenRepository(pool, ex);
        PgJoinStateRepository joins = new PgJoinStateRepository(pool, ex);
        PgTimerStore timerStore = new PgTimerStore(ex);
        PgTaskStore taskStore = new PgTaskStore(pool, ex);

        dev.vertique.context.DefaultContextHolder holder = new dev.vertique.context.DefaultContextHolder();
        dev.vertique.core.context.DurableContextMetadataEncoder<RegionCtx> regionEncoder =
                new dev.vertique.core.context.DurableContextMetadataEncoder<>() {
                    @Override
                    public Class<RegionCtx> type() {
                        return RegionCtx.class;
                    }

                    @Override
                    public String namespace() {
                        return REGION_NAMESPACE;
                    }

                    @Override
                    public DurableMetadata encode(RegionCtx value, dev.vertique.core.context.DurableEncodeContext c) {
                        return DurableMetadata.of(
                                REGION_NAMESPACE,
                                new io.vertx.core.json.JsonObject().put("regionId", value.regionId()));
                    }
                };
        dev.vertique.core.context.DurableContextMetadataDecoder<RegionCtx> regionDecoder =
                new dev.vertique.core.context.DurableContextMetadataDecoder<>() {
                    @Override
                    public Class<RegionCtx> type() {
                        return RegionCtx.class;
                    }

                    @Override
                    public String namespace() {
                        return REGION_NAMESPACE;
                    }

                    @Override
                    public dev.vertique.core.context.ContextDecodeResult<RegionCtx> decode(
                            DurableMetadata metadata, dev.vertique.core.context.DurableDecodeContext c) {
                        return metadata.body(REGION_NAMESPACE)
                                .map(body -> body.getString("regionId"))
                                .map(RegionCtx::new)
                                .map(dev.vertique.core.context.ContextDecodeResult::of)
                                .orElseGet(dev.vertique.core.context.ContextDecodeResult::empty);
                    }
                };
        dev.vertique.context.DurableContextMetadataRegistry contextRegistry =
                new dev.vertique.context.DurableContextMetadataRegistry(
                        Set.of(TenantCtxCodec.encoder(), regionEncoder),
                        Set.of(TenantCtxCodec.decoder(), regionDecoder));
        propagator = new DurableContextPropagator(
                contextRegistry, holder, new dev.vertique.context.ContextScopeBinder(holder));

        DefaultWorkflowRegistry registry = new DefaultWorkflowRegistry();
        registry.register(DEFINITION);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instances,
                historyRepo,
                dedup,
                Set.of(new CapturingServiceRecorder(), new NoopEventRecorder()),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timerStore,
                taskStore,
                Clock.systemUTC(),
                branchRepo,
                joins,
                propagator,
                java.util.Optional.empty());
        ctx.completeNow();
    }

    @BeforeEach
    void truncate(VertxTestContext ctx) {
        capturedServiceIntents.clear();
        lastCapturedContext = null;
        pool.query("TRUNCATE TABLE workflow_branch_tokens, workflow_join_states, workflow_timers,"
                        + " workflow_history, workflow_dedup, workflow_instances RESTART IDENTITY CASCADE")
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
     * Runs a test's async chain on a duplicated Vert.x context — required because the engine's
     * branch drive binds durable context via {@code bindFrom}, which is strict per FR-CTX-157b.
     *
     * @param body the test chain to run
     */
    private static void runOnDuplicatedContext(Runnable body) {
        ContextInternal dup = ((ContextInternal) vertxRef.getOrCreateContext()).duplicate();
        dup.runOnContext(unused -> body.run());
    }

    /**
     * Delivers a branch-targeted signal with an explicit carrier through the real
     * {@link TransactionalWorkflowOperations#signal} 8-arg override, opening its own transaction
     * (mirroring {@code WorkflowSignalContributor.handleSignal}'s {@code pool.withTransaction}
     * wrapping, minus the inbox dedup layer — not the subject of this IT).
     *
     * @param id workflow instance id
     * @param forkStepId the fork step id
     * @param branchId the branch id
     * @param dedupKey caller-supplied dedup key
     * @param carrier the explicit durable-context carrier, or {@code null}
     * @return a {@link Future} that completes when the signal has been applied
     */
    private static Future<Void> signalBranch(
            WorkflowInstanceId id, String forkStepId, String branchId, String dedupKey, DurableMetadata carrier) {
        TransactionalWorkflowOperations<SqlClient> txOps = engine;
        return pool.withTransaction(
                tx -> txOps.signal(id, "signal-a", "go", dedupKey, forkStepId, branchId, carrier, tx));
    }

    @Test
    @DisplayName("branch-targeted signal with explicit carrier drives the branch and the carrier wins over"
            + " token metadata (AC-4 headline branch case)")
    void branchTargetedSignal_explicitCarrier_drivesBranchAndCarrierWinsOverTokenMetadata(VertxTestContext ctx) {
        StartFanout payload = new StartFanout("headline");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);

        DurableMetadata tokenMetadataCarrier = DurableMetadata.of(TenantCtxCodec.NAMESPACE, jsonTenant("TOKEN"));
        DurableMetadata explicitCarrier = DurableMetadata.of(TenantCtxCodec.NAMESPACE, jsonTenant("CARRIER"));

        runOnDuplicatedContext(() -> ((WorkflowOperations) engine)
                .start(cmd)
                // Seed the branch token's own metadata directly — the branch was forked under a
                // different tenant than the one the signal's explicit carrier will supply.
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET metadata = $1"
                                + " WHERE workflow_id = $2 AND branch_id = 'a'")
                        .execute(io.vertx.sqlclient.Tuple.of(tokenMetadataCarrier.toCarrier(), id.value()))
                        .map(id))
                .compose(id -> signalBranch(id, "fork", "a", "dedup-1", explicitCarrier)
                        .map(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(
                            ar.succeeded(),
                            "branch-targeted explicit-carrier signal should succeed: "
                                    + (ar.cause() == null ? "" : ar.cause()));
                    assertEquals(
                            1,
                            capturedServiceIntents.size(),
                            "exactly one SERVICE dispatch intent must have been recorded");
                    assertEquals(
                            "CARRIER",
                            tenantIdOf(lastCapturedContext),
                            "the branch's dispatch must carry the explicit carrier's tenant, not the token's");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("branch-targeted signal with explicit carrier and instance fill — carrier wins, instance"
            + " fills a namespace absent from both carrier and token metadata")
    void branchTargetedSignal_explicitCarrierAndInstanceFill_carrierWinsInstanceFillsAbsent(VertxTestContext ctx) {
        StartFanout payload = new StartFanout("instance-fill");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);

        DurableMetadata explicitCarrier = DurableMetadata.of(TenantCtxCodec.NAMESPACE, jsonTenant("CARRIER"));
        io.vertx.core.json.JsonObject regionCarrier = new io.vertx.core.json.JsonObject()
                .put(
                        "context",
                        new io.vertx.core.json.JsonObject()
                                .put(REGION_NAMESPACE, new io.vertx.core.json.JsonObject().put("regionId", "R1")));

        runOnDuplicatedContext(() -> ((WorkflowOperations) engine)
                .start(cmd)
                // Instance metadata carries a namespace (region) absent from both the explicit
                // carrier and the branch token's own metadata.
                .compose(id -> pool.preparedQuery("UPDATE workflow_instances SET metadata = $1 WHERE id = $2")
                        .execute(io.vertx.sqlclient.Tuple.of(regionCarrier, id.value()))
                        .map(id))
                .compose(id -> signalBranch(id, "fork", "a", "dedup-1", explicitCarrier)
                        .map(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(
                            ar.succeeded(),
                            "branch-targeted explicit-carrier + instance-fill signal should" + " succeed: "
                                    + (ar.cause() == null ? "" : ar.cause()));
                    assertEquals(
                            "CARRIER",
                            tenantIdOf(lastCapturedContext),
                            "the branch's dispatch must carry the explicit carrier's tenant");
                    assertTrue(
                            lastCapturedContext != null
                                    && lastCapturedContext.has(REGION_NAMESPACE)
                                    && "R1"
                                            .equals(lastCapturedContext
                                                    .body(REGION_NAMESPACE)
                                                    .map(b -> b.getString("regionId"))
                                                    .orElse(null)),
                            "the branch's dispatch must carry the instance-filled region namespace"
                                    + " (proving effectiveBase = carrier.merge(instance.metadata(), CALLER_WINS)"
                                    + " is what was passed as the override, not the bare carrier)");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("branch-targeted signal with explicit carrier on a NULL-metadata instance binds exactly the"
            + " carrier's namespaces — no token-metadata fallback (FR-WF-CTX-012 branch-targeted variant)")
    void branchTargetedSignal_nullInstanceMetadataExplicitCarrier_carrierNamespacesOnlyNoTokenFallback(
            VertxTestContext ctx) {
        StartFanout payload = new StartFanout("null-instance");
        StartCommand cmd = new StartCommand(DEFINITION.definitionId(), payload, payload.idempotencyKey(), null, null);

        DurableMetadata tokenMetadataCarrier = DurableMetadata.of(TenantCtxCodec.NAMESPACE, jsonTenant("TOKEN"));
        DurableMetadata explicitCarrier = DurableMetadata.of(TenantCtxCodec.NAMESPACE, jsonTenant("CARRIER"));

        runOnDuplicatedContext(() -> ((WorkflowOperations) engine)
                .start(cmd)
                // instance.metadata() stays NULL (no ambient context bound at start()).
                .compose(id -> pool.preparedQuery("SELECT metadata FROM workflow_instances WHERE id = $1")
                        .execute(io.vertx.sqlclient.Tuple.of(id.value()))
                        .map(rs -> {
                            assertTrue(
                                    rs.iterator().next().getValue("metadata") == null,
                                    "instance metadata must be NULL (no ambient context at start)");
                            return id;
                        }))
                .compose(id -> pool.preparedQuery("UPDATE workflow_branch_tokens SET metadata = $1"
                                + " WHERE workflow_id = $2 AND branch_id = 'a'")
                        .execute(io.vertx.sqlclient.Tuple.of(tokenMetadataCarrier.toCarrier(), id.value()))
                        .map(id))
                .compose(id -> signalBranch(id, "fork", "a", "dedup-1", explicitCarrier)
                        .map(id))
                .onComplete(ar -> ctx.verify(() -> {
                    assertTrue(
                            ar.succeeded(),
                            "branch-targeted explicit-carrier signal on a NULL-metadata" + " instance should succeed: "
                                    + (ar.cause() == null ? "" : ar.cause()));
                    assertEquals(
                            "CARRIER",
                            tenantIdOf(lastCapturedContext),
                            "bind occurs on the explicit carrier alone; the token metadata (TOKEN) must"
                                    + " never be used as a fallback");
                    assertFalse(
                            lastCapturedContext == null
                                    || lastCapturedContext.namespaces().size() != 1,
                            "the effective document must carry exactly the carrier's namespaces — no extra"
                                    + " namespace contributed by a NULL instance metadata");
                    ctx.completeNow();
                })));
    }

    // --- Helpers ---

    private static io.vertx.core.json.JsonObject jsonTenant(String tenantId) {
        return new io.vertx.core.json.JsonObject().put("tenantId", tenantId);
    }

    private static String tenantIdOf(DurableMetadata metadata) {
        if (metadata == null) {
            return null;
        }
        return metadata.body(TenantCtxCodec.NAMESPACE)
                .map(b -> b.getString("tenantId"))
                .orElse(null);
    }
}
