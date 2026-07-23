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

import dev.vertique.context.ContextValues;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.core.correlation.CorrelationContext;
import dev.vertique.core.correlation.CorrelationIdentifier;
import dev.vertique.correlation.CorrelationContextFactory;
import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.TenantCtxCodec;
import dev.vertique.workflow.ops.TaskCompletionCommand;
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
import dev.vertique.workflow.state.BranchStatus;
import dev.vertique.workflow.state.BranchToken;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.tasks.TaskTransition;
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
 * Verifies {@code commandCorrelationId} history stitching on the engine-side command drives —
 * {@code migrate}, {@code cancel}, {@code retry}, and {@code taskCompleted} (PRD FR-WF-CTX-050/051,
 * AC-5, Contract Appendix C5).
 *
 * <p>Each drive is exercised with ambient correlation bound via {@link ContextValues#bind} on a
 * duplicated Vert.x context (the {@code runOnDuplicated} pattern shared with
 * {@link WorkflowContextBinderTest} / {@link WorkflowEngineBinderWiringTest}); the
 * {@code history.append(...)} call is intercepted to capture the appended
 * {@link WorkflowHistoryEntry#payloadJson()} and assert its {@code commandCorrelationId} field.
 */
@ExtendWith({VertxExtension.class, MockitoExtension.class})
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class HistoryPayloadCorrelationStitchingTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final String DEFINITION_ID = "wf-correlation-stitching-test";
    private static final String COMPLETE_STEP = "complete";

    /** Runs the given task on a duplicated Vert.x context. */
    private static void runOnDuplicated(Vertx vertx, Handler<Void> task) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(task);
    }

    private static CorrelationContext correlation(String correlationIdValue) {
        return new CorrelationContextFactory(Optional.empty())
                .create(
                        new CorrelationIdentifier("req-" + correlationIdValue, "test"),
                        new CorrelationIdentifier(correlationIdValue, "test"));
    }

    private static WorkflowInstance instanceAt(
            String stepId, WaitType waitType, String waitKey, WorkflowStatus status) {
        return instanceAt(stepId, waitType, waitKey, status, null);
    }

    private static WorkflowInstance instanceAt(
            String stepId,
            WaitType waitType,
            String waitKey,
            WorkflowStatus status,
            @jakarta.annotation.Nullable DurableMetadata metadata) {
        return new WorkflowInstance(
                new dev.vertique.workflow.ops.WorkflowInstanceId(UUID.randomUUID()),
                DEFINITION_ID,
                1L,
                "plan-hash",
                0L,
                status,
                null,
                null,
                stepId,
                waitType,
                waitKey,
                null,
                "{}",
                null,
                null,
                FIXED_NOW,
                FIXED_NOW,
                metadata);
    }

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
     * Builds a runtime workflow whose branch-owned human task decision ({@code "approve"}) advances
     * to a non-terminal {@code WaitSignalNode} step. Landing on a non-terminal branch step after
     * task completion keeps {@code ForkJoinCoordinator.driveAndMaybeJoin} on the simple
     * (non-terminal) path — no join/fork-terminal machinery needs stubbing for these tests, which
     * only assert the {@code BRANCH_TASK_COMPLETED} history payload appended just before the drive.
     */
    private static final String BRANCH_WAIT_STEP = "branch-wait-signal";

    private static RuntimeWorkflow buildBranchRuntimeWorkflow(WorkflowCallbackRegistry callbacks) {
        HumanTaskNode branchHumanTask = new HumanTaskNode(
                "branch-human-task",
                new HumanTaskNode.AssignmentSpec.User("user-1"),
                List.of(new HumanTaskNode.TaskDecision(
                        "approve", Object.class.getName(), new CallbackId("decision-applicator"), BRANCH_WAIT_STEP)),
                null,
                null,
                null,
                null,
                false);
        WaitSignalNode branchWaitSignal = new WaitSignalNode(
                BRANCH_WAIT_STEP, "next-signal", Object.class.getName(), new CallbackId("state-updater"), null, null);
        List<WorkflowNode> nodes = List.of(branchHumanTask, branchWaitSignal);
        WorkflowPlan plan = new WorkflowPlan(
                DEFINITION_ID, 1L, "plan-hash", Object.class.getName(), branchHumanTask.stepId(), nodes, null);
        return RuntimeWorkflow.of(plan, Object.class, Object.class, payload -> Map.of(), Map.of(), callbacks);
    }

    private static WorkflowCallbackRegistry stubCallbacks() {
        WorkflowCallbackRegistry callbacks = mock();
        org.mockito.Mockito.lenient()
                .when(callbacks.decisionApplicator(any()))
                .thenReturn((java.util.function.BiFunction<Object, Object, Object>) (state, payload) -> state);
        return callbacks;
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
            BranchTokenRepository<SqlClient> branchTokens) {}

    private static Harness buildHarness(DurableContextPropagator propagator) {
        WorkflowTransactionRunner<SqlClient> txRunner = mock();
        WorkflowRegistry registry = mock();
        WorkflowInstanceRepository<SqlClient> instances = mock();
        WorkflowHistoryRepository<SqlClient> history = mock();
        WorkflowDedupRepository<SqlClient> dedup = mock();
        BranchTokenRepository<SqlClient> branchTokens = mock();
        JoinStateRepository<SqlClient> joinStates = mock();
        TimerStore<SqlClient> timerStore = mock();
        TaskStore<SqlClient> taskStore = mock();

        org.mockito.Mockito.lenient()
                .when(instances.updateOptimistic(any(), anyLong(), any()))
                .thenReturn(Future.succeededFuture(1));

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
                branchTokens);
    }

    /**
     * Stubs {@code history.append} to capture the first appended entry's {@code payloadJson}, then
     * succeeds.
     */
    private static AtomicReference<String> captureAppendedPayload(WorkflowHistoryRepository<SqlClient> history) {
        AtomicReference<String> captured = new AtomicReference<>();
        org.mockito.Mockito.lenient().when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        doAnswer(inv -> {
                    WorkflowHistoryEntry entry = inv.getArgument(0);
                    captured.compareAndSet(null, entry.payloadJson());
                    return Future.succeededFuture();
                })
                .when(history)
                .append(any(), any());
        return captured;
    }

    // --- cancel ---

    @Test
    @DisplayName("cancel(): ambient correlation bound records commandCorrelationId on CancelledHistoryPayload")
    void cancel_ambientCorrelationBound_cancelledHistoryPayloadRecordsCorrelationId(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator);
        var captured = captureAppendedPayload(h.history());
        SqlClient tx = mock();

        WorkflowInstance inst = instanceAt(COMPLETE_STEP, WaitType.SIGNAL, "approve", WorkflowStatus.WAITING);
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.branchTokens().findActiveByWorkflow(inst.id(), tx)).thenReturn(Future.succeededFuture(List.of()));

        runOnDuplicated(vertx, v -> {
            ContextValues.bind(CorrelationContext.class, correlation("corr-E"));
            h.txOps()
                    .cancel(inst.id(), "because", tx)
                    .onComplete(ar -> ctx.verify(() -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                            return;
                        }
                        JsonObject payload = new JsonObject(captured.get());
                        assertThat(payload.getString("commandCorrelationId")).isEqualTo("corr-E");
                        ctx.completeNow();
                    }));
        });
    }

    @Test
    @DisplayName("cancel(): no correlation bound leaves commandCorrelationId absent/null on CancelledHistoryPayload")
    void cancel_noCorrelationBound_commandCorrelationIdIsNull(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator);
        var captured = captureAppendedPayload(h.history());
        SqlClient tx = mock();

        WorkflowInstance inst = instanceAt(COMPLETE_STEP, WaitType.SIGNAL, "approve", WorkflowStatus.WAITING);
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.branchTokens().findActiveByWorkflow(inst.id(), tx)).thenReturn(Future.succeededFuture(List.of()));

        runOnDuplicated(vertx, v -> h.txOps()
                .cancel(inst.id(), "because", tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    JsonObject payload = new JsonObject(captured.get());
                    assertThat(payload.getString("commandCorrelationId")).isNull();
                    ctx.completeNow();
                })));
    }

    // --- retry ---

    @Test
    @DisplayName("retry(): ambient correlation bound records commandCorrelationId on RetriedHistoryPayload")
    void retry_ambientCorrelationBound_retriedHistoryPayloadRecordsCorrelationId(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator);
        var captured = captureAppendedPayload(h.history());
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        WorkflowInstance inst = instanceAt(COMPLETE_STEP, null, null, WorkflowStatus.FAILED);
        when(h.instances().findByIdForUpdate(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));

        runOnDuplicated(vertx, v -> {
            ContextValues.bind(CorrelationContext.class, correlation("corr-E"));
            h.txOps()
                    .retry(inst.id(), tx)
                    .onComplete(ar -> ctx.verify(() -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                            return;
                        }
                        JsonObject payload = new JsonObject(captured.get());
                        assertThat(payload.getString("commandCorrelationId")).isEqualTo("corr-E");
                        ctx.completeNow();
                    }));
        });
    }

    // --- migrate ---

    @Test
    @DisplayName("migrate(): ambient correlation F records commandCorrelationId on WorkflowMigratedHistoryPayload")
    void migrate_ambientCorrelationF_workflowMigratedHistoryPayloadRecordsF(Vertx vertx, VertxTestContext ctx) {
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

        var captured = captureAppendedPayload(history);
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

        WorkflowInstance inst = instanceAt(COMPLETE_STEP, null, null, WorkflowStatus.WAITING);
        when(instances.findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(branchTokens.findActiveByWorkflow(inst.id(), tx)).thenReturn(Future.succeededFuture(List.of()));
        when(instances.migratePinAndState(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));
        // The target plan's resume step is a CompleteNode; driveTransitions lands there and calls
        // updateOptimistic to persist the COMPLETED status.
        when(instances.updateOptimistic(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));

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

        runOnDuplicated(vertx, v -> {
            ContextValues.bind(CorrelationContext.class, correlation("corr-F"));
            handle.migrate(inst.id(), 2L, tx)
                    .onComplete(ar -> ctx.verify(() -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                            return;
                        }
                        JsonObject payload = new JsonObject(captured.get());
                        assertThat(payload.getString("commandCorrelationId")).isEqualTo("corr-F");
                        ctx.completeNow();
                    }));
        });
    }

    // --- taskCompleted: ambient correlation authoritative over instance fallback (AC-5 side-effect half) ---

    @Test
    @DisplayName(
            "taskCompleted(): ambient correlation E — side effects (history payload) carry E over instance fallback")
    void taskCompleted_ambientCorrelationE_sideEffectsCarryEOverInstanceFallback(Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator);
        var captured = captureAppendedPayload(h.history());
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        UUID taskId = UUID.randomUUID();
        // Instance metadata carries a different (irrelevant here since this record has no
        // metadata-derived correlation source) namespace; the correlation observed inside the
        // bound drive is whatever is ambient/bound at call time, which the binder authoritatively
        // sets from the ambient base per FR-WF-CTX-020/024.
        WorkflowInstance inst = instanceAt("human-task", WaitType.TASK, taskId.toString(), WorkflowStatus.WAITING);
        TaskRecord task = new TaskRecord(
                taskId,
                inst.id(),
                "human-task",
                new dev.vertique.workflow.tasks.TaskAssignment.User("user-1"),
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
                null,
                null,
                null);
        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.dedup().claimOrResolveTaskCompletion(eq(taskId), any(), eq(inst.id()), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(new dev.vertique.workflow.engine.spi.DedupClaim(true, "fp")));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.timerStore().findScheduledRemindersForTask(taskId, tx)).thenReturn(Future.succeededFuture(List.of()));
        when(h.taskStore().lockForCompletion(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.taskStore().markCompleted(eq(taskId), eq("approve"), any(), any(), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(TaskTransition.APPLIED));

        TaskCompletionCommand cmd = new TaskCompletionCommand(
                taskId, "approve", Map.of(), "idem-1", new WorkflowActor.User("user-1"), null);

        runOnDuplicated(vertx, v -> {
            ContextValues.bind(CorrelationContext.class, correlation("corr-E"));
            h.taskCallbacks()
                    .taskCompleted(cmd, tx)
                    .onComplete(ar -> ctx.verify(() -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                            return;
                        }
                        JsonObject payload = new JsonObject(captured.get());
                        assertThat(payload.getString("commandCorrelationId")).isEqualTo("corr-E");
                        ctx.completeNow();
                    }));
        });
    }

    // --- taskCompleted: branch-owned completion instance-metadata correlation fallback (review
    // round-1 FIX 2) ---

    /**
     * Builds a {@link CorrelationContext} durable-metadata document under the {@code "correlation"}
     * namespace, mirroring the wire shape {@code CorrelationContextDurableEncoder} writes (a
     * {@code correlationId} object with {@code value}/{@code source} fields) so
     * {@code WorkflowPayloads.commandCorrelationId(WorkflowInstance)}'s field-name-mirroring
     * extraction is exercised against the real wire shape.
     */
    private static DurableMetadata correlationMetadata(String correlationIdValue) {
        JsonObject correlationBody = new JsonObject()
                .put("schemaVersion", 1)
                .put(
                        "requestId",
                        new JsonObject()
                                .put("value", "req-" + correlationIdValue)
                                .put("source", "test"))
                .put(
                        "correlationId",
                        new JsonObject().put("value", correlationIdValue).put("source", "test"));
        return DurableMetadata.of("correlation", correlationBody);
    }

    private static TaskRecord branchTaskAt(UUID taskId, WorkflowInstanceId workflowId, UUID branchTokenId) {
        return new TaskRecord(
                taskId,
                workflowId,
                "branch-human-task",
                new TaskAssignment.User("user-1"),
                TaskStatus.OPEN,
                List.of(new TaskDecisionDescriptor("approve", Object.class.getName(), BRANCH_WAIT_STEP)),
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
    }

    private static BranchToken waitingBranchToken(UUID branchTokenId, WorkflowInstanceId workflowId, UUID taskId) {
        return new BranchToken(
                branchTokenId,
                workflowId,
                "fork-step",
                "branch-1",
                "branch-human-task",
                BranchStatus.WAITING,
                WaitType.TASK,
                taskId.toString(),
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
                DurableMetadata.empty());
    }

    @Test
    @DisplayName("taskCompleted(): branch-owned completion, no ambient correlation, instance metadata carries"
            + " correlation X — payload records X")
    void taskCompleted_branchOwned_noAmbientCorrelation_instanceMetadataFallbackRecordsX(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator);
        var captured = captureAppendedPayload(h.history());
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildBranchRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        UUID taskId = UUID.randomUUID();
        UUID branchTokenId = UUID.randomUUID();
        WorkflowInstance inst = instanceAt(
                "branch-human-task", WaitType.JOIN, null, WorkflowStatus.WAITING, correlationMetadata("corr-X"));
        TaskRecord task = branchTaskAt(taskId, inst.id(), branchTokenId);
        BranchToken branch = waitingBranchToken(branchTokenId, inst.id(), taskId);

        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.dedup().claimOrResolveTaskCompletion(eq(taskId), any(), eq(inst.id()), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(new dev.vertique.workflow.engine.spi.DedupClaim(true, "fp")));
        when(h.timerStore().findScheduledRemindersForTask(taskId, tx)).thenReturn(Future.succeededFuture(List.of()));
        when(h.taskStore().lockForCompletion(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.branchTokens().findByIdForUpdate(branchTokenId, tx))
                .thenReturn(Future.succeededFuture(Optional.of(branch)));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.taskStore().markCompleted(eq(taskId), eq("approve"), any(), any(), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(TaskTransition.APPLIED));
        org.mockito.Mockito.lenient()
                .when(h.branchTokens().updateOptimistic(any(), anyLong(), any()))
                .thenReturn(Future.succeededFuture(1));

        TaskCompletionCommand cmd = new TaskCompletionCommand(
                taskId, "approve", Map.of(), "idem-1", new WorkflowActor.User("user-1"), null);

        // No ambient correlation is bound for this drive — the C2 bind-once rule keeps the
        // branch-owned path unbound; the fallback must read inst.metadata() instead.
        runOnDuplicated(vertx, v -> h.taskCallbacks()
                .taskCompleted(cmd, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    JsonObject payload = new JsonObject(captured.get());
                    assertThat(payload.getString("commandCorrelationId")).isEqualTo("corr-X");
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("taskCompleted(): branch-owned completion, ambient correlation E, instance metadata carries a"
            + " different correlation — payload records E (ambient wins)")
    void taskCompleted_branchOwned_ambientCorrelationE_ambientWinsOverInstanceMetadata(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator);
        var captured = captureAppendedPayload(h.history());
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildBranchRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        UUID taskId = UUID.randomUUID();
        UUID branchTokenId = UUID.randomUUID();
        WorkflowInstance inst = instanceAt(
                "branch-human-task",
                WaitType.JOIN,
                null,
                WorkflowStatus.WAITING,
                correlationMetadata("corr-instance-fallback"));
        TaskRecord task = branchTaskAt(taskId, inst.id(), branchTokenId);
        BranchToken branch = waitingBranchToken(branchTokenId, inst.id(), taskId);

        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.dedup().claimOrResolveTaskCompletion(eq(taskId), any(), eq(inst.id()), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(new dev.vertique.workflow.engine.spi.DedupClaim(true, "fp")));
        when(h.timerStore().findScheduledRemindersForTask(taskId, tx)).thenReturn(Future.succeededFuture(List.of()));
        when(h.taskStore().lockForCompletion(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.branchTokens().findByIdForUpdate(branchTokenId, tx))
                .thenReturn(Future.succeededFuture(Optional.of(branch)));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.taskStore().markCompleted(eq(taskId), eq("approve"), any(), any(), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(TaskTransition.APPLIED));
        org.mockito.Mockito.lenient()
                .when(h.branchTokens().updateOptimistic(any(), anyLong(), any()))
                .thenReturn(Future.succeededFuture(1));

        TaskCompletionCommand cmd = new TaskCompletionCommand(
                taskId, "approve", Map.of(), "idem-1", new WorkflowActor.User("user-1"), null);

        runOnDuplicated(vertx, v -> {
            ContextValues.bind(CorrelationContext.class, correlation("corr-E"));
            h.taskCallbacks()
                    .taskCompleted(cmd, tx)
                    .onComplete(ar -> ctx.verify(() -> {
                        if (ar.failed()) {
                            ctx.failNow(ar.cause());
                            return;
                        }
                        JsonObject payload = new JsonObject(captured.get());
                        assertThat(payload.getString("commandCorrelationId")).isEqualTo("corr-E");
                        ctx.completeNow();
                    }));
        });
    }

    // --- taskCompleted: branch-owned completion, malformed nested correlationId shape (Codex
    // round-2 FIX A) ---

    /**
     * Builds a durable-metadata document under the {@code "correlation"} namespace whose
     * {@code correlationId} field is a plain string rather than the nested {@code {value, source}}
     * object the correlation module's durable encoder actually writes. This mirrors a corrupted or
     * hand-edited persisted row: {@code namespace-body-level} decode validation
     * ({@link DurableMetadata#fromJson}) only requires the {@code correlation} namespace body itself
     * to be a JSON object — it does not descend into nested fields — so this malformed shape passes
     * decode and is only encountered when the fallback in
     * {@code WorkflowPayloads.commandCorrelationId(WorkflowInstance)} reads it.
     */
    private static DurableMetadata malformedNestedCorrelationMetadata() {
        JsonObject correlationBody = new JsonObject().put("correlationId", "not-an-object");
        return DurableMetadata.of("correlation", correlationBody);
    }

    @Test
    @DisplayName("taskCompleted(): branch-owned completion, no ambient correlation, instance metadata"
            + " correlationId is malformed (not an object) — payload records null commandCorrelationId,"
            + " completion succeeds")
    void taskCompleted_branchOwned_malformedNestedCorrelationId_recordsNullCommandCorrelationId(
            Vertx vertx, VertxTestContext ctx) {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);
        Harness h = buildHarness(propagator);
        var captured = captureAppendedPayload(h.history());
        SqlClient tx = mock();

        RuntimeWorkflow rw = buildBranchRuntimeWorkflow(stubCallbacks());
        when(h.registry().resolvePinned(DEFINITION_ID, 1L)).thenReturn(rw);

        UUID taskId = UUID.randomUUID();
        UUID branchTokenId = UUID.randomUUID();
        WorkflowInstance inst = instanceAt(
                "branch-human-task", WaitType.JOIN, null, WorkflowStatus.WAITING, malformedNestedCorrelationMetadata());
        TaskRecord task = branchTaskAt(taskId, inst.id(), branchTokenId);
        BranchToken branch = waitingBranchToken(branchTokenId, inst.id(), taskId);

        when(h.taskStore().findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.dedup().claimOrResolveTaskCompletion(eq(taskId), any(), eq(inst.id()), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(new dev.vertique.workflow.engine.spi.DedupClaim(true, "fp")));
        when(h.timerStore().findScheduledRemindersForTask(taskId, tx)).thenReturn(Future.succeededFuture(List.of()));
        when(h.taskStore().lockForCompletion(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(task)));
        when(h.branchTokens().findByIdForUpdate(branchTokenId, tx))
                .thenReturn(Future.succeededFuture(Optional.of(branch)));
        when(h.instances().findById(inst.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(h.taskStore().markCompleted(eq(taskId), eq("approve"), any(), any(), any(), eq(tx)))
                .thenReturn(Future.succeededFuture(TaskTransition.APPLIED));
        org.mockito.Mockito.lenient()
                .when(h.branchTokens().updateOptimistic(any(), anyLong(), any()))
                .thenReturn(Future.succeededFuture(1));

        TaskCompletionCommand cmd = new TaskCompletionCommand(
                taskId, "approve", Map.of(), "idem-1", new WorkflowActor.User("user-1"), null);

        // No ambient correlation is bound — the fallback must read inst.metadata(), encounter the
        // malformed nested correlationId, and return null rather than throwing ClassCastException.
        runOnDuplicated(vertx, v -> h.taskCallbacks()
                .taskCompleted(cmd, tx)
                .onComplete(ar -> ctx.verify(() -> {
                    if (ar.failed()) {
                        ctx.failNow(ar.cause());
                        return;
                    }
                    JsonObject payload = new JsonObject(captured.get());
                    assertThat(payload.getString("commandCorrelationId")).isNull();
                    ctx.completeNow();
                })));
    }
}
