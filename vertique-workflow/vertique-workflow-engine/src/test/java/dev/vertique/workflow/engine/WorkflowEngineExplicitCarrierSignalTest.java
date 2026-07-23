// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.ContextDecodeResult;
import dev.vertique.core.context.ContextValue;
import dev.vertique.core.context.DurableContextMetadataDecoder;
import dev.vertique.core.context.DurableDecodeContext;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.TenantCtx;
import dev.vertique.workflow.engine.testsupport.TenantCtxCodec;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Explicit signal-carrier tests for the engine's 8-arg {@code signal(...)} override (PRD-WF-007,
 * Contract Appendix C4, AC-4).
 *
 * <p>Exercises the engine override of the metadata-aware
 * {@link TransactionalWorkflowOperations#signal(WorkflowInstanceId, String, Object, String, String,
 * String, DurableMetadata, Object)} 8-arg method: a non-null explicit carrier is bound as the
 * drive's base (instance metadata filling only absent namespaces), and a duplicate-dedup-key
 * loser returns without ever binding its carrier.
 *
 * <p>Reuses the {@code runOnDuplicated} + {@link TenantCtx}/{@link TenantCtxCodec} fixture pattern
 * established by {@code WorkflowLiveSignalContextTest} (S6) and {@code WorkflowContextBinderTest}
 * (S3).
 */
