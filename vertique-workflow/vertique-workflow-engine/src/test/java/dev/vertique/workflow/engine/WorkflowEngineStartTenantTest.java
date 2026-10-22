// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.context.InboundDispatchScope;
import dev.vertique.context.InboundExecutionContextScope;
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
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.plan.CompleteNode;
import dev.vertique.workflow.plan.WorkflowPlan;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowCallbackRegistry;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;

/**
 * Start-time scope tests for {@code WorkflowEngine#doCreateInstance}.
 *
 * <p>The engine is assembled through its package-private constructor over mocked repositories,
 * exactly as {@link WorkflowEngineFactory#create} does. The engine takes no scope source of its
 * own: an instance it starts is in the start scope of its command, and in the default scope when the command
 * names none, whatever the ambient context holds.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class WorkflowEngineStartTenantTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final String DEFINITION_ID = "wf-start-scope-test";
    private static final String INITIAL_STEP_ID = "complete";
    private static final String CONTRACT_NAME = "test-contract";
    private static final String FINGERPRINT = "definitionVersion=1";

    /** The assembled engine plus the mocked collaborators the scenarios assert on. */
    private record Rig(
            WorkflowEngine engine,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            TaskStore<SqlClient> taskStore) {}

    private static RuntimeWorkflow minimalRuntimeWorkflow() {
        WorkflowPlan plan = new WorkflowPlan(
                DEFINITION_ID,
                1L,
                "plan-hash",
                Object.class.getName(),
                INITIAL_STEP_ID,
                List.of(new CompleteNode(INITIAL_STEP_ID)),
                null);
        WorkflowCallbackRegistry callbacks = mock();
        return RuntimeWorkflow.of(
                plan, CONTRACT_NAME, Object.class, Object.class, payload -> Map.of(), Map.of(), callbacks);
    }

    /**
     * Assembles an engine like {@link WorkflowEngineFactory#create} does, with the given propagator
     * (nullable) and the dedup outcome the claim returns.
     */
    private static Rig rig(DurableContextPropagator propagator, StartDedupResult dedupOutcome) {
        WorkflowTransactionRunner<SqlClient> txRunner = mock();
        WorkflowRegistry registry = mock();
        WorkflowInstanceRepository<SqlClient> instances = mock();
        WorkflowHistoryRepository<SqlClient> history = mock();
        WorkflowDedupRepository<SqlClient> dedup = mock();
        BranchTokenRepository<SqlClient> branchTokens = mock();
        JoinStateRepository<SqlClient> joinStates = mock();
        TimerStore<SqlClient> timerStore = mock();
        TaskStore<SqlClient> taskStore = mock();

        DefinitionKey currentKey = new DefinitionKey(WorkflowScopes.DEFAULT, DEFINITION_ID, 1);
        when(registry.keysFor(DEFINITION_ID)).thenReturn(List.of(currentKey));
        when(registry.isDeployed(currentKey)).thenReturn(true);
        when(registry.resolve(currentKey)).thenReturn(minimalRuntimeWorkflow());
        when(dedup.findStart(any(), eq(DEFINITION_ID), any(), any()))
                .thenReturn(Future.succeededFuture(Optional.empty()));
        when(dedup.claimOrResolveStart(any(), eq(DEFINITION_ID), any(), any(), any(), any()))
                .thenReturn(Future.succeededFuture(dedupOutcome));
        when(instances.insert(any(), any())).thenReturn(Future.succeededFuture());
        when(instances.updateOptimistic(any(), anyLong(), any())).thenReturn(Future.succeededFuture(1));
        when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        when(history.append(any(), any())).thenReturn(Future.succeededFuture());

        Set<WorkflowSideEffectRecorder<SqlClient>> recorders = Set.of();
        WorkflowReminderComposeValidator complianceCheck = new WorkflowReminderComposeValidator(registry, recorders);
        RecorderRouter recorderRouter =
                new RecorderRouter(recorders, WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS, complianceCheck);
        WorkflowEventEmitter eventEmitter = new WorkflowEventEmitter(recorderRouter, FIXED_CLOCK);
        WorkflowHistoryRecorder historyRecorder = new WorkflowHistoryRecorder(history, eventEmitter, FIXED_CLOCK);
        ReminderScheduler reminderScheduler = new ReminderScheduler(history, recorderRouter, timerStore, FIXED_CLOCK);
        CompensationService compensationService =
                new CompensationService(instances, history, recorderRouter, eventEmitter, FIXED_CLOCK);
        InboundExecutionContextScope inboundExecScope = propagator == null
                ? null
                : new InboundExecutionContextScope(new InboundDispatchScope(), propagator, Set.of());
        BranchTransitionEngine branchEngine = inboundExecScope == null
                ? new BranchTransitionEngine(
                        branchTokens, dedup, history, recorderRouter, taskStore, FIXED_CLOCK, propagator)
                : new BranchTransitionEngine(
                        branchTokens,
                        dedup,
                        history,
                        recorderRouter,
                        taskStore,
                        FIXED_CLOCK,
                        propagator,
                        inboundExecScope);
        BranchCompensationOrchestrator branchCompensation =
                new BranchCompensationOrchestrator(history, recorderRouter, FIXED_CLOCK);

        WorkflowEngine engine = new WorkflowEngine(
                txRunner,
                registry,
                instances,
                history,
                dedup,
                recorderRouter,
                timerStore,
                taskStore,
                new FingerprintCanonicalizer(),
                FIXED_CLOCK,
                branchTokens,
                joinStates,
                branchEngine,
                branchCompensation,
                propagator,
                Optional.empty(),
                eventEmitter,
                historyRecorder,
                reminderScheduler,
                compensationService,
                inboundExecScope,
                new PayloadConstraintCheck(Optional.empty()));
        return new Rig(engine, instances, history, taskStore);
    }

    private static StartDedupResult winner() {
        return new StartDedupResult(new WorkflowInstanceId(UUID.randomUUID()), true, FINGERPRINT);
    }

    private static StartCommand startCommand(String startScope) {
        return new StartCommand(
                DEFINITION_ID,
                CONTRACT_NAME,
                Map.of(),
                "idem-" + UUID.randomUUID(),
                null,
                null,
                null,
                startScope,
                null);
    }

    /**
     * Starts a workflow on a duplicated Vert.x context, with the given tenant bound as the ambient
     * context first (none when {@code null}), and blocks until the start future settles.
     */
    private static AsyncResult<WorkflowInstanceId> start(
            Vertx vertx, Rig rig, DefaultContextHolder holder, String ambientTenant, String startScope)
            throws Exception {
        CompletableFuture<AsyncResult<WorkflowInstanceId>> settled = new CompletableFuture<>();
        ContextInternal duplicated = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        duplicated.runOnContext(v -> {
            if (ambientTenant != null) {
                holder.bind(TenantCtx.class, new TenantCtx(ambientTenant));
            }
            rig.engine()
                    .start(startCommand(startScope), ActiveTransactionClient.create())
                    .onComplete(settled::complete);
        });
        return settled.get(5, TimeUnit.SECONDS);
    }

    private static WorkflowInstance insertedInstance(Rig rig) {
        ArgumentCaptor<WorkflowInstance> inserted = ArgumentCaptor.forClass(WorkflowInstance.class);
        verify(rig.instances()).insert(inserted.capture(), any());
        return inserted.getValue();
    }

    @Test
    @DisplayName("a start stamps the start scope of its command on the instance, whatever the ambient context holds")
    void startStampsTheStartScopeOfItsCommand(Vertx vertx) throws Exception {
        DefaultContextHolder holder = new DefaultContextHolder();

        // Given an engine and an ambient context carrying t1
        Rig rig = rig(TenantCtxCodec.propagator(holder), winner());

        // When a workflow starts with the start scope acme
        AsyncResult<WorkflowInstanceId> started = start(vertx, rig, holder, "t1", "acme");

        // Then the stored instance has the acme start scope and the definition's own scope
        assertThat(started.succeeded()).as("start in acme: %s", started.cause()).isTrue();
        assertThat(insertedInstance(rig).scopeKey()).isEqualTo("acme");
        assertThat(insertedInstance(rig).definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
    }

    @Test
    @DisplayName("no tenant resolver type is shipped and every start lands in the default scope")
    void noResolverSeamIsShippedAndEveryStartLandsInTheDefaultScope(Vertx vertx) throws Exception {
        DefaultContextHolder holder = new DefaultContextHolder();
        DurableContextPropagator propagator = TenantCtxCodec.propagator(holder);

        // Given an engine with a durable-context propagator and an ambient context carrying t1
        Rig withPropagator = rig(propagator, winner());

        // When a workflow starts
        AsyncResult<WorkflowInstanceId> propagatedStart = start(vertx, withPropagator, holder, "t1", null);

        // Then the start succeeds and the stored instance is in the default scope
        assertThat(propagatedStart.succeeded())
                .as("start with a propagator: %s", propagatedStart.cause())
                .isTrue();
        assertThat(insertedInstance(withPropagator).scopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(insertedInstance(withPropagator).definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);

        // Given an engine without a propagator and the same ambient context
        Rig withoutPropagator = rig(null, winner());

        // When a workflow starts
        AsyncResult<WorkflowInstanceId> plainStart = start(vertx, withoutPropagator, holder, "t1", null);

        // Then the start succeeds and the stored instance is in the default scope
        assertThat(plainStart.succeeded())
                .as("start without a propagator: %s", plainStart.cause())
                .isTrue();
        assertThat(insertedInstance(withoutPropagator).scopeKey()).isEqualTo(WorkflowScopes.DEFAULT);
        assertThat(insertedInstance(withoutPropagator).definitionScopeKey()).isEqualTo(WorkflowScopes.DEFAULT);

        // When the former resolver type is looked up by name
        // Then it is not on the class path
        assertThatThrownBy(() -> Class.forName("dev.vertique.workflow.ops.WorkflowTenantResolver"))
                .isInstanceOf(ClassNotFoundException.class);
    }
}
