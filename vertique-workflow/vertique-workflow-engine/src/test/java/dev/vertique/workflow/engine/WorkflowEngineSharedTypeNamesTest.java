// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.actor.WorkflowActor;
import dev.vertique.workflow.contract.WorkflowContract;
import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.spi.BranchTokenRepository;
import dev.vertique.workflow.engine.spi.JoinStateRepository;
import dev.vertique.workflow.engine.spi.WorkflowDedupRepository;
import dev.vertique.workflow.engine.spi.WorkflowHistoryRepository;
import dev.vertique.workflow.engine.spi.WorkflowInstanceRepository;
import dev.vertique.workflow.engine.spi.WorkflowTransactionRunner;
import dev.vertique.workflow.engine.testsupport.AcceptingTaskReservations;
import dev.vertique.workflow.engine.testsupport.EmptyCommandRepository;
import dev.vertique.workflow.engine.testsupport.InMemoryWorkflowDefinitionRegistryStore;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.DefaultWorkflowRegistry;
import dev.vertique.workflow.registry.RuntimeWorkflow;
import dev.vertique.workflow.registry.WorkflowRegistry;
import dev.vertique.workflow.registry.store.DefinitionKey;
import dev.vertique.workflow.registry.store.WorkflowScopes;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectRecorder;
import dev.vertique.workflow.state.WaitType;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.tasks.TaskAssignment;
import dev.vertique.workflow.tasks.TaskDecisionDescriptor;
import dev.vertique.workflow.tasks.TaskRecord;
import dev.vertique.workflow.tasks.TaskStatus;
import dev.vertique.workflow.tasks.TaskStore;
import dev.vertique.workflow.timer.TimerStore;
import dev.vertique.workflow.types.DefaultWorkflowTypeNames;
import dev.vertique.workflow.types.WorkflowType;
import dev.vertique.workflow.types.WorkflowTypeNames;
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
import org.mockito.AdditionalAnswers;