@ExtendWith({VertxExtension.class, MockitoExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WorkflowEngineExplicitCarrierSignalTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final String DEFINITION_ID = "wf-explicit-carrier-signal-test";
    private static final String COMPLETE_STEP = "complete";

    /** Second test namespace (distinct from {@link TenantCtx}) used to prove instance-fill of an
     * absent-from-carrier namespace. */
    private static final String REGION_NAMESPACE = "region";

    /** Third test namespace, owned by an authenticated-only decoder (ADR-0147 sanitization seam). */
    private static final String SECURE_NAMESPACE = "secure";

    /** Minimal {@link ContextValue} fixture for the authenticated-only sanitization-seam test. */
    private record SecureCtx(String value) implements ContextValue {}

    /**
     * Returns a {@link DurableContextMetadataDecoder} for {@link SecureCtx} that declares
     * {@link DurableContextMetadataDecoder#acceptsExplicitCarrier()} {@code == false} — an
     * authenticated-only namespace per ADR-0147 that must be stripped from a sender-supplied
     * explicit signal carrier but must still fill normally from persisted instance metadata.
     */
    private static DurableContextMetadataDecoder<SecureCtx> secureDecoder() {
        return new DurableContextMetadataDecoder<>() {
            @Override
            public Class<SecureCtx> type() {
                return SecureCtx.class;
            }

            @Override
            public String namespace() {
                return SECURE_NAMESPACE;
            }

            @Override
            public ContextDecodeResult<SecureCtx> decode(DurableMetadata metadata, DurableDecodeContext context) {
                return metadata.body(SECURE_NAMESPACE)
                        .map(body -> body.getString("value"))
                        .map(SecureCtx::new)
                        .map(ContextDecodeResult::of)
                        .orElseGet(ContextDecodeResult::empty);
            }

            @Override
            public boolean acceptsExplicitCarrier() {
                return false;
            }
        };
    }

    /** Runs the given task on a duplicated Vert.x context. */
    private static void runOnDuplicated(Vertx vertx, Handler<Void> task) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(task);
    }

    /** Test harness bundling the assembled engine handle plus its mocked SPI collaborators. */
    private record Harness(
            TransactionalWorkflowOperations<SqlClient> txOps,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowDedupRepository<SqlClient> dedup,
            WorkflowRegistry registry,
            DefaultContextHolder holder) {}

    /** Builds a {@link RuntimeWorkflow} with a single {@link WaitSignalNode} leading to completion. */
    private static RuntimeWorkflow buildRuntimeWorkflow() {
        WaitSignalNode waitSignal = new WaitSignalNode(
                "wait-signal", "approve", Object.class.getName(), new CallbackId("state-updater"), COMPLETE_STEP, null);
        CompleteNode complete = new CompleteNode(COMPLETE_STEP);
        List<WorkflowNode> nodes = List.of(waitSignal, complete);
        WorkflowPlan plan = new WorkflowPlan(
                DEFINITION_ID, 1L, "plan-hash", Object.class.getName(), waitSignal.stepId(), nodes, null);
        WorkflowCallbackRegistry callbacks = org.mockito.Mockito.mock(
                WorkflowCallbackRegistry.class,
                org.mockito.Mockito.withSettings().lenient());
        when(callbacks.stateUpdater(new CallbackId("state-updater"))).thenReturn((a, b) -> Map.of());
        return RuntimeWorkflow.of(plan, Object.class, Object.class, payload -> Map.of(), Map.of(), callbacks);
    }

    /** Builds a {@link WorkflowInstance} waiting on {@code approve}, carrying the given metadata. */
    private static WorkflowInstance instanceWaitingOnSignal(DurableMetadata metadata) {
        return new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                DEFINITION_ID,
                1L,
                "plan-hash",
                0L,
                WorkflowStatus.WAITING,
                null,
                null,
                "wait-signal",
                WaitType.SIGNAL,
                "approve",
                null,
                "{}",
                null,
                null,
                FIXED_NOW,
                FIXED_NOW,
                metadata);
    }

    /** Assembles a {@link Harness} wired with the {@link TenantCtx} codec, via {@link WorkflowEngineFactory#create}. */
    private static Harness buildHarness() {
        return buildHarness(Set.of(TenantCtxCodec.decoder()));
    }

    /**
     * Assembles a {@link Harness} whose {@link WorkflowEngineFactory#create} call is given a
     * {@code null} durable-context propagator (the legacy/unwired-durable-propagation case).
     *
     * @return the assembled harness, wired without durable-context propagation
     */
    private static Harness buildHarnessWithNullPropagator() {
        DefaultContextHolder holder = new DefaultContextHolder();

        WorkflowTransactionRunner<SqlClient> txRunner = mock();
        WorkflowRegistry workflowRegistry = mock();
        WorkflowInstanceRepository<SqlClient> instances = mock();
        WorkflowHistoryRepository<SqlClient> history = mock();
        WorkflowDedupRepository<SqlClient> dedup = mock();
        BranchTokenRepository<SqlClient> branchTokens = mock();
        JoinStateRepository<SqlClient> joinStates = mock();
        TimerStore<SqlClient> timerStore = mock();
        TaskStore<SqlClient> taskStore = mock();

        WorkflowEngineHandle handle = WorkflowEngineFactory.create(
                txRunner,
                workflowRegistry,
                instances,
                history,
                dedup,
                branchTokens,
                joinStates,
                timerStore,
                taskStore,
                FIXED_CLOCK,
                Set.<WorkflowSideEffectRecorder<SqlClient>>of(),
                WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS,
                Optional.empty(),
                null);
        return new Harness(handle, instances, dedup, workflowRegistry, holder);
    }

    /**
     * Assembles a {@link Harness} wired with the {@link TenantCtx} encoder plus the given decoder
     * set (which must include {@link TenantCtxCodec#decoder()} for tests reusing the tenant
     * namespace), via {@link WorkflowEngineFactory#create}. Used by the sanitization-seam test to
     * additionally register an authenticated-only decoder alongside the tenant codec.
     *
     * @param decoders the decoder set to register with the propagator's registry
     * @return the assembled harness
     */
    private static Harness buildHarness(Set<DurableContextMetadataDecoder<?>> decoders) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextMetadataRegistry registry =
                new DurableContextMetadataRegistry(Set.of(TenantCtxCodec.encoder()), decoders);
        DurableContextPropagator propagator =
                new DurableContextPropagator(registry, holder, new ContextScopeBinder(holder));

        WorkflowTransactionRunner<SqlClient> txRunner = mock();
        WorkflowRegistry workflowRegistry = mock();
        WorkflowInstanceRepository<SqlClient> instances = mock();
        WorkflowHistoryRepository<SqlClient> history = mock();
        WorkflowDedupRepository<SqlClient> dedup = mock();
        BranchTokenRepository<SqlClient> branchTokens = mock();
        JoinStateRepository<SqlClient> joinStates = mock();
        TimerStore<SqlClient> timerStore = mock();
        TaskStore<SqlClient> taskStore = mock();

        // lenient(): unused by the dedup-race-loser test, whose drive short-circuits before ever
        // reaching doApplySignal — proof that the dedup claim runs before the bind (FR-WF-CTX-042).
        org.mockito.Mockito.lenient().when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        org.mockito.Mockito.lenient().when(history.append(any(), any())).thenReturn(Future.succeededFuture());

        WorkflowEngineHandle handle = WorkflowEngineFactory.create(
                txRunner,
                workflowRegistry,
                instances,
                history,
                dedup,
                branchTokens,
                joinStates,
                timerStore,
                taskStore,
                FIXED_CLOCK,
                Set.<WorkflowSideEffectRecorder<SqlClient>>of(),
                WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS,
                Optional.empty(),
                propagator);
        return new Harness(handle, instances, dedup, workflowRegistry, holder);
    }

    @Test
    @DisplayName("signal(): explicit carrier wins over ambient and instance fills an absent namespace (AC-4 core)")
    void signal_explicitCarrierInstanceLevel_carrierWinsOverAmbientPlusInstanceFill(Vertx vertx, VertxTestContext ctx) {
        Harness h = buildHarness();
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildRuntimeWorkflow();
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        // Instance metadata carries a namespace (region) absent from the explicit carrier.
        DurableMetadata instanceMetadata = DurableMetadata.of(REGION_NAMESPACE, new JsonObject().put("regionId", "R1"));
        WorkflowInstance inst = instanceWaitingOnSignal(instanceMetadata);
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.dedup().claimOrResolveSignal(eq(inst.id()), any(), eq(tx))).thenReturn(Future.succeededFuture(true));

        DurableMetadata explicitCarrier =
                DurableMetadata.of(TenantCtxCodec.NAMESPACE, new JsonObject().put("tenantId", "CARRIER"));

        AtomicReference<Optional<TenantCtx>> capturedTenant = new AtomicReference<>();
        doAnswer(inv -> {
                    capturedTenant.set(h.holder().current(TenantCtx.class));
                    return Future.succeededFuture(1);
                })
                .when(h.instances())
                .updateOptimistic(any(), anyLong(), any());

        runOnDuplicated(vertx, v -> {
            // Ambient tenant, present but must lose to the explicit carrier.
            h.holder().bind(TenantCtx.class, new TenantCtx("AMBIENT"));

            h.txOps()
                    .signal(inst.id(), "approve", Map.of(), "dedup-1", null, null, explicitCarrier, tx)
                    .onComplete(ar -> ctx.verify(() -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                            return;
                        }
                        assertThat(capturedTenant.get())
                                .as("explicit carrier must win over ambient")
                                .contains(new TenantCtx("CARRIER"));
                        ctx.completeNow();
                    }));
        });
    }

    @Test
    @DisplayName("signal(): explicit carrier on a NULL-metadata instance binds exactly the carrier's namespaces"
            + " (FR-WF-CTX-012 sentence 2)")
    void signal_explicitCarrierNullInstanceMetadata_carrierNamespacesOnlyNoFillContribution(
            Vertx vertx, VertxTestContext ctx) {
        Harness h = buildHarness();
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildRuntimeWorkflow();
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        WorkflowInstance inst = instanceWaitingOnSignal(null);
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.dedup().claimOrResolveSignal(eq(inst.id()), any(), eq(tx))).thenReturn(Future.succeededFuture(true));

        DurableMetadata explicitCarrier =
                DurableMetadata.of(TenantCtxCodec.NAMESPACE, new JsonObject().put("tenantId", "CARRIER"));

        AtomicReference<Optional<TenantCtx>> capturedTenant = new AtomicReference<>();
        doAnswer(inv -> {
                    capturedTenant.set(h.holder().current(TenantCtx.class));
                    return Future.succeededFuture(1);
                })
                .when(h.instances())
                .updateOptimistic(any(), anyLong(), any());

        runOnDuplicated(vertx, v -> h.txOps()
                .signal(inst.id(), "approve", Map.of(), "dedup-1", null, null, explicitCarrier, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(capturedTenant.get())
                            .as("bind occurs on the explicit carrier alone even with a NULL-metadata instance")
                            .contains(new TenantCtx("CARRIER"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("signal(): a losing dedup-race delivery's carrier is discarded and never bound (FR-WF-CTX-042)")
    void signal_duplicateDedupKeyRace_loserCarrierDiscardedWithLoser(Vertx vertx, VertxTestContext ctx) {
        Harness h = buildHarness();
        SqlClient tx = mock();

        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        // Loser: claimOrResolveSignal resolves false (a prior committed transaction already applied
        // the signal) — the dedup race is resolved before the instance is ever loaded, so the
        // carrier never reaches a drive/bind. No instance stub is registered: if the drive
        // incorrectly loaded the instance before (or regardless of) the claim, the unstubbed
        // findById would return Mockito's default null-Future, surfacing as an NPE.
        when(h.dedup().claimOrResolveSignal(eq(id), any(), eq(tx))).thenReturn(Future.succeededFuture(false));

        DurableMetadata loserCarrier =
                DurableMetadata.of(TenantCtxCodec.NAMESPACE, new JsonObject().put("tenantId", "LOSER"));

        runOnDuplicated(vertx, v -> h.txOps()
                .signal(id, "approve", Map.of(), "dedup-1", null, null, loserCarrier, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(h.holder().current(TenantCtx.class))
                            .as("the loser's carrier must never be bound — the dedup no-op short-circuits before"
                                    + " any bind/drive occurs")
                            .isEmpty();
                    org.mockito.Mockito.verify(h.instances(), org.mockito.Mockito.never())
                            .findById(any(), any());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("signal(): the sanitization seam strips an authenticated-only namespace from an explicit "
            + "carrier but persisted instance metadata still fills it normally (ADR-0147)")
    void signal_explicitCarrierWithAuthenticatedOnlyNamespace_strippedFromCarrierButInstanceFillUnsanitized(
            Vertx vertx, VertxTestContext ctx) {
        Harness h = buildHarness(Set.of(TenantCtxCodec.decoder(), secureDecoder()));
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildRuntimeWorkflow();
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        // Persisted instance metadata carries the authenticated-only namespace — a framework-
        // persisted carrier (instance metadata) is never routed through the sanitization seam, so it
        // must still fill normally.
        DurableMetadata instanceMetadata =
                DurableMetadata.of(SECURE_NAMESPACE, new JsonObject().put("value", "FROM-INSTANCE"));
        WorkflowInstance inst = instanceWaitingOnSignal(instanceMetadata);
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.dedup().claimOrResolveSignal(eq(inst.id()), any(), eq(tx))).thenReturn(Future.succeededFuture(true));

        // Sender-supplied explicit carrier tries to forge the authenticated-only namespace alongside
        // a normal one.
        DurableMetadata explicitCarrier = DurableMetadata.of(
                        TenantCtxCodec.NAMESPACE, new JsonObject().put("tenantId", "CARRIER"))
                .with(SECURE_NAMESPACE, new JsonObject().put("value", "FORGED"));

        AtomicReference<Optional<TenantCtx>> capturedTenant = new AtomicReference<>();
        AtomicReference<Optional<SecureCtx>> capturedSecure = new AtomicReference<>();
        doAnswer(inv -> {
                    capturedTenant.set(h.holder().current(TenantCtx.class));
                    capturedSecure.set(h.holder().current(SecureCtx.class));
                    return Future.succeededFuture(1);
                })
                .when(h.instances())
                .updateOptimistic(any(), anyLong(), any());

        runOnDuplicated(vertx, v -> h.txOps()
                .signal(inst.id(), "approve", Map.of(), "dedup-1", null, null, explicitCarrier, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(capturedTenant.get())
                            .as("the normal namespace from the explicit carrier is still bound")
                            .contains(new TenantCtx("CARRIER"));
                    assertThat(capturedSecure.get())
                            .as("the authenticated-only namespace fills from persisted instance metadata, "
                                    + "not the sender-forged carrier value")
                            .contains(new SecureCtx("FROM-INSTANCE"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("signal(): a non-null explicit carrier on an engine assembled with a null propagator fails fast"
            + " instead of silently dropping the carrier (C4 no-silent-drop)")
    void signal_explicitCarrierWithNullPropagator_failsFastInsteadOfSilentlyDroppingCarrier(
            Vertx vertx, VertxTestContext ctx) {
        Harness h = buildHarnessWithNullPropagator();
        SqlClient tx = mock();

        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        DurableMetadata explicitCarrier =
                DurableMetadata.of(TenantCtxCodec.NAMESPACE, new JsonObject().put("tenantId", "CARRIER"));

        runOnDuplicated(vertx, v -> h.txOps()
                .signal(id, "approve", Map.of(), "dedup-1", null, null, explicitCarrier, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    assertThat(ar.failed())
                            .as("a non-null explicit carrier must fail fast when durable context propagation is"
                                    + " not wired, rather than being silently dropped")
                            .isTrue();
                    assertThat(ar.cause()).isInstanceOf(UnsupportedOperationException.class);
                    org.mockito.Mockito.verify(h.dedup(), org.mockito.Mockito.never())
                            .claimOrResolveSignal(any(), any(), any());
                    ctx.completeNow();
                })));
    }
}
