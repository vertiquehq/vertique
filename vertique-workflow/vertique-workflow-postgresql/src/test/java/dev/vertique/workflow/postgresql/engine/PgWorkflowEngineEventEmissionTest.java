// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.postgresql.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.workflow.dsl.WorkflowBuilder;
import dev.vertique.workflow.dsl.WorkflowDefinition;
import dev.vertique.workflow.engine.WorkflowEngineHandle;
import dev.vertique.workflow.events.WorkflowEventIntent;
import dev.vertique.workflow.events.WorkflowEventType;
import dev.vertique.workflow.ops.WorkflowInstanceId;
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
import dev.vertique.workflow.state.WorkflowHistoryEntry;
import dev.vertique.workflow.state.WorkflowInstance;
import dev.vertique.workflow.state.WorkflowStatus;
import dev.vertique.workflow.timer.TimerStatusTransition;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit-level tests asserting that {@link WorkflowEngineHandle} emits {@link WorkflowEventIntent}
 * payloads through the {@code WORKFLOW_EVENT} recorder at the correct lifecycle transition sites. The events of a
 * start come from the executor's first segment and are covered by {@code PgWorkflowEngineStartEventsIT}.
 *
 * <p>Uses Mockito stubs for the database layer and an in-memory {@link WorkflowSideEffectRecorder}
 * that captures emitted intents. Tests call the transactional engine methods directly (passing a
 * mocked {@link SqlClient}) so no real database is needed.
 *
 * <p>Covers the {@link WorkflowEventType#WORKFLOW_CANCELLED} emission site. The remaining sites are covered by
 * integration tests.
 */
class PgWorkflowEngineEventEmissionTest {

    // --- Domain types ---

    /** Minimal workflow state for test plans. */
    record State(String id) {}

    // --- Contract markers ---

    /** Contract marker for complete-immediately plans. */
    interface CompleteContract {}

    // --- Static workflow definitions ---

    /**
     * A minimal workflow: starts → transitions immediately to CompleteNode.
     */
    static final WorkflowDefinition<State, CompleteContract> COMPLETE_DEF = new WorkflowDefinition<>() {
        @Override
        public Class<CompleteContract> contract() {
            return CompleteContract.class;
        }

        @Override
        public Class<State> stateType() {
            return State.class;
        }

        @Override
        public String definitionId() {
            return "event-emit-complete";
        }

        @Override
        public long definitionVersion() {
            return 1L;
        }

        @Override
        public void define(WorkflowBuilder<State> wf) {
            wf.init(State.class, s -> s).initialStep("done").complete("done");
        }
    };

    // --- Captured intents ---

    /** All {@link WorkflowSideEffectIntent}s captured by the event recorder. */
    final List<WorkflowSideEffectIntent> capturedIntents = new ArrayList<>();

    /**
     * Returns the first captured {@link WorkflowEventIntent} with the given type, or throws
     * with a descriptive message listing what was actually captured.
     *
     * @param type the event type to find
     * @return the first matching event
     */
    WorkflowEventIntent findEvent(WorkflowEventType type) {
        return capturedIntents.stream()
                .filter(i -> i.kind() == IntentKind.WORKFLOW_EVENT)
                .map(i -> (WorkflowEventIntent) i.payload())
                .filter(e -> e.eventType() == type)
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + type + " event captured; captured: "
                        + capturedIntents.stream()
                                .filter(i -> i.kind() == IntentKind.WORKFLOW_EVENT)
                                .map(i -> ((WorkflowEventIntent) i.payload()).eventType())
                                .toList()));
    }

    // --- Mocks and engine ---

    PgWorkflowHistoryRepository historyRepo;
    PgWorkflowInstanceRepository instanceRepo;
    PgWorkflowDedupRepository dedupRepo;
    PgTaskStore taskStore;
    PgTimerStore timerStore;
    Pool pool;
    SqlClient tx;
    DefaultWorkflowRegistry registry;
    WorkflowEngineHandle engine;

    final AtomicLong seqCounter = new AtomicLong(0);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        capturedIntents.clear();
        seqCounter.set(0);

        historyRepo = mock(PgWorkflowHistoryRepository.class);
        instanceRepo = mock(PgWorkflowInstanceRepository.class);
        dedupRepo = mock(PgWorkflowDedupRepository.class);
        taskStore = mock(PgTaskStore.class);
        timerStore = mock(PgTimerStore.class);
        pool = mock(Pool.class);
        tx = ActiveTransactionConnection.create();

        // nextSequence returns incrementing longs.
        when(historyRepo.nextSequence(any(WorkflowInstanceId.class), any(SqlClient.class)))
                .thenAnswer(inv -> Future.succeededFuture(seqCounter.incrementAndGet()));

        // append always succeeds.
        when(historyRepo.append(any(WorkflowHistoryEntry.class), any(SqlClient.class)))
                .thenReturn(Future.succeededFuture());

        // listByInstance returns empty (no compensable steps).
        when(historyRepo.listByInstance(any(WorkflowInstanceId.class), any(SqlClient.class)))
                .thenReturn(Future.succeededFuture(List.of()));

        // taskStore.incrementRemindersFiredCount returns Optional.empty by default for tests that
        // do not exercise reminder firing — the engine treats that as "task closed" and no-ops.
        when(taskStore.incrementRemindersFiredCount(any(UUID.class), any()))
                .thenReturn(Future.succeededFuture(Optional.empty()));

        // No pending reminder timers.
        when(timerStore.findScheduledRemindersForTask(any(UUID.class), any()))
                .thenReturn(Future.succeededFuture(List.of()));

        // timerStore.markCancelled returns APPLIED.
        when(timerStore.markCancelled(any(UUID.class), any(Instant.class), any()))
                .thenReturn(Future.succeededFuture(TimerStatusTransition.APPLIED));

        // Event recorder.
        WorkflowSideEffectRecorder<SqlClient> eventRecorder = new WorkflowSideEffectRecorder<>() {
            @Override
            public IntentKind kind() {
                return IntentKind.WORKFLOW_EVENT;
            }

            @Override
            public Future<RecorderResult> record(WorkflowSideEffectIntent intent, SqlClient sqlTx) {
                capturedIntents.add(intent);
                return Future.succeededFuture(RecorderResult.empty());
            }
        };

        registry = new DefaultWorkflowRegistry();
        registry.register(COMPLETE_DEF);

        engine = PgWorkflowEngineTestSupport.create(
                pool,
                registry,
                instanceRepo,
                historyRepo,
                dedupRepo,
                Set.of(eventRecorder),
                Set.of(IntentKind.WORKFLOW_EVENT),
                timerStore,
                taskStore,
                Clock.fixed(Instant.parse("2026-05-09T00:00:00Z"), ZoneOffset.UTC));
    }

    /**
     * Creates a minimal {@link WorkflowInstance} with the given status.
     *
     * @param id           the instance id
     * @param definitionId the definition id
     * @param stepId       the current step id
     * @param status       the instance status
     * @return a minimal instance
     */
    private static WorkflowInstance instance(
            WorkflowInstanceId id, String definitionId, String stepId, WorkflowStatus status) {
        Instant now = Instant.parse("2026-05-09T00:00:00Z");
        return new WorkflowInstance(
                id,
                definitionId,
                1L,
                "test-hash",
                0L,
                status,
                null,
                null,
                stepId,
                null,
                null,
                null,
                "{}",
                null,
                null,
                now,
                now,
                null);
    }

    // --- Tests ---

    @Test
    @DisplayName("WORKFLOW_CANCELLED: cancel() emits WORKFLOW_CANCELLED with reason attribute")
    void cancelEmitsWorkflowCancelledEvent() {
        WorkflowInstanceId id = new WorkflowInstanceId(UUID.randomUUID());
        // A RUNNING instance with no wait state (timer = null).
        WorkflowInstance inst = instance(id, "event-emit-complete", "done", WorkflowStatus.RUNNING);
        when(instanceRepo.findById(eq(id), any(SqlClient.class))).thenReturn(Future.succeededFuture(Optional.of(inst)));
        when(instanceRepo.updateOptimistic(any(WorkflowInstance.class), any(Long.class), any(SqlClient.class)))
                .thenReturn(Future.succeededFuture(1));

        Future<Void> result = engine.cancel(id, "unit-test-cancellation", tx);

        assertTrue(result.succeeded(), "cancel() must succeed; cause: " + result.cause());

        WorkflowEventIntent event = findEvent(WorkflowEventType.WORKFLOW_CANCELLED);
        assertEquals(WorkflowEventType.WORKFLOW_CANCELLED, event.eventType());
        assertEquals("unit-test-cancellation", event.attributes().get("reason"));
    }
}
