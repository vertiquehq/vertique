// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.DedupClaim;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.TenantCtx;
import dev.vertique.workflow.engine.testsupport.TenantCtxCodec;
import dev.vertique.workflow.ops.TaskCompletionCommand;
import dev.vertique.workflow.ops.TaskReassignmentCommand;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import dev.vertique.workflow.ops.TransactionalTimerCallbacks;
import dev.vertique.workflow.ops.TransactionalWorkflowOperations;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.ops.WorkflowOperations;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.HumanTaskNode;
import dev.vertique.workflow.plan.TimerNode;
import dev.vertique.workflow.plan.TimerSpec;
import dev.vertique.workflow.plan.WaitSignalNode;
import dev.vertique.workflow.plan.WorkflowNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.CallbackId;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskReassignmentResult;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.tasks.TaskTransition;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Binder-row wiring tests for {@link WorkflowEngine}, {@link TaskLifecycleService}, and
 * {@link TimerLifecycleService} (PRD AC-7, FR-WF-CTX-020/024/025, and the bind-once routing rule
 * in Contract Appendix C2).
 *
 * <p>Each instance-owned single-path drive (signal, cancel, retry, migrate, task/timer lifecycle
 * callbacks with no branch token involved) must route the drive body through
 * {@link WorkflowContextBinder#withBound} after the instance row is loaded. Branch-owned drives
 * (proven here via the {@code timerFired} branch-owned negative case) must NOT be binder-bound —
 * that seam belongs to {@link BranchTransitionEngine}.
 *
 * <p>Uses the {@code runOnDuplicated} pattern plus the shared {@link TenantCtx} /
 * {@link TenantCtxCodec} test fixture, assembling the engine via
 * {@link WorkflowEngineFactory#create} exactly like {@link WorkflowEngineStartMetadataTest}. The
 * bind is observed by stubbing a mocked repository call inside the drive body (e.g.
 * {@code instances.updateOptimistic}) to capture the ambient {@link TenantCtx} synchronously at
 * call time, before returning a succeeded future.
 */
@ExtendWith({VertxExtension.class, MockitoExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WorkflowEngineBinderWiringTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final String DEFINITION_ID = "wf-binder-wiring-test";
    private static final String COMPLETE_STEP = "complete";

    /** Runs the given task on a duplicated Vert.x context. */
    private static void runOnDuplicated(Vertx vertx, Handler<Void> task) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(task);
    }

    /** Runs the given task directly on the calling (non-duplicated) Vert.x context. */
    private static void runOnNonDuplicated(Vertx vertx, Handler<Void> task) {
        vertx.getOrCreateContext().runOnContext(task);
    }

    private static dev.vertique.core.context.DurableMetadata tenantMetadata(String tenantId) {
        return dev.vertique.core.context.DurableMetadata.of(
                TenantCtxCodec.NAMESPACE, io.vertx.core.json.JsonObject.of("tenantId", tenantId));
    }

    /** Test harness bundling the assembled engine handle plus its mocked SPI collaborators. */
    private record Harness(
            WorkflowOperations ops,
            TransactionalWorkflowOperations<SqlClient> txOps,
            TransactionalTaskCallbacks<SqlClient> taskCallbacks,
            TransactionalTimerCallbacks<SqlClient> timerCallbacks,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            WorkflowRegistry registry,
            TaskStore<SqlClient> taskStore,
            TimerStore<SqlClient> timerStore,
            BranchTokenRepository<SqlClient> branchTokens,
            DefaultContextHolder holder) {}

    /**
     * Builds a {@link RuntimeWorkflow} whose plan covers every node shape the S4 entry points need:
     * a {@link WaitSignalNode} (signal), a {@link HumanTaskNode} with one decision (task complete /
     * reassign), a {@link TimerNode} (timer fire), and a terminal {@link CompleteNode}. All
     * callbacks are stubbed via a Mockito mock registry with lenient stubbing (each test only
     * exercises the callback its node needs).
     */
    private static RuntimeWorkflow buildRuntimeWorkflow(WorkflowCallbackRegistry callbacks) {
        WaitSignalNode waitSignal = new WaitSignalNode(
                "wait-signal", "approve", Object.class.getName(), new CallbackId("state-updater"), COMPLETE_STEP, null);
        HumanTaskNode humanTask = new HumanTaskNode(
                "human-task",
                new HumanTaskNode.AssignmentSpec.User("user-1"),
                List.of(new HumanTaskNode.TaskDecision(
                        "approve", Object.class.getName(), new CallbackId("decision-applicator"), COMPLETE_STEP)),
                null,
                null,
                null,
                null,
                false);
        TimerNode timerNode =
                new TimerNode("timer-node", COMPLETE_STEP, new TimerSpec.After(java.time.Duration.ofMinutes(1)));
        CompleteNode complete = new CompleteNode(COMPLETE_STEP);
        List<WorkflowNode> nodes = List.of(waitSignal, humanTask, timerNode, complete);
        WorkflowPlan plan = new WorkflowPlan(
                DEFINITION_ID, 1L, "plan-hash", Object.class.getName(), waitSignal.stepId(), nodes, null);
        return RuntimeWorkflow.of(plan, Object.class, Object.class, payload -> Map.of(), Map.of(), callbacks);
    }

    /**
     * Builds a {@link RuntimeWorkflow} whose plan has a branch-owned {@link TimerNode} at step
     * {@code "branch-timer-node"} advancing to the {@code "wait-signal"} {@link WaitSignalNode} —
     * a non-terminal node — so a branch drive that fires this timer parks {@code WAITING} again
     * instead of reaching a terminal status. Used by the branch-owned initializer-parity test so it
     * does not also need to exercise join evaluation (which would require a resolvable
     * {@link dev.vertique.workflow.plan.ForkNode}/{@link dev.vertique.workflow.plan.JoinNode} pair).
     */
    private static RuntimeWorkflow buildBranchTimerToWaitSignalRuntimeWorkflow(WorkflowCallbackRegistry callbacks) {
        WaitSignalNode waitSignal = new WaitSignalNode(
                "wait-signal", "approve", Object.class.getName(), new CallbackId("state-updater"), COMPLETE_STEP, null);
        TimerNode branchTimerNode =
                new TimerNode("branch-timer-node", "wait-signal", new TimerSpec.After(java.time.Duration.ofMinutes(1)));
        CompleteNode complete = new CompleteNode(COMPLETE_STEP);
        List<WorkflowNode> nodes = List.of(waitSignal, branchTimerNode, complete);
        WorkflowPlan plan = new WorkflowPlan(
                DEFINITION_ID, 1L, "plan-hash", Object.class.getName(), waitSignal.stepId(), nodes, null);
        return RuntimeWorkflow.of(plan, Object.class, Object.class, payload -> Map.of(), Map.of(), callbacks);
    }

    /**
     * Builds a {@link WorkflowInstance} with the given wait slot and (nullable) durable metadata.
     * {@code stepId} selects which plan node the instance is parked at.
     */
    private static WorkflowInstance instanceAt(
            String stepId,
            WaitType waitType,
            String waitKey,
            UUID waitAuxId,
            dev.vertique.core.context.DurableMetadata metadata) {
        return new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                DEFINITION_ID,
                1L,
                "plan-hash",
                0L,
                WorkflowStatus.WAITING,
                null,
                null,
                stepId,
                waitType,
                waitKey,
                waitAuxId,
                "{}",
                null,
                null,
                FIXED_NOW,
                FIXED_NOW,
                metadata);
    }

    /**
     * Assembles a {@link Harness} via {@link WorkflowEngineFactory#create} over fully-mocked
     * repository SPIs, wired with the given (nullable) propagator.
     */
    private static Harness buildHarness(DurableContextPropagator propagator, DefaultContextHolder holder) {
        WorkflowTransactionRunner<SqlClient> txRunner = mock();
        WorkflowRegistry registry = mock();
        WorkflowInstanceRepository<SqlClient> instances = mock();
        WorkflowHistoryRepository<SqlClient> history = mock();
        WorkflowDedupRepository<SqlClient> dedup = mock();
        BranchTokenRepository<SqlClient> branchTokens = mock();
        JoinStateRepository<SqlClient> joinStates = mock();
        TimerStore<SqlClient> timerStore = mock();
        TaskStore<SqlClient> taskStore = mock();

        // Lenient: not every test in this class reaches history writes (the strict-context ISE
        // tests short-circuit before the drive body runs at all), but most do — declaring these two
        // defaults once here (instead of per-test) avoids repeating them across ~20 test methods.
        org.mockito.Mockito.lenient().when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        org.mockito.Mockito.lenient().when(history.append(any(), any())).thenReturn(Future.succeededFuture());

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
        return new Harness(
                handle,
                handle,
                handle,
                handle,
                instances,
                history,
                dedup,
                registry,
                taskStore,
                timerStore,
                branchTokens,
                holder);
    }

    /**
     * Assembles a {@link Harness} via the {@link WorkflowEngineFactory#create} initializer-set
     * overload, threading {@code initializers} into the assembled
     * {@link dev.vertique.context.InboundExecutionContextScope} (factory initializer parity).
     *
     * @param propagator   the durable-context propagator to wire
     * @param holder       the context holder backing {@code propagator}
     * @param initializers the {@link dev.vertique.core.context.InboundContextInitializer} set to
     *                     thread through
     * @return the assembled harness
     */
    private static Harness buildHarnessWithInitializers(
            DurableContextPropagator propagator,
            DefaultContextHolder holder,
            Set<dev.vertique.core.context.InboundContextInitializer> initializers) {
        WorkflowTransactionRunner<SqlClient> txRunner = mock();
        WorkflowRegistry registry = mock();
        WorkflowInstanceRepository<SqlClient> instances = mock();
        WorkflowHistoryRepository<SqlClient> history = mock();
        WorkflowDedupRepository<SqlClient> dedup = mock();
        BranchTokenRepository<SqlClient> branchTokens = mock();
        JoinStateRepository<SqlClient> joinStates = mock();
        TimerStore<SqlClient> timerStore = mock();
        TaskStore<SqlClient> taskStore = mock();

        org.mockito.Mockito.lenient().when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        org.mockito.Mockito.lenient().when(history.append(any(), any())).thenReturn(Future.succeededFuture());

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
                propagator,
                initializers);
        return new Harness(
                handle,
                handle,
                handle,
                handle,
                instances,
                history,
                dedup,
                registry,
                taskStore,
                timerStore,
                branchTokens,
                holder);
    }

    /**
     * Stubs {@code instances.updateOptimistic} to capture the ambient {@link TenantCtx} observed
     * synchronously at call time (before the drive continues), then succeed with 1 row updated.
     */
    private static java.util.concurrent.atomic.AtomicReference<Optional<TenantCtx>> captureAmbientOnUpdate(
            WorkflowInstanceRepository<SqlClient> instances, DefaultContextHolder holder) {
        var captured = new java.util.concurrent.atomic.AtomicReference<Optional<TenantCtx>>();
        doAnswer(inv -> {
                    captured.set(holder.current(TenantCtx.class));
                    return Future.succeededFuture(1);
                })
                .when(instances)
                .updateOptimistic(any(), anyLong(), any());
        return captured;
    }

    // --- signal (instance-owned) ---

    @Test
    @DisplayName("signal(): instance-owned no-branch signal binds via the binder with instance fill")
    void signal_instanceOwnedNoBranch_bindsViaBinderWithInstanceFill(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        var captured = captureAmbientOnUpdate(h.instances(), holder);
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        WorkflowInstance inst = instanceAt("wait-signal", WaitType.SIGNAL, "approve", null, tenantMetadata("T1"));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.dedup().claimOrResolveSignal(eq(inst.id()), any(), eq(tx))).thenReturn(Future.succeededFuture(true));

        runOnDuplicated(vertx, v -> h.txOps()
                .signal(inst.id(), "approve", Map.of(), "dedup-1", tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(captured.get())
                            .as("instance fill (TenantCtx T1) must be observed inside the bound drive")
                            .contains(new TenantCtx("T1"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("signal(): duplicate-key loser performs no instance read (claim runs before the load)")
    void signal_duplicateDedupKeyRace_loserPerformsNoInstanceRead(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        // Loser: claimOrResolveSignal resolves false (a prior committed transaction already applied
        // the signal). No instance stub is registered — if the drive incorrectly loaded the instance
        // before (or regardless of) the claim, the unstubbed findById would return Mockito's default
        // null-Future, surfacing as an NPE and failing the test.
        when(h.dedup().claimOrResolveSignal(eq(id), any(), eq(tx))).thenReturn(Future.succeededFuture(false));

        runOnDuplicated(vertx, v -> h.txOps()
                .signal(id, "approve", Map.of(), "dedup-1", tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    org.mockito.Mockito.verify(h.instances(), org.mockito.Mockito.never())
                            .findById(any(), any());
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("signal(): a factory-assembled engine given the initializer-set overload runs registered"
            + " InboundContextInitializers (correlation seeding) on a bound drive")
    void signal_factoryAssembledWithInitializerOverload_runsRegisteredInboundContextInitializers(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        dev.vertique.correlation.CorrelationContextFactory correlationFactory =
                new dev.vertique.correlation.CorrelationContextFactory(Optional.empty());
        Set<dev.vertique.core.context.InboundContextInitializer> initializers =
                Set.of(new dev.vertique.correlation.CorrelationContextSeeder(holder, correlationFactory));
        Harness h = buildHarnessWithInitializers(propagator, holder, initializers);
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        // Gate opened via the tenant namespace only; no correlation namespace on the instance and
        // no ambient correlation bound — the seeder must fire because the effective document has no
        // correlation namespace.
        WorkflowInstance inst = instanceAt("wait-signal", WaitType.SIGNAL, "approve", null, tenantMetadata("T1"));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.dedup().claimOrResolveSignal(eq(inst.id()), any(), eq(tx))).thenReturn(Future.succeededFuture(true));

        var capturedCorrelation = new java.util.concurrent.atomic.AtomicReference<
                Optional<dev.vertique.core.correlation.CorrelationContext>>();
        doAnswer(inv -> {
                    capturedCorrelation.set(holder.current(dev.vertique.core.correlation.CorrelationContext.class));
                    return Future.succeededFuture(1);
                })
                .when(h.instances())
                .updateOptimistic(any(), anyLong(), any());

        runOnDuplicated(vertx, v -> h.txOps()
                .signal(inst.id(), "approve", Map.of(), "dedup-1", tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(capturedCorrelation.get())
                            .as("the registered CorrelationContextSeeder must have run and seeded a correlation"
                                    + " inside the bound drive — proving the initializer-set overload threads its"
                                    + " initializers into the InboundExecutionContextScope")
                            .isPresent();
                    assertThat(capturedCorrelation.get().get().correlationId().source())
                            .as("a freshly seeded correlation carries the seeded:<boundary> source marker")
                            .startsWith("seeded:");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("signal(): the no-initializer-set overload (parity default) does NOT run any"
            + " InboundContextInitializer even though a propagator is supplied")
    void signal_noInitializerSetOverload_doesNotRunInboundContextInitializers(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        WorkflowInstance inst = instanceAt("wait-signal", WaitType.SIGNAL, "approve", null, tenantMetadata("T1"));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.dedup().claimOrResolveSignal(eq(inst.id()), any(), eq(tx))).thenReturn(Future.succeededFuture(true));

        var capturedCorrelation = new java.util.concurrent.atomic.AtomicReference<
                Optional<dev.vertique.core.correlation.CorrelationContext>>();
        doAnswer(inv -> {
                    capturedCorrelation.set(holder.current(dev.vertique.core.correlation.CorrelationContext.class));
                    return Future.succeededFuture(1);
                })
                .when(h.instances())
                .updateOptimistic(any(), anyLong(), any());

        runOnDuplicated(vertx, v -> h.txOps()
                .signal(inst.id(), "approve", Map.of(), "dedup-1", tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(capturedCorrelation.get())
                            .as("the parity-default overload assembles WITHOUT inbound initializers, so no"
                                    + " correlation is seeded even though the drive is bound")
                            .isEmpty();
                    ctx.completeNow();
                })));
    }

    // --- cancel (instance-owned) ---

    @Test
    @DisplayName("cancel(): instance-owned cancel binds via the binder with ambient base + instance fill")
    void cancel_instanceOwned_bindsViaBinderWithAmbientBaseAndInstanceFill(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        var captured = captureAmbientOnUpdate(h.instances(), holder);
        SqlClient tx = mock();

        WorkflowInstance inst = instanceAt(COMPLETE_STEP, WaitType.SIGNAL, "approve", null, tenantMetadata("T1"));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.branchTokens().findActiveByWorkflow(inst.id(), tx)).thenReturn(Future.succeededFuture(List.of()));

        runOnDuplicated(vertx, v -> h.txOps()
                .cancel(inst.id(), "because", tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(captured.get())
                            .as("instance fill (TenantCtx T1) must be observed inside the bound cancel drive")
                            .contains(new TenantCtx("T1"));
                    ctx.completeNow();
                })));
    }

    // --- retry (instance-owned) ---

    @Test
    @DisplayName("retry(): instance-owned retry binds via the binder")
    void retry_instanceOwned_bindsViaBinder(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        var captured = captureAmbientOnUpdate(h.instances(), holder);
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        WorkflowInstance inst = new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                DEFINITION_ID,
                1L,
                "plan-hash",
                0L,
                WorkflowStatus.FAILED,
                null,
                null,
                COMPLETE_STEP,
                null,
                null,
                null,
                "{}",
                "ERR",
                "boom",
                FIXED_NOW,
                FIXED_NOW,
                tenantMetadata("T1"));
        when(h.instances().findByIdForUpdate(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));

        runOnDuplicated(vertx, v -> h.txOps()
                .retry(inst.id(), tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(captured.get())
                            .as("instance fill (TenantCtx T1) must be observed inside the bound retry drive")
                            .contains(new TenantCtx("T1"));
                    ctx.completeNow();
                })));
    }

    // --- migrate (instance-owned, via MigrationExecutor) ---

    @Test
    @DisplayName("migrate(): instance-owned migrate binds via the binder")
    void migrate_instanceOwned_bindsViaBinder(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);

        WorkflowTransactionRunner<SqlClient> txRunner = mock();
        WorkflowRegistry registry = mock();
        WorkflowInstanceRepository<SqlClient> instances = mock();
        WorkflowHistoryRepository<SqlClient> history = mock();
        WorkflowDedupRepository<SqlClient> dedup = mock();
        BranchTokenRepository<SqlClient> branchTokens = mock();
        JoinStateRepository<SqlClient> joinStates = mock();
        TimerStore<SqlClient> timerStore = mock();
        TaskStore<SqlClient> taskStore = mock();
        SqlClient tx = mock();

        when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        when(history.append(any(), any())).thenReturn(Future.succeededFuture());
        when(history.listRecentByInstance(any(), anyInt(), any())).thenReturn(Future.succeededFuture(List.of()));

        RuntimeWorkflow sourceRw = buildRuntimeWorkflow(stubCallbacks());
        WorkflowPlan targetPlan = new WorkflowPlan(
                DEFINITION_ID,
                2L,
                "plan-hash-v2",
                Object.class.getName(),
                COMPLETE_STEP,
                List.of(new CompleteNode(COMPLETE_STEP)),
                null);
        RuntimeWorkflow targetRw = RuntimeWorkflow.of(
                targetPlan, Object.class, Object.class, payload -> Map.of(), Map.of(), stubCallbacks());
        when(registry.resolvePinned(DEFINITION_ID, 1L)).thenReturn(sourceRw);
        when(registry.resolvePinned(DEFINITION_ID, 2L)).thenReturn(targetRw);

        WorkflowInstance inst = instanceAt(COMPLETE_STEP, null, null, null, tenantMetadata("T1"));
        when(instances.findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(branchTokens.findActiveByWorkflow(inst.id(), tx)).thenReturn(Future.succeededFuture(List.of()));
        var captured = captureAmbientOnUpdate(instances, holder);
        when(instances.migratePinAndState(any(), anyLong(), any())).thenAnswer(inv -> {
            captured.set(holder.current(TenantCtx.class));
            return Future.succeededFuture(1);
        });

        dev.vertique.workflow.migration.WorkflowMigrationRegistry migrationRegistry = mock();
        dev.vertique.workflow.migration.WorkflowMigrationHandler<Object, Object> handler = mock();
        when(handler.sourceStateType()).thenReturn((Class) Object.class);
        when(handler.migrate(any(), any()))
                .thenReturn(dev.vertique.workflow.migration.MigrationResult.anchorAtInitial(Map.of()));
        when(migrationRegistry.find(DEFINITION_ID, 1L, 2L)).thenReturn(Optional.of(handler));

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
                Optional.of(migrationRegistry),
                propagator);

        runOnDuplicated(vertx, v -> handle.migrate(inst.id(), 2L, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(captured.get())
                            .as("instance fill (TenantCtx T1) must be observed inside the bound migrate drive")
                            .contains(new TenantCtx("T1"));
                    ctx.completeNow();
                })));
    }

    // --- taskCompleted (instance-owned, no branch) ---

    @Test
    @DisplayName("taskCompleted(): instance-owned no-branch completion binds via the binder")
    void taskCompleted_instanceOwnedNoBranch_bindsViaBinder(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        var captured = captureAmbientOnUpdate(h.instances(), holder);
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        UUID taskId = UUID.randomUUID();
        WorkflowInstance inst = instanceAt("human-task", WaitType.TASK, taskId.toString(), null, tenantMetadata("T1"));
        TaskRecord task = minimalOpenTask(taskId, inst.id());
        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.dedup().claimOrResolveTaskCompletion(eq(taskId), any(), eq(inst.id()), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(new DedupClaim(true, "fp")));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.timerStore().findScheduledRemindersForTask(taskId, tx)).thenReturn(Future.succeededFuture(List.of()));
        when(h.taskStore().lockForCompletion(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.taskStore().markCompleted(eq(taskId), eq("approve"), any(), any(), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(TaskTransition.APPLIED));

        TaskCompletionCommand cmd = new TaskCompletionCommand(
                taskId, "approve", Map.of(), "idem-1", new WorkflowActor.User("user-1"), null);

        runOnDuplicated(vertx, v -> h.taskCallbacks()
                .taskCompleted(cmd, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(captured.get())
                            .as("instance fill (TenantCtx T1) must be observed inside the bound taskCompleted drive")
                            .contains(new TenantCtx("T1"));
                    ctx.completeNow();
                })));
    }

    // --- taskReassigned (instance-owned; no branch fork exists for this entry point) ---

    @Test
    @DisplayName("taskReassigned(): instance-owned reassignment binds via the binder")
    void taskReassigned_instanceOwned_bindsViaBinder(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        UUID taskId = UUID.randomUUID();
        WorkflowInstance inst = instanceAt("human-task", WaitType.TASK, taskId.toString(), null, tenantMetadata("T1"));
        TaskRecord task = minimalOpenTask(taskId, inst.id());
        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.dedup().claimOrResolveTaskReassignment(eq(taskId), any(), eq(inst.id()), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(new DedupClaim(true, "fp")));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));

        var captured = new java.util.concurrent.atomic.AtomicReference<Optional<TenantCtx>>();
        when(h.taskStore().reassign(eq(taskId), any(), any(), any(), any(), eq(tx)))
                .thenAnswer(inv -> {
                    captured.set(holder.current(TenantCtx.class));
                    return Future.succeededFuture(new TaskReassignmentResult.Applied(
                            taskId,
                            inst.id(),
                            "human-task",
                            new TaskAssignment.User("user-1"),
                            new TaskAssignment.User("user-2"),
                            new WorkflowActor.User("user-3"),
                            null,
                            FIXED_NOW));
                });

        TaskReassignmentCommand cmd = new TaskReassignmentCommand(
                taskId, new TaskAssignment.User("user-2"), new WorkflowActor.User("user-3"), "idem-1", null);

        runOnDuplicated(vertx, v -> h.taskCallbacks()
                .taskReassigned(cmd, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(captured.get())
                            .as("instance fill (TenantCtx T1) must be observed inside the bound taskReassigned drive")
                            .contains(new TenantCtx("T1"));
                    ctx.completeNow();
                })));
    }

    // --- taskDueFired (instance-owned, no branch) ---

    @Test
    @DisplayName("taskDueFired(): instance-owned no-branch due-date expiry binds via the binder")
    void taskDueFired_instanceOwned_bindsViaBinder(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        var captured = captureAmbientOnUpdate(h.instances(), holder);
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildDueDateRuntimeWorkflow(stubCallbacksWithDueMutator());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        UUID taskId = UUID.randomUUID();
        UUID dueTimerId = UUID.randomUUID();
        WorkflowInstance inst =
                instanceAt("human-task-due", WaitType.TASK, taskId.toString(), dueTimerId, tenantMetadata("T1"));
        TaskRecord task = openTaskWithDueDate(taskId, inst.id(), dueTimerId);
        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.timerStore().findScheduledRemindersForTask(taskId, tx)).thenReturn(Future.succeededFuture(List.of()));
        when(h.taskStore().lockForCompletion(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.taskStore().markExpired(eq(taskId), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(TaskTransition.APPLIED));

        runOnDuplicated(vertx, v -> h.taskCallbacks()
                .taskDueFired(inst.id(), taskId, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(captured.get())
                            .as("instance fill (TenantCtx T1) must be observed inside the bound taskDueFired drive")
                            .contains(new TenantCtx("T1"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName(
            "taskDueFired(): branch-owned due-date expiry is NOT binder-bound; the branch carrier seam governs instead")
    void taskDueFired_branchOwned_isNotBinderBound_branchCarrierGovernsInstead(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskId = UUID.randomUUID();
        UUID dueTimerId = UUID.randomUUID();
        UUID branchTokenId = UUID.randomUUID();
        TaskRecord branchTask = openBranchTaskWithDueDate(taskId, workflowId, dueTimerId, branchTokenId);
        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(branchTask)));
        when(h.timerStore().findScheduledRemindersForTask(taskId, tx)).thenReturn(Future.succeededFuture(List.of()));
        // Branch-owned path locks the task row first, then short-circuits to STALE_NOOP when the
        // lock observes a non-OPEN status — this never reaches the instance-path bind.
        TaskRecord closedBranchTask =
                branchTaskWithDueDateInStatus(taskId, workflowId, dueTimerId, branchTokenId, TaskStatus.CANCELLED);
        when(h.taskStore().lockForCompletion(taskId, tx))
                .thenReturn(Future.succeededFuture(Optional.of(closedBranchTask)));

        // No stub on instances.findById/updateOptimistic for the instance-path: if the branch-owned
        // route incorrectly fell through to the instance-path bind, it would call
        // instances.findById(workflowId, tx), which is unstubbed here and returns Mockito's default
        // null-Future — surfacing as a NullPointerException that fails the test, proving the
        // instance-path binder route was never taken for this branch-owned drive.
        runOnDuplicated(vertx, v -> h.taskCallbacks()
                .taskDueFired(workflowId, taskId, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(holder.current(TenantCtx.class))
                            .as("no instance-path bind should have touched the ambient TenantCtx binding")
                            .isEmpty();
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("taskDueFired(): non-duplicated context + non-null instance metadata fails with IllegalStateException")
    void taskDueFired_nonDuplicatedContextNonNullInstanceMetadata_throwsIllegalStateException(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        UUID taskId = UUID.randomUUID();
        UUID dueTimerId = UUID.randomUUID();
        WorkflowInstance inst =
                instanceAt("human-task-due", WaitType.TASK, taskId.toString(), dueTimerId, tenantMetadata("T1"));
        TaskRecord task = openTaskWithDueDate(taskId, inst.id(), dueTimerId);
        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));

        runOnNonDuplicated(vertx, v -> h.taskCallbacks()
                .taskDueFired(inst.id(), taskId, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    assertThat(ar.failed()).isTrue();
                    assertThat(ar.cause()).isInstanceOf(IllegalStateException.class);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("taskDueFired(): non-duplicated context + NULL instance metadata succeeds as today (bind gate skips)")
    void taskDueFired_nonDuplicatedContextNullInstanceMetadata_succeedsAsToday(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildDueDateRuntimeWorkflow(stubCallbacksWithDueMutator());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        UUID taskId = UUID.randomUUID();
        UUID dueTimerId = UUID.randomUUID();
        WorkflowInstance inst = instanceAt("human-task-due", WaitType.TASK, taskId.toString(), dueTimerId, null);
        TaskRecord task = openTaskWithDueDate(taskId, inst.id(), dueTimerId);
        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.timerStore().findScheduledRemindersForTask(taskId, tx)).thenReturn(Future.succeededFuture(List.of()));
        when(h.taskStore().lockForCompletion(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.taskStore().markExpired(eq(taskId), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(TaskTransition.APPLIED));
        when(h.instances().updateOptimistic(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));

        runOnNonDuplicated(vertx, v -> h.taskCallbacks()
                .taskDueFired(inst.id(), taskId, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    ctx.completeNow();
                })));
    }

    // --- taskReminderFired (instance-owned, no branch) ---

    @Test
    @DisplayName("taskReminderFired(): instance-owned no-branch reminder fire binds via the binder")
    void taskReminderFired_instanceOwned_bindsViaBinder(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildReminderRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        UUID taskId = UUID.randomUUID();
        UUID reminderTimerId = UUID.randomUUID();
        WorkflowInstance inst =
                instanceAt("human-task-reminder", WaitType.TASK, taskId.toString(), null, tenantMetadata("T1"));
        TaskRecord task = minimalOpenTaskAtStep(taskId, inst.id(), "human-task-reminder");
        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));

        var captured = new java.util.concurrent.atomic.AtomicReference<Optional<TenantCtx>>();
        when(h.taskStore().incrementRemindersFiredCount(taskId, tx)).thenAnswer(inv -> {
            captured.set(holder.current(TenantCtx.class));
            return Future.succeededFuture(Optional.of(1));
        });

        runOnDuplicated(vertx, v -> h.taskCallbacks()
                .taskReminderFired(inst.id(), taskId, reminderTimerId, FIXED_NOW, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(captured.get())
                            .as("instance fill (TenantCtx T1) must be observed inside the bound taskReminderFired"
                                    + " drive")
                            .contains(new TenantCtx("T1"));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("taskReminderFired(): branch-owned reminder fire is NOT binder-bound; the branch carrier seam governs"
            + " instead")
    void taskReminderFired_branchOwned_isNotBinderBound_branchCarrierGovernsInstead(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID taskId = UUID.randomUUID();
        UUID reminderTimerId = UUID.randomUUID();
        UUID branchTokenId = UUID.randomUUID();
        TaskRecord branchTask = new TaskRecord(
                taskId,
                workflowId,
                "branch-human-task-reminder",
                new TaskAssignment.User("user-1"),
                TaskStatus.OPEN,
                List.of(new TaskDecisionDescriptor("approve", Object.class.getName(), COMPLETE_STEP)),
                null,
                null,
                null,
                null,
                null,
                FIXED_NOW,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                branchTokenId,
                "fork-step",
                "branch-1");
        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(branchTask)));
        // Branch has moved on (no longer found) — doBranchTaskReminderFired no-ops immediately,
        // never reaching the instance-path bind.
        when(h.branchTokens().findById(branchTokenId, tx)).thenReturn(Future.succeededFuture(Optional.empty()));

        // No stub on instances.findById/updateOptimistic for the instance-path: if the branch-owned
        // route incorrectly fell through to the instance-path bind, it would call
        // instances.findById(workflowId, tx), which is unstubbed here and returns Mockito's default
        // null-Future — surfacing as a NullPointerException that fails the test, proving the
        // instance-path binder route was never taken for this branch-owned drive.
        runOnDuplicated(vertx, v -> h.taskCallbacks()
                .taskReminderFired(workflowId, taskId, reminderTimerId, FIXED_NOW, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(holder.current(TenantCtx.class))
                            .as("no instance-path bind should have touched the ambient TenantCtx binding")
                            .isEmpty();
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("taskReminderFired(): non-duplicated context + non-null instance metadata fails with"
            + " IllegalStateException")
    void taskReminderFired_nonDuplicatedContextNonNullInstanceMetadata_throwsIllegalStateException(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        UUID taskId = UUID.randomUUID();
        UUID reminderTimerId = UUID.randomUUID();
        WorkflowInstance inst =
                instanceAt("human-task-reminder", WaitType.TASK, taskId.toString(), null, tenantMetadata("T1"));
        TaskRecord task = minimalOpenTaskAtStep(taskId, inst.id(), "human-task-reminder");
        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));

        runOnNonDuplicated(vertx, v -> h.taskCallbacks()
                .taskReminderFired(inst.id(), taskId, reminderTimerId, FIXED_NOW, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    assertThat(ar.failed()).isTrue();
                    assertThat(ar.cause()).isInstanceOf(IllegalStateException.class);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("taskReminderFired(): non-duplicated context + NULL instance metadata succeeds as today (bind gate"
            + " skips)")
    void taskReminderFired_nonDuplicatedContextNullInstanceMetadata_succeedsAsToday(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildReminderRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        UUID taskId = UUID.randomUUID();
        UUID reminderTimerId = UUID.randomUUID();
        WorkflowInstance inst = instanceAt("human-task-reminder", WaitType.TASK, taskId.toString(), null, null);
        TaskRecord task = minimalOpenTaskAtStep(taskId, inst.id(), "human-task-reminder");
        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.taskStore().incrementRemindersFiredCount(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(1)));

        runOnNonDuplicated(vertx, v -> h.taskCallbacks()
                .taskReminderFired(inst.id(), taskId, reminderTimerId, FIXED_NOW, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    ctx.completeNow();
                })));
    }

    // --- timerFired (instance-owned, standalone timer, no branch) ---

    @Test
    @DisplayName("timerFired(): instance-owned no-branch standalone timer binds via the binder")
    void timerFired_instanceOwnedNoBranch_bindsViaBinder(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        var captured = captureAmbientOnUpdate(h.instances(), holder);
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        UUID timerId = UUID.randomUUID();
        WorkflowInstance inst =
                instanceAt("timer-node", WaitType.TIMER, timerId.toString(), null, tenantMetadata("T1"));
        when(h.timerStore().findById(timerId, tx))
                .thenReturn(Future.succeededFuture(Optional.of(instanceOwnedTimer(timerId, inst.id()))));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));

        runOnDuplicated(vertx, v -> h.timerCallbacks()
                .timerFired(inst.id(), timerId, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(captured.get())
                            .as("instance fill (TenantCtx T1) must be observed inside the bound timerFired drive")
                            .contains(new TenantCtx("T1"));
                    ctx.completeNow();
                })));
    }

    // --- timerFiringFailed (instance-owned) ---

    @Test
    @DisplayName("timerFiringFailed(): instance-owned failure binds via the binder")
    void timerFiringFailed_instanceOwned_bindsViaBinder(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        var captured = captureAmbientOnUpdate(h.instances(), holder);
        SqlClient tx = mock();

        UUID timerId = UUID.randomUUID();
        WorkflowInstance inst =
                instanceAt("timer-node", WaitType.TIMER, timerId.toString(), null, tenantMetadata("T1"));
        when(h.timerStore().lockForFiring(timerId, tx))
                .thenReturn(Future.succeededFuture(Optional.of(instanceOwnedTimer(timerId, inst.id()))));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.timerStore().markFailed(eq(timerId), any(), any(), eq(tx))).thenReturn(Future.succeededFuture());

        runOnDuplicated(vertx, v -> h.timerCallbacks()
                .timerFiringFailed(inst.id(), timerId, "TIMEOUT", "boom", tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(captured.get())
                            .as(
                                    "instance fill (TenantCtx T1) must be observed inside the bound timerFiringFailed drive")
                            .contains(new TenantCtx("T1"));
                    ctx.completeNow();
                })));
    }

    // --- Negative: branch-owned timer fire is NOT binder-bound (bind-once routing rule, C2) ---

    @Test
    @DisplayName("timerFired(): branch-owned timer fire is NOT binder-bound; the branch carrier seam governs instead")
    void timerFired_branchOwned_isNotBinderBound_branchCarrierGovernsInstead(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID timerId = UUID.randomUUID();
        UUID branchTokenId = UUID.randomUUID();

        TimerRecord branchTimer = new TimerRecord(
                timerId,
                workflowId,
                "branch-timer-node",
                FIXED_NOW,
                TimerStatus.SCHEDULED,
                UUID.randomUUID(),
                FIXED_NOW,
                null,
                null,
                null,
                null,
                TimerPurpose.STANDALONE,
                null,
                branchTokenId,
                "fork-step",
                "branch-1",
                dev.vertique.core.context.DurableMetadata.empty());
        when(h.timerStore().findById(timerId, tx)).thenReturn(Future.succeededFuture(Optional.of(branchTimer)));

        // The branch token has moved on (status CANCELLED, no longer WAITING on this timer) so
        // driveBranchTransitions is not exercised in this unit test — only that no instance-path
        // bind occurred is asserted.
        BranchToken branch = new BranchToken(
                branchTokenId,
                workflowId,
                "fork-step",
                "branch-1",
                "branch-timer-node",
                dev.vertique.workflow.state.BranchStatus.CANCELLED,
                null,
                null,
                null,
                null,
                null,
                null,
                0,
                3,
                null,
                null,
                null,
                null,
                0L,
                FIXED_NOW,
                FIXED_NOW,
                tenantMetadata("BRANCH"));
        when(h.branchTokens().findByIdForUpdate(branchTokenId, tx))
                .thenReturn(Future.succeededFuture(Optional.of(branch)));
        when(h.history().nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        when(h.history().append(any(), any())).thenReturn(Future.succeededFuture());

        // No stub on instances.findById/updateOptimistic for the instance-path: if the branch-owned
        // route incorrectly fell through to the instance-path bind, it would call
        // instances.findById(workflowId, tx), which is unstubbed here and returns Mockito's default
        // null-Future — surfacing as a NullPointerException that fails the test, proving the
        // instance-path binder route was never taken for this branch-owned drive.
        runOnDuplicated(vertx, v -> h.timerCallbacks()
                .timerFired(workflowId, timerId, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(holder.current(TenantCtx.class))
                            .as("no instance-path bind should have touched the ambient TenantCtx binding")
                            .isEmpty();
                    ctx.completeNow();
                })));
    }

    // --- Positive: factory-assembled branch drive runs registered InboundContextInitializers too
    // (review finding — the two-constructor factory split previously left BranchTransitionEngine's
    // inboundExecScope null, so a factory-assembled engine dropped correlation seeding, and any
    // other registered InboundContextInitializer, from every branch-owned drive) ---

    @Test
    @DisplayName("timerFired(): a factory-assembled engine given the initializer-set overload runs registered"
            + " InboundContextInitializers (correlation seeding) on a BRANCH-owned drive too")
    void timerFired_branchOwnedFactoryAssembledWithInitializerOverload_runsRegisteredInboundContextInitializers(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        dev.vertique.correlation.CorrelationContextFactory correlationFactory =
                new dev.vertique.correlation.CorrelationContextFactory(Optional.empty());
        Set<dev.vertique.core.context.InboundContextInitializer> initializers =
                Set.of(new dev.vertique.correlation.CorrelationContextSeeder(holder, correlationFactory));
        Harness h = buildHarnessWithInitializers(propagator, holder, initializers);
        SqlClient tx = mock();

        // A branch-timer plan whose TimerNode advances to a non-terminal WaitSignalNode, so the
        // driven branch parks WAITING again instead of reaching a terminal status — this avoids
        // pulling join evaluation (ForkNode/JoinNode resolution) into this binder-wiring-focused
        // test; only the branch drive's own context bind is under test here.
        RuntimeWorkflow rw = buildBranchTimerToWaitSignalRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        UUID timerId = UUID.randomUUID();
        UUID branchTokenId = UUID.randomUUID();

        TimerRecord branchTimer = new TimerRecord(
                timerId,
                workflowId,
                "branch-timer-node",
                FIXED_NOW,
                TimerStatus.SCHEDULED,
                UUID.randomUUID(),
                FIXED_NOW,
                null,
                null,
                null,
                null,
                TimerPurpose.STANDALONE,
                null,
                branchTokenId,
                "fork-step",
                "branch-1",
                dev.vertique.core.context.DurableMetadata.empty());
        when(h.timerStore().findById(timerId, tx)).thenReturn(Future.succeededFuture(Optional.of(branchTimer)));

        // Branch token is RUNNING and parked at the branch-owned TimerNode step, waiting on this
        // exact timer — doBranchStandaloneTimerFired advances it to "wait-signal" and drives.
        BranchToken branch = new BranchToken(
                branchTokenId,
                workflowId,
                "fork-step",
                "branch-1",
                "branch-timer-node",
                dev.vertique.workflow.state.BranchStatus.RUNNING,
                WaitType.TIMER,
                timerId.toString(),
                null,
                null,
                null,
                null,
                0,
                3,
                null,
                null,
                null,
                null,
                0L,
                FIXED_NOW,
                FIXED_NOW,
                null);
        when(h.branchTokens().findByIdForUpdate(branchTokenId, tx))
                .thenReturn(Future.succeededFuture(Optional.of(branch)));

        // Parent instance must carry the SAME id as workflowId (the timer's owning instance) —
        // doBranchStandaloneTimerFired loads it via instances.findById(workflowId, tx).
        WorkflowInstance inst = new WorkflowInstance(
                workflowId,
                DEFINITION_ID,
                1L,
                "plan-hash",
                0L,
                WorkflowStatus.WAITING,
                null,
                null,
                COMPLETE_STEP,
                WaitType.JOIN,
                null,
                null,
                "{}",
                null,
                null,
                FIXED_NOW,
                FIXED_NOW,
                tenantMetadata("T1"));
        when(h.instances().findById(workflowId, tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));

        when(h.history().nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        when(h.history().append(any(), any())).thenReturn(Future.succeededFuture());

        var capturedCorrelation = new java.util.concurrent.atomic.AtomicReference<
                Optional<dev.vertique.core.correlation.CorrelationContext>>();
        doAnswer(inv -> {
                    capturedCorrelation.set(holder.current(dev.vertique.core.correlation.CorrelationContext.class));
                    return Future.succeededFuture(1);
                })
                .when(h.branchTokens())
                .updateOptimistic(any(), anyLong(), any());

        runOnDuplicated(vertx, v -> h.timerCallbacks()
                .timerFired(workflowId, timerId, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    assertThat(capturedCorrelation.get())
                            .as("the registered CorrelationContextSeeder must have run and seeded a correlation"
                                    + " inside the BRANCH-owned drive — proving the factory threads the same"
                                    + " InboundExecutionContextScope into BranchTransitionEngine as it does into the"
                                    + " binder-row WorkflowContextBinder")
                            .isPresent();
                    assertThat(capturedCorrelation.get().get().correlationId().source())
                            .as("a freshly seeded correlation carries the seeded:<boundary> source marker")
                            .startsWith("seeded:");
                    ctx.completeNow();
                })));
    }

    // --- Strict-context tests (AC-7): non-duplicated context + non-null instance metadata => ISE ---

    @Test
    @DisplayName("signal(): non-duplicated context + non-null instance metadata fails with IllegalStateException")
    void signal_nonDuplicatedContextNonNullInstanceMetadata_throwsIllegalStateException(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        WorkflowInstance inst = instanceAt("wait-signal", WaitType.SIGNAL, "approve", null, tenantMetadata("T1"));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.dedup().claimOrResolveSignal(eq(inst.id()), any(), eq(tx))).thenReturn(Future.succeededFuture(true));

        runOnNonDuplicated(vertx, v -> h.txOps()
                .signal(inst.id(), "approve", Map.of(), "dedup-1", tx)
                .onComplete(ar -> ctx.verify(() -> {
                    assertThat(ar.failed())
                            .as("a non-duplicated context with non-null instance metadata must fail")
                            .isTrue();
                    assertThat(ar.cause()).isInstanceOf(IllegalStateException.class);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("signal(): non-duplicated context + NULL instance metadata succeeds as today (bind gate skips)")
    void signal_nonDuplicatedContextNullInstanceMetadata_succeedsAsToday(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        WorkflowInstance inst = instanceAt("wait-signal", WaitType.SIGNAL, "approve", null, null);
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.dedup().claimOrResolveSignal(eq(inst.id()), any(), eq(tx))).thenReturn(Future.succeededFuture(true));
        when(h.instances().updateOptimistic(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));

        runOnNonDuplicated(vertx, v -> h.txOps()
                .signal(inst.id(), "approve", Map.of(), "dedup-1", tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("cancel(): non-duplicated context + non-null instance metadata fails with IllegalStateException")
    void cancel_nonDuplicatedContextNonNullInstanceMetadata_throwsIllegalStateException(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        WorkflowInstance inst = instanceAt(COMPLETE_STEP, WaitType.SIGNAL, "approve", null, tenantMetadata("T1"));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));

        runOnNonDuplicated(vertx, v -> h.txOps()
                .cancel(inst.id(), "because", tx)
                .onComplete(ar -> ctx.verify(() -> {
                    assertThat(ar.failed()).isTrue();
                    assertThat(ar.cause()).isInstanceOf(IllegalStateException.class);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("cancel(): non-duplicated context + NULL instance metadata succeeds as today (bind gate skips)")
    void cancel_nonDuplicatedContextNullInstanceMetadata_succeedsAsToday(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        WorkflowInstance inst = instanceAt(COMPLETE_STEP, WaitType.SIGNAL, "approve", null, null);
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.branchTokens().findActiveByWorkflow(inst.id(), tx)).thenReturn(Future.succeededFuture(List.of()));
        when(h.instances().updateOptimistic(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));

        runOnNonDuplicated(vertx, v -> h.txOps()
                .cancel(inst.id(), "because", tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("retry(): non-duplicated context + non-null instance metadata fails with IllegalStateException")
    void retry_nonDuplicatedContextNonNullInstanceMetadata_throwsIllegalStateException(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        WorkflowInstance inst = new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                DEFINITION_ID,
                1L,
                "plan-hash",
                0L,
                WorkflowStatus.FAILED,
                null,
                null,
                COMPLETE_STEP,
                null,
                null,
                null,
                "{}",
                "ERR",
                "boom",
                FIXED_NOW,
                FIXED_NOW,
                tenantMetadata("T1"));
        when(h.instances().findByIdForUpdate(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));

        runOnNonDuplicated(vertx, v -> h.txOps()
                .retry(inst.id(), tx)
                .onComplete(ar -> ctx.verify(() -> {
                    assertThat(ar.failed()).isTrue();
                    assertThat(ar.cause()).isInstanceOf(IllegalStateException.class);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("retry(): non-duplicated context + NULL instance metadata succeeds as today (bind gate skips)")
    void retry_nonDuplicatedContextNullInstanceMetadata_succeedsAsToday(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        WorkflowInstance inst = new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                DEFINITION_ID,
                1L,
                "plan-hash",
                0L,
                WorkflowStatus.FAILED,
                null,
                null,
                COMPLETE_STEP,
                null,
                null,
                null,
                "{}",
                "ERR",
                "boom",
                FIXED_NOW,
                FIXED_NOW,
                null);
        when(h.instances().findByIdForUpdate(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.instances().updateOptimistic(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));

        runOnNonDuplicated(vertx, v -> h.txOps()
                .retry(inst.id(), tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("migrate(): non-duplicated context + non-null instance metadata fails with IllegalStateException")
    void migrate_nonDuplicatedContextNonNullInstanceMetadata_throwsIllegalStateException(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        MigrationHarness mh = buildHarnessWithMigration(propagator, holder);
        Harness h = mh.harness();
        SqlClient tx = mock();

        WorkflowInstance inst = instanceAt(COMPLETE_STEP, null, null, null, tenantMetadata("T1"));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));

        runOnNonDuplicated(vertx, v -> h.txOps()
                .migrate(inst.id(), 2L, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    assertThat(ar.failed()).isTrue();
                    assertThat(ar.cause()).isInstanceOf(IllegalStateException.class);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("migrate(): non-duplicated context + NULL instance metadata succeeds as today (bind gate skips)")
    void migrate_nonDuplicatedContextNullInstanceMetadata_succeedsAsToday(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        MigrationHarness mh = buildHarnessWithMigration(propagator, holder);
        Harness h = mh.harness();
        SqlClient tx = mock();

        RuntimeWorkflow sourceRw = buildRuntimeWorkflow(stubCallbacks());
        WorkflowPlan targetPlan = new WorkflowPlan(
                DEFINITION_ID,
                2L,
                "plan-hash-v2",
                Object.class.getName(),
                COMPLETE_STEP,
                List.of(new CompleteNode(COMPLETE_STEP)),
                null);
        RuntimeWorkflow targetRw = RuntimeWorkflow.of(
                targetPlan, Object.class, Object.class, payload -> Map.of(), Map.of(), stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(sourceRw);
        when(h.registry().resolvePinned(DEFINITION_ID, 2L)).thenReturn(targetRw);

        WorkflowInstance inst = instanceAt(COMPLETE_STEP, null, null, null, null);
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.branchTokens().findActiveByWorkflow(inst.id(), tx)).thenReturn(Future.succeededFuture(List.of()));
        when(h.history().listRecentByInstance(any(), anyInt(), any())).thenReturn(Future.succeededFuture(List.of()));
        when(h.instances().migratePinAndState(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));
        when(h.instances().updateOptimistic(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));

        dev.vertique.workflow.migration.WorkflowMigrationHandler<Object, Object> handler = mock();
        when(handler.sourceStateType()).thenReturn((Class) Object.class);
        when(handler.migrate(any(), any()))
                .thenReturn(dev.vertique.workflow.migration.MigrationResult.anchorAtInitial(Map.of()));
        when(mh.migrationRegistry().find(DEFINITION_ID, 1L, 2L)).thenReturn(Optional.of(handler));

        runOnNonDuplicated(vertx, v -> h.txOps()
                .migrate(inst.id(), 2L, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName(
            "taskCompleted(): non-duplicated context + non-null instance metadata fails with IllegalStateException")
    void taskCompleted_nonDuplicatedContextNonNullInstanceMetadata_throwsIllegalStateException(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        UUID taskId = UUID.randomUUID();
        WorkflowInstance inst = instanceAt("human-task", WaitType.TASK, taskId.toString(), null, tenantMetadata("T1"));
        TaskRecord task = minimalOpenTask(taskId, inst.id());
        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.dedup().claimOrResolveTaskCompletion(eq(taskId), any(), eq(inst.id()), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(new DedupClaim(true, "fp")));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));

        TaskCompletionCommand cmd = new TaskCompletionCommand(
                taskId, "approve", Map.of(), "idem-1", new WorkflowActor.User("user-1"), null);

        runOnNonDuplicated(vertx, v -> h.taskCallbacks()
                .taskCompleted(cmd, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    assertThat(ar.failed()).isTrue();
                    assertThat(ar.cause()).isInstanceOf(IllegalStateException.class);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("taskCompleted(): non-duplicated context + NULL instance metadata succeeds as today (bind gate skips)")
    void taskCompleted_nonDuplicatedContextNullInstanceMetadata_succeedsAsToday(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        UUID taskId = UUID.randomUUID();
        WorkflowInstance inst = instanceAt("human-task", WaitType.TASK, taskId.toString(), null, null);
        TaskRecord task = minimalOpenTask(taskId, inst.id());
        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.dedup().claimOrResolveTaskCompletion(eq(taskId), any(), eq(inst.id()), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(new DedupClaim(true, "fp")));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.timerStore().findScheduledRemindersForTask(taskId, tx)).thenReturn(Future.succeededFuture(List.of()));
        when(h.taskStore().lockForCompletion(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.taskStore().markCompleted(eq(taskId), eq("approve"), any(), any(), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(TaskTransition.APPLIED));
        when(h.instances().updateOptimistic(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));

        TaskCompletionCommand cmd = new TaskCompletionCommand(
                taskId, "approve", Map.of(), "idem-1", new WorkflowActor.User("user-1"), null);

        runOnNonDuplicated(vertx, v -> h.taskCallbacks()
                .taskCompleted(cmd, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName(
            "taskReassigned(): non-duplicated context + non-null instance metadata fails with IllegalStateException")
    void taskReassigned_nonDuplicatedContextNonNullInstanceMetadata_throwsIllegalStateException(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        UUID taskId = UUID.randomUUID();
        WorkflowInstance inst = instanceAt("human-task", WaitType.TASK, taskId.toString(), null, tenantMetadata("T1"));
        TaskRecord task = minimalOpenTask(taskId, inst.id());
        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.dedup().claimOrResolveTaskReassignment(eq(taskId), any(), eq(inst.id()), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(new DedupClaim(true, "fp")));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));

        TaskReassignmentCommand cmd = new TaskReassignmentCommand(
                taskId, new TaskAssignment.User("user-2"), new WorkflowActor.User("user-3"), "idem-1", null);

        runOnNonDuplicated(vertx, v -> h.taskCallbacks()
                .taskReassigned(cmd, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    assertThat(ar.failed()).isTrue();
                    assertThat(ar.cause()).isInstanceOf(IllegalStateException.class);
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName(
            "taskReassigned(): non-duplicated context + NULL instance metadata succeeds as today (bind gate skips)")
    void taskReassigned_nonDuplicatedContextNullInstanceMetadata_succeedsAsToday(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator, holder);
        SqlClient tx = mock();

        UUID taskId = UUID.randomUUID();
        WorkflowInstance inst = instanceAt("human-task", WaitType.TASK, taskId.toString(), null, null);
        TaskRecord task = minimalOpenTask(taskId, inst.id());
        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.dedup().claimOrResolveTaskReassignment(eq(taskId), any(), eq(inst.id()), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(new DedupClaim(true, "fp")));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.taskStore().reassign(eq(taskId), any(), any(), any(), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(new TaskReassignmentResult.Applied(
                        taskId,
                        inst.id(),
                        "human-task",
                        new TaskAssignment.User("user-1"),
                        new TaskAssignment.User("user-2"),
                        new WorkflowActor.User("user-3"),
                        null,
                        FIXED_NOW)));

        TaskReassignmentCommand cmd = new TaskReassignmentCommand(
                taskId, new TaskAssignment.User("user-2"), new WorkflowActor.User("user-3"), "idem-1", null);

        runOnNonDuplicated(vertx, v -> h.taskCallbacks()
                .taskReassigned(cmd, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    ctx.completeNow();
                })));
    }

    // --- Test helpers ---

    /** Builds a lenient, fully-stubbed callback registry covering every node in {@link #buildRuntimeWorkflow}. */
    private static WorkflowCallbackRegistry stubCallbacks() {
        WorkflowCallbackRegistry callbacks = org.mockito.Mockito.mock(
                WorkflowCallbackRegistry.class,
                org.mockito.Mockito.withSettings().lenient());
        when(callbacks.stateUpdater(new CallbackId("state-updater"))).thenReturn((a, b) -> Map.of());
        when(callbacks.decisionApplicator(new CallbackId("decision-applicator")))
                .thenReturn((a, b) -> Map.of());
        return callbacks;
    }

    /**
     * Builds a lenient, fully-stubbed callback registry identical to {@link #stubCallbacks()} plus
     * a registered state-mutator for {@code "due-mutator"}, used by {@link #buildDueDateRuntimeWorkflow}.
     */
    private static WorkflowCallbackRegistry stubCallbacksWithDueMutator() {
        WorkflowCallbackRegistry callbacks = stubCallbacks();
        when(callbacks.stateMutator(new CallbackId("due-mutator"))).thenReturn(a -> a);
        return callbacks;
    }

    /**
     * Builds a {@link RuntimeWorkflow} with a {@link HumanTaskNode} configured with a due-date
     * (step id {@code "human-task-due"}), for {@code taskDueFired} binder-wiring tests.
     */
    private static RuntimeWorkflow buildDueDateRuntimeWorkflow(WorkflowCallbackRegistry callbacks) {
        HumanTaskNode humanTaskDue = new HumanTaskNode(
                "human-task-due",
                new HumanTaskNode.AssignmentSpec.User("user-1"),
                List.of(new HumanTaskNode.TaskDecision(
                        "approve", Object.class.getName(), new CallbackId("decision-applicator"), COMPLETE_STEP)),
                new TimerSpec.After(java.time.Duration.ofMinutes(5)),
                new CallbackId("due-mutator"),
                COMPLETE_STEP,
                null,
                false);
        CompleteNode complete = new CompleteNode(COMPLETE_STEP);
        List<WorkflowNode> nodes = List.of(humanTaskDue, complete);
        WorkflowPlan plan = new WorkflowPlan(
                DEFINITION_ID, 1L, "plan-hash", Object.class.getName(), humanTaskDue.stepId(), nodes, null);
        return RuntimeWorkflow.of(plan, Object.class, Object.class, payload -> Map.of(), Map.of(), callbacks);
    }

    /**
     * Builds a {@link RuntimeWorkflow} with a {@link HumanTaskNode} configured with a
     * {@link ReminderSpec.OneShotOffsets} reminder schedule (step id {@code "human-task-reminder"}),
     * for {@code taskReminderFired} binder-wiring tests.
     */
    private static RuntimeWorkflow buildReminderRuntimeWorkflow(WorkflowCallbackRegistry callbacks) {
        HumanTaskNode humanTaskReminder = new HumanTaskNode(
                "human-task-reminder",
                new HumanTaskNode.AssignmentSpec.User("user-1"),
                List.of(new HumanTaskNode.TaskDecision(
                        "approve", Object.class.getName(), new CallbackId("decision-applicator"), COMPLETE_STEP)),
                null,
                null,
                null,
                new dev.vertique.workflow.plan.ReminderSpec.OneShotOffsets(List.of(java.time.Duration.ofMinutes(30))),
                false);
        CompleteNode complete = new CompleteNode(COMPLETE_STEP);
        List<WorkflowNode> nodes = List.of(humanTaskReminder, complete);
        WorkflowPlan plan = new WorkflowPlan(
                DEFINITION_ID, 1L, "plan-hash", Object.class.getName(), humanTaskReminder.stepId(), nodes, null);
        return RuntimeWorkflow.of(plan, Object.class, Object.class, payload -> Map.of(), Map.of(), callbacks);
    }

    /**
     * Builds an instance-owned {@link TaskRecord} in {@link TaskStatus#OPEN} status with a due-date
     * timer configured, for {@code taskDueFired} binder-wiring tests.
     */
    private static TaskRecord openTaskWithDueDate(UUID taskId, WorkflowInstanceId workflowId, UUID dueDateTimerId) {
        return new TaskRecord(
                taskId,
                workflowId,
                "human-task-due",
                new TaskAssignment.User("user-1"),
                TaskStatus.OPEN,
                List.of(new TaskDecisionDescriptor("approve", Object.class.getName(), COMPLETE_STEP)),
                FIXED_NOW.plusSeconds(300), // dueAt
                dueDateTimerId,
                null, // completedAt
                null, // cancelledAt
                null, // expiredAt
                FIXED_NOW, // updatedAt
                null, // decisionName
                null, // decisionPayloadJson
                null, // completedBy
                null, // cancelledBy
                null, // reassignedBy
                null, // cancellationReason
                null, // reassignmentReason
                null, // subjectVersionAtCreation
                null, // branchTokenId
                null, // forkStepId
                null); // branchId
    }

    /**
     * Builds a branch-owned {@link TaskRecord} in {@link TaskStatus#OPEN} status with a due-date
     * timer configured, for the {@code taskDueFired} branch-owned negative test.
     */
    private static TaskRecord openBranchTaskWithDueDate(
            UUID taskId, WorkflowInstanceId workflowId, UUID dueDateTimerId, UUID branchTokenId) {
        return branchTaskWithDueDateInStatus(taskId, workflowId, dueDateTimerId, branchTokenId, TaskStatus.OPEN);
    }

    /**
     * Builds a branch-owned {@link TaskRecord} with a due-date timer configured, in the given
     * {@link TaskStatus}. Used by {@link #openBranchTaskWithDueDate} (OPEN) and the
     * {@code taskDueFired} branch-owned negative test (a non-OPEN status observed under the
     * {@code lockForCompletion} row lock).
     */
    private static TaskRecord branchTaskWithDueDateInStatus(
            UUID taskId, WorkflowInstanceId workflowId, UUID dueDateTimerId, UUID branchTokenId, TaskStatus status) {
        return new TaskRecord(
                taskId,
                workflowId,
                "branch-human-task-due",
                new TaskAssignment.User("user-1"),
                status,
                List.of(new TaskDecisionDescriptor("approve", Object.class.getName(), COMPLETE_STEP)),
                FIXED_NOW.plusSeconds(300), // dueAt
                dueDateTimerId,
                null, // completedAt
                null, // cancelledAt
                null, // expiredAt
                FIXED_NOW, // updatedAt
                null, // decisionName
                null, // decisionPayloadJson
                null, // completedBy
                null, // cancelledBy
                null, // reassignedBy
                null, // cancellationReason
                null, // reassignmentReason
                null, // subjectVersionAtCreation
                branchTokenId,
                "fork-step",
                "branch-1");
    }

    /** Builds a minimal instance-owned {@link TaskRecord} in {@link TaskStatus#OPEN} status. */
    private static TaskRecord minimalOpenTask(UUID taskId, WorkflowInstanceId workflowId) {
        return minimalOpenTaskAtStep(taskId, workflowId, "human-task");
    }

    /**
     * Builds a minimal instance-owned {@link TaskRecord} in {@link TaskStatus#OPEN} status at the
     * given plan step id (used by tests whose {@link RuntimeWorkflow} parks the {@code HumanTaskNode}
     * at a step other than {@code "human-task"}, e.g. {@code "human-task-reminder"}).
     */
    private static TaskRecord minimalOpenTaskAtStep(UUID taskId, WorkflowInstanceId workflowId, String stepId) {
        return new TaskRecord(
                taskId,
                workflowId,
                stepId,
                new TaskAssignment.User("user-1"),
                TaskStatus.OPEN,
                List.of(new TaskDecisionDescriptor("approve", Object.class.getName(), COMPLETE_STEP)),
                null, // dueAt
                null, // dueDateTimerId
                null, // completedAt
                null, // cancelledAt
                null, // expiredAt
                FIXED_NOW, // updatedAt
                null, // decisionName
                null, // decisionPayloadJson
                null, // completedBy
                null, // cancelledBy
                null, // reassignedBy
                null, // cancellationReason
                null, // reassignmentReason
                null, // subjectVersionAtCreation
                null, // branchTokenId
                null, // forkStepId
                null); // branchId
    }

    /** Builds a minimal instance-owned (non-branch) standalone {@link TimerRecord}. */
    private static TimerRecord instanceOwnedTimer(UUID timerId, WorkflowInstanceId workflowId) {
        return new TimerRecord(
                timerId,
                workflowId,
                "timer-node",
                FIXED_NOW,
                TimerStatus.SCHEDULED,
                UUID.randomUUID(),
                FIXED_NOW,
                null,
                null,
                null,
                null,
                TimerPurpose.STANDALONE,
                null,
                null,
                null,
                null,
                dev.vertique.core.context.DurableMetadata.empty());
    }

    /** A {@link Harness} paired with the migration registry it was assembled with. */
    private record MigrationHarness(
            Harness harness, dev.vertique.workflow.migration.WorkflowMigrationRegistry migrationRegistry) {}

    /** Builds a {@link Harness} wired with a non-empty migration registry so {@code migrate} is enabled. */
    private static MigrationHarness buildHarnessWithMigration(
            DurableContextPropagator propagator, DefaultContextHolder holder) {
        WorkflowTransactionRunner<SqlClient> txRunner = mock();
        WorkflowRegistry registry = mock();
        WorkflowInstanceRepository<SqlClient> instances = mock();
        WorkflowHistoryRepository<SqlClient> history = mock();
        WorkflowDedupRepository<SqlClient> dedup = mock();
        BranchTokenRepository<SqlClient> branchTokens = mock();
        JoinStateRepository<SqlClient> joinStates = mock();
        TimerStore<SqlClient> timerStore = mock();
        TaskStore<SqlClient> taskStore = mock();

        // Lenient: the strict-context ISE test built via this harness short-circuits before any
        // history write is reached.
        org.mockito.Mockito.lenient().when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        org.mockito.Mockito.lenient().when(history.append(any(), any())).thenReturn(Future.succeededFuture());

        dev.vertique.workflow.migration.WorkflowMigrationRegistry migrationRegistry = mock();

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
                Optional.of(migrationRegistry),
                propagator);
        Harness h = new Harness(
                handle,
                handle,
                handle,
                handle,
                instances,
                history,
                dedup,
                registry,
                taskStore,
                timerStore,
                branchTokens,
                holder);
        return new MigrationHarness(h, migrationRegistry);
    }
}
