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
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextDurableDecoder;
import dev.vertique.correlation.CorrelationContextDurableEncoder;
import dev.vertique.correlation.CorrelationContextFactory;
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
 * Live-signal ambient-authority test for {@link WorkflowEngine#signal} (PRD AC-3,
 * FR-WF-CTX-020/024).
 *
 * <p>An instance started under correlation A + tenant T (persisted metadata) receives a live signal
 * delivered under ambient correlation B (no explicit carrier). The binder-row bind must observe
 * ambient correlation B as authoritative over the instance's persisted correlation A, while the
 * instance-fill half of the merge supplies tenant T for the namespace ambient lacks — proving the
 * base-wins/instance-fill contract (Contract Appendix C2) on the live (non-recovery) signal path.
 *
 * <p>Uses the {@code runOnDuplicated} pattern plus the shared {@link TenantCtx} /
 * {@link TenantCtxCodec} test fixture, extended with the real {@link CorrelationContext}
 * encoder/decoder pair (as {@link WorkflowContextBinderTest} does for its AC-9 ordering tests) so
 * both namespaces are exercised through the real binder wiring assembled via
 * {@link WorkflowEngineFactory#create}.
 */
@ExtendWith({VertxExtension.class, MockitoExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WorkflowLiveSignalContextTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final String DEFINITION_ID = "wf-live-signal-context-test";
    private static final String COMPLETE_STEP = "complete";

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
            DefaultContextHolder holder,
            CorrelationContextFactory correlationFactory) {}

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

    /**
     * Assembles a {@link Harness} wired with both the {@link TenantCtx} codec and the real
     * {@link CorrelationContext} codec, via {@link WorkflowEngineFactory#create}.
     */
    private static Harness buildHarness() {
        DefaultContextHolder holder = new DefaultContextHolder();
        CorrelationContextFactory correlationFactory = new CorrelationContextFactory(Optional.empty());
        DurableContextMetadataRegistry registry = new DurableContextMetadataRegistry(
                Set.of(TenantCtxCodec.encoder(), new CorrelationContextDurableEncoder()),
                Set.of(TenantCtxCodec.decoder(), new CorrelationContextDurableDecoder(correlationFactory)));
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

        when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        when(history.append(any(), any())).thenReturn(Future.succeededFuture());

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
        return new Harness(handle, instances, dedup, workflowRegistry, holder, correlationFactory);
    }

    @Test
    @DisplayName("signal(): live ambient correlation B wins over the instance's persisted correlation A,"
            + " and instance fill supplies tenant T")
    void signal_liveAmbientCorrelationNoExplicitCarrier_ambientWinsAndInstanceFillsTenant(
            Vertx vertx, VertxTestContext ctx) {
        Harness h = buildHarness();
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildRuntimeWorkflow();
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        // Instance persisted metadata: correlation A + tenant T.
        CorrelationContext instanceCorrelation = h.correlationFactory()
                .create(new CorrelationIdentifier("req-A", "test"), new CorrelationIdentifier("corr-A", "test"));
        DurableMetadata correlationPart = new CorrelationContextDurableEncoder()
                .encode(instanceCorrelation, new dev.vertique.core.context.DurableEncodeContext("test"));
        DurableMetadata tenantPart =
                DurableMetadata.of(TenantCtxCodec.NAMESPACE, io.vertx.core.json.JsonObject.of("tenantId", "T"));
        DurableMetadata instanceMetadata =
                correlationPart.merge(tenantPart, DurableMetadata.MergePolicy.FAIL_ON_CONFLICT);

        WorkflowInstance inst = instanceWaitingOnSignal(instanceMetadata);
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.dedup().claimOrResolveSignal(eq(inst.id()), any(), eq(tx))).thenReturn(Future.succeededFuture(true));

        AtomicReference<Optional<TenantCtx>> capturedTenant = new AtomicReference<>();
        AtomicReference<Optional<CorrelationContext>> capturedCorrelation = new AtomicReference<>();
        doAnswer(inv -> {
                    capturedTenant.set(h.holder().current(TenantCtx.class));
                    capturedCorrelation.set(h.holder().current(CorrelationContext.class));
                    return Future.succeededFuture(1);
                })
                .when(h.instances())
                .updateOptimistic(any(), anyLong(), any());

        runOnDuplicated(vertx, v -> {
            // Deliver the signal under a fresh ambient correlation B — no explicit carrier.
            CorrelationContext ambientCorrelationB = h.correlationFactory()
                    .create(new CorrelationIdentifier("req-B", "test"), new CorrelationIdentifier("corr-B", "test"));
            h.holder().bind(CorrelationContext.class, ambientCorrelationB);

            h.txOps()
                    .signal(inst.id(), "approve", Map.of(), "dedup-1", tx)
                    .onComplete(ar -> ctx.verify(() -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                            return;
                        }
                        assertThat(capturedCorrelation.get())
                                .as("ambient correlation B must be authoritative over the instance's persisted"
                                        + " correlation A")
                                .isPresent();
                        assertThat(capturedCorrelation
                                        .get()
                                        .get()
                                        .correlationId()
                                        .value())
                                .isEqualTo("corr-B");
                        assertThat(capturedTenant.get())
                                .as("instance fill must supply tenant T (absent from ambient)")
                                .contains(new TenantCtx("T"));
                        ctx.completeNow();
                    }));
        });
    }
}