/**
 * Verifies that an engine assembled over a registry that wraps the default registry resolves a decision payload by
 * its logical name through the index the wrapped registry fills, instead of looking the logical name up as a class.
 *
 * <p>The engine is assembled through {@link WorkflowEngineFactory}. When the factory offers an overload whose last
 * parameter is the shared {@link WorkflowTypeNames}, the test passes the registry's index through it; otherwise it
 * uses the plain overload.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class WorkflowEngineSharedTypeNamesTest {

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final String DEFINITION_ID = "shared-index-saga";

    // --- Fixture types ---

    /** Decision payload type that carries a logical name. */
    @WorkflowType("shared.approval")
    record Approval(boolean approved) {}

    record SharedState(String id) {}

    record SharedStart(String id) {}

    @WorkflowContract
    interface SharedContract {}

    private static final class ReachedTheTaskLock extends RuntimeException {
        ReachedTheTaskLock() {
            super("validation passed and the engine reached the task lock", null, false, false);
        }
    }

    private static WorkflowDefinition<SharedState, SharedContract> definition() {
        return new WorkflowDefinition<>() {
            @Override
            public Class<SharedContract> contract() {
                return SharedContract.class;
            }

            @Override
            public Class<SharedState> stateType() {
                return SharedState.class;
            }

            @Override
            public String definitionId() {
                return DEFINITION_ID;
            }

            @Override
            public long definitionVersion() {
                return 1L;
            }

            @Override
            public void define(WorkflowBuilder<SharedState> wf) {
                wf.init(SharedStart.class, start -> new SharedState(start.id()))
                        .initialStep("review")
                        .task("review")
                        .assignToRole("reviewers")
                        .decision("approve", Approval.class)
                        .onDecision((state, decision) -> state)
                        .toStep("done")
                        .build()
                        .complete("done");
            }
        };
    }

    private static void runOnDuplicated(Vertx vertx, Handler<Void> task) {
        ContextInternal dup = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        dup.runOnContext(task);
    }

    @Test
    @DisplayName("a decision payload named logically resolves through the index of a default registry")
    void decisionPayloadResolvesThroughTheIndexOfADefaultRegistry(Vertx vertx, VertxTestContext ctx) {
        completeTaskWithLogicallyNamedPayload(vertx, ctx, false);
    }

    @Test
    @DisplayName("a decision payload named logically resolves through the index of a wrapped default registry")
    void decisionPayloadResolvesThroughTheIndexOfAWrappedRegistry(Vertx vertx, VertxTestContext ctx) {
        completeTaskWithLogicallyNamedPayload(vertx, ctx, true);
    }

    private void completeTaskWithLogicallyNamedPayload(Vertx vertx, VertxTestContext ctx, boolean wrapRegistry) {
        // Given: a default registry over a shared index, holding a definition with a logically named decision payload,
        // and, when asked, a registry that only delegates to it, so the engine cannot ask it for its index
        DefaultWorkflowTypeNames names = new DefaultWorkflowTypeNames();
        DefaultWorkflowRegistry real = new DefaultWorkflowRegistry(null, Set.of(), names);
        real.register(definition());
        WorkflowRegistry wrapped =
                wrapRegistry ? mock(WorkflowRegistry.class, AdditionalAnswers.delegatesTo(real)) : real;
        RuntimeWorkflow rw = real.resolve(new DefinitionKey(WorkflowScopes.DEFAULT, DEFINITION_ID, 1L));
        assertThat(rw.plan().nodes().toString()).contains("shared.approval");

        WorkflowTransactionRunner<SqlClient> txRunner = mock();
        WorkflowInstanceRepository<SqlClient> instances = mock();
        WorkflowHistoryRepository<SqlClient> history = mock();
        WorkflowDedupRepository<SqlClient> dedup = mock();
        BranchTokenRepository<SqlClient> branchTokens = mock();
        JoinStateRepository<SqlClient> joinStates = mock();
        TimerStore<SqlClient> timerStore = mock();
        TaskStore<SqlClient> taskStore = mock();
        SqlClient tx = ActiveTransactionClient.create();
        WorkflowEngineHandle engine = createEngine(
                txRunner, wrapped, instances, history, dedup, branchTokens, joinStates, timerStore, taskStore, names);

        // And: an open task of the definition that waits for the approve decision
        UUID taskId = UUID.randomUUID();
        WorkflowInstance instance = new WorkflowInstance(
                new WorkflowInstanceId(UUID.randomUUID()),
                DEFINITION_ID,
                1L,
                rw.plan().planHash(),
                0L,
                WorkflowStatus.WAITING,
                null,
                null,
                "review",
                WaitType.TASK,
                taskId.toString(),
                null,
                "{}",
                null,
                null,
                FIXED_NOW,
                FIXED_NOW,
                null);
        TaskRecord task = new TaskRecord(
                taskId,
                instance.id(),
                "review",
                new TaskAssignment.User("user-1"),
                TaskStatus.OPEN,
                List.of(new TaskDecisionDescriptor("approve", "shared.approval", "done")),
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
        TaskRecord reserved = RecordedCompletionSegments.reserved(task);
        when(history.nextSequence(any(), any())).thenReturn(Future.succeededFuture(1L));
        when(history.append(any(), any())).thenReturn(Future.succeededFuture());
        org.mockito.Mockito.lenient().when(history.appendDecision(any(), any())).thenReturn(Future.succeededFuture());
        when(taskStore.findById(taskId, tx)).thenReturn(Future.succeededFuture(Optional.of(reserved)));
        when(instances.findById(instance.id(), tx)).thenReturn(Future.succeededFuture(Optional.of(instance)));
        when(timerStore.findScheduledRemindersForTask(taskId, tx)).thenReturn(Future.succeededFuture(List.of()));
        // The task lock is the first step after payload validation, so reaching it proves the payload type resolved.
        when(taskStore.lockForCompletion(taskId, tx)).thenReturn(Future.failedFuture(new ReachedTheTaskLock()));

        // When: the recorded completion is applied with a payload that binds to the logically named decision type
        runOnDuplicated(vertx, v -> RecordedCompletionSegments.run(
                        engine,
                        instance,
                        reserved,
                        "approve",
                        Map.of("approved", true),
                        new WorkflowActor.User("user-1"),
                        tx)
                .onComplete(ar -> ctx.verify(() -> {
                    // Then: validation resolved the type by its logical name and the engine went on to lock the task
                    assertThat(ar.failed()).isTrue();
                    assertThat(ar.cause())
                            .as("a failure other than the task lock means the payload type was not resolved")
                            .isInstanceOf(ReachedTheTaskLock.class);
                    ctx.completeNow();
                })));
    }

    // --- Helpers ---

    /**
     * Assembles the engine through the factory overload that takes the shared index when there is one, and through the
     * plain overload otherwise.
     */
    @SuppressWarnings("unchecked")
    private static WorkflowEngineHandle createEngine(
            WorkflowTransactionRunner<SqlClient> txRunner,
            WorkflowRegistry registry,
            WorkflowInstanceRepository<SqlClient> instances,
            WorkflowHistoryRepository<SqlClient> history,
            WorkflowDedupRepository<SqlClient> dedup,
            BranchTokenRepository<SqlClient> branchTokens,
            JoinStateRepository<SqlClient> joinStates,
            TimerStore<SqlClient> timerStore,
            TaskStore<SqlClient> taskStore,
            WorkflowTypeNames names) {
        return WorkflowEngineFactory.create(WorkflowEngineParts.builder()
                .txRunner(txRunner)
                .registry(registry)
                .instances(instances)
                .history(history)
                .dedup(dedup)
                .branchTokens(branchTokens)
                .joinStates(joinStates)
                .timerStore(timerStore)
                .taskStore(taskStore)
                .taskReservations(new AcceptingTaskReservations())
                .clock(FIXED_CLOCK)
                .recorders(Set.<WorkflowSideEffectRecorder<SqlClient>>of())
                .optionalIntentKinds(WorkflowEngineFactory.DEFAULT_OPTIONAL_INTENT_KINDS)
                .migrationRegistry(Optional.empty())
                .propagator(null)
                .typeNames(names)
                .commands(new EmptyCommandRepository())
                .definitionStore(new InMemoryWorkflowDefinitionRegistryStore().seedCodeFrom(registry))
                .build());
    }
}
