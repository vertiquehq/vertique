// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.StartDedupResult;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.TenantCtx;
import dev.vertique.workflow.engine.testsupport.TenantCtxCodec;
import dev.vertique.workflow.ops.StartCommand;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WorkflowInstance;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Start-time durable-context capture tests for {@link WorkflowEngine#doCreateInstance} (PRD-WF-007
 * AC-1, FR-WF-CTX-010/012).
 *
 * <p>{@code doCreateInstance} is expected to capture the ambient durable context via
 * {@code propagator.capture(DispatchBoundary.WORKFLOW)} and persist it as the new instance's 18th
 * ({@code metadata}) component — converting an empty capture to {@code null} rather than an empty
 * {@link DurableMetadata} document. Start is capture-only: no new durable scope is installed around
 * the start drive.
 *
 * <p>Uses the {@code runOnDuplicated} pattern plus the shared {@link TenantCtx} /
 * {@link TenantCtxCodec} test fixture to bind ambient context on a duplicated Vert.x context before
 * invoking {@link WorkflowEngineFactory#create}'s assembled engine.
 */
@ExtendWith({VertxExtension.class, MockitoExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WorkflowEngineStartMetadataTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final String DEFINITION_ID = "wf-start-metadata-test";
    private static final String INITIAL_STEP_ID = "complete";

    /** Runs the given task on a duplicated Vert.x context. */
    private static void runOnDuplicated(Vertx vertx, Handler<Void> task) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(task);
    }

    /**
     * Builds a {@link RuntimeWorkflow} with a single terminal {@link CompleteNode} plan so
     * {@code doCreateInstance}'s post-insert {@code driver.driveTransitions} call settles
     * immediately without touching any collaborator beyond the mocked instance/history
     * repositories.
     */
    private static RuntimeWorkflow minimalRuntimeWorkflow() {
        List<WorkflowNode> nodes = List.of(new CompleteNode(INITIAL_STEP_ID));
        WorkflowPlan plan =
                new WorkflowPlan(DEFINITION_ID, 1L, "plan-hash", Object.class.getName(), INITIAL_STEP_ID, nodes, null);
        WorkflowCallbackRegistry callbacks = org.mockito.Mockito.mock();
        return RuntimeWorkflow.of(plan, Object.class, Object.class, payload -> Map.of(), Map.of(), callbacks);
    }

    /** Test harness bundling the assembled engine handle plus its mocked SPI collaborators. */
    private record Harness(
            TransactionalWorkflowOperations<SqlClient> ops,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            WorkflowRegistry registry,
            DurableContextPropagator propagator) {}

    /**
     * Assembles a {@link WorkflowEngineHandle} via {@link WorkflowEngineFactory#create} over
     * fully-mocked repository SPIs (all interfaces) and the given propagator, with the dedup call
     * always winning the race and the history/instance writes always succeeding.
     */
    private static Harness buildHarness(DurableContextPropagator propagator) {
        WorkflowTransactionRunner<SqlClient> txRunner = org.mockito.Mockito.mock();
        WorkflowRegistry registry = org.mockito.Mockito.mock();
        WorkflowInstanceRepository<SqlClient> instances = org.mockito.Mockito.mock();
        WorkflowHistoryRepository<SqlClient> history = org.mockito.Mockito.mock();
        WorkflowDedupRepository<SqlClient> dedup = org.mockito.Mockito.mock();
        BranchTokenRepository<SqlClient> branchTokens = org.mockito.Mockito.mock();
        JoinStateRepository<SqlClient> joinStates = org.mockito.Mockito.mock();
        dev.vertique.workflow.timer.TimerStore<SqlClient> timerStore = org.mockito.Mockito.mock();
        dev.vertique.workflow.tasks.TaskStore<SqlClient> taskStore = org.mockito.Mockito.mock();

        RuntimeWorkflow rw = minimalRuntimeWorkflow();
        when(registry.resolveCurrent(DEFINITION_ID)).thenReturn(rw);

        UUID winningId = UUID.randomUUID();
        when(dedup.claimOrResolveStart(eq(DEFINITION_ID), any(), any(), any(), any()))
                .thenAnswer(inv -> Future.succeededFuture(
                        new StartDedupResult(new WorkflowInstanceId(winningId), true, "definitionVersion=1")));

        when(instances.insert(any(), any())).thenReturn(Future.succeededFuture());
        when(instances.updateOptimistic(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));
        when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        when(history.append(any(), any())).thenReturn(Future.succeededFuture());

        WorkflowEngineHandle handle = WorkflowEngineFactory.create(
                txRunner,
                registry,
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
        return new Harness(handle, instances, history, dedup, registry, propagator);
    }

    private static StartCommand startCommand() {
        return new StartCommand(DEFINITION_ID, Map.of(), "idem-" + UUID.randomUUID(), null, null);
    }

    @Test
    @DisplayName("start() captures the ambient durable context into the new instance's metadata")
    void start_capturesAmbientContextIntoInstanceMetadata(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness harness = buildHarness(propagator);
        SqlClient tx = org.mockito.Mockito.mock();

        runOnDuplicated(vertx, v -> {
            holder.bind(TenantCtx.class, new TenantCtx("T1"));
            harness.ops()
                    .start(startCommand(), tx)
                    .onComplete(ar -> ctx.verify(() -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                            return;
                        }
                        ArgumentCaptor<WorkflowInstance> captor = ArgumentCaptor.forClass(WorkflowInstance.class);
                        verify(harness.instances()).insert(captor.capture(), any());
                        DurableMetadata persisted = captor.getValue().metadata();
                        assertThat(persisted)
                                .as("captured ambient TenantCtx must be persisted on the new instance's metadata")
                                .isNotNull();
                        assertThat(persisted.body(TenantCtxCodec.NAMESPACE).map(body -> body.getString("tenantId")))
                                .contains("T1");
                        ctx.completeNow();
                    }));
        });
    }

    @Test
    @DisplayName("start() with no ambient durable context persists NULL metadata (not an empty document)")
    void start_withEmptyAmbientContext_persistsNullMetadata(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness harness = buildHarness(propagator);
        SqlClient tx = org.mockito.Mockito.mock();

        runOnDuplicated(vertx, v -> harness.ops()
                .start(startCommand(), tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    ArgumentCaptor<WorkflowInstance> captor = ArgumentCaptor.forClass(WorkflowInstance.class);
                    verify(harness.instances()).insert(captor.capture(), any());
                    assertThat(captor.getValue().metadata())
                            .as("an empty ambient capture must persist as null, not DurableMetadata.empty()")
                            .isNull();
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("start() is capture-only: no new durable scope is installed around the start drive")
    void start_isNotBound_ambientIsTheCaptureItself(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness harness = buildHarness(propagator);
        SqlClient tx = org.mockito.Mockito.mock();

        runOnDuplicated(vertx, v -> {
            holder.bind(TenantCtx.class, new TenantCtx("T1"));
            harness.ops()
                    .start(startCommand(), tx)
                    .onComplete(ar -> ctx.verify(() -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                            return;
                        }
                        // Start is capture-only (FR-WF-CTX-025 "start: none — no new bind"): the
                        // ambient TenantCtx bound before the call must still be exactly what was
                        // bound — no fresh install/replace touched it during doCreateInstance.
                        assertThat(holder.current(TenantCtx.class))
                                .as("no new durable scope should replace the ambient binding during start")
                                .contains(new TenantCtx("T1"));
                        ctx.completeNow();
                    }));
        });
    }
}
