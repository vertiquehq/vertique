// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.recorder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.context.ContextScopeBinder;
import dev.vertique.context.DefaultContextHolder;
import dev.vertique.context.DurableContextMetadataRegistry;
import dev.vertique.context.DurableContextPropagator;
import dev.vertique.job.delayed.DelayedJobOptions;
import dev.vertique.workflow.delayed.job.TimerFirePayload;
import dev.vertique.workflow.delayed.job.WorkflowTimerFireJob;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.sideeffect.IntentKind;
import dev.vertique.workflow.sideeffect.RecorderResult;
import dev.vertique.workflow.sideeffect.WorkflowSideEffectIntent;
import dev.vertique.workflow.timer.TimerIntentPayload;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link WorkflowTimerSideEffectRecorder}.
 *
 * <p>Verifies that the recorder:
 * <ul>
 *   <li>Declares {@link IntentKind#WORKFLOW_TIMER} from {@link WorkflowTimerSideEffectRecorder#kind()}.</li>
 *   <li>Enqueues a {@link dev.vertique.job.delayed.DelayedJobOptions} with {@code runAt} matching
 *       the intent's payload {@link Instant}.</li>
 *   <li>Calls {@link TimerStore#insertScheduled(TimerRecord, Object)} with the correct
 *       {@link TimerRecord} fields after the enqueue returns an execution ID.</li>
 *   <li>Returns {@link RecorderResult#ofTimer(UUID)} whose {@code timerId} matches the inserted
 *       timer row.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WorkflowTimerSideEffectRecorderTest {

    @Mock
    private TimerStore<SqlClient> timerStore;

    @Mock
    private WorkflowTimerFireJob fireJob;

    @Mock
    private SqlClient tx;

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private WorkflowTimerSideEffectRecorder recorder;

    @BeforeEach
    void setUp() {
        // Validator passed as null: it is only a construction-side-effect dependency; in unit
        // tests the Dagger graph is not involved. The propagator is a no-op (empty registries)
        // so the recorder's mergeCaptured call returns the supplied empty map unchanged — these
        // unit tests do not exercise the durable-context capture path; dual-write coverage with
        // a real encoder lives in the IT.
        recorder = new WorkflowTimerSideEffectRecorder(timerStore, fireJob, FIXED_CLOCK, noOpPropagator(), null);
    }

    private static DurableContextPropagator noOpPropagator() {
        DefaultContextHolder holder = new DefaultContextHolder();
        return new DurableContextPropagator(
                new DurableContextMetadataRegistry(java.util.Set.of(), java.util.Set.of()),
                holder,
                new ContextScopeBinder(holder));
    }

    @Test
    @DisplayName("kind() returns WORKFLOW_TIMER")
    void kind_returnsWorkflowTimer() {
        assertThat(recorder.kind()).isEqualTo(IntentKind.WORKFLOW_TIMER);
    }

    // --- Happy path ---

    @Nested
    @DisplayName("record() — happy path")
    class HappyPath {

        @Test
        @DisplayName("enqueues fire job with runAt matching the intent payload Instant")
        void record_enqueuesFireJobWithCorrectRunAt() {
            // Arrange
            Instant fireAt = Instant.parse("2026-05-08T11:00:00Z");
            UUID instanceUuid = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(instanceUuid);
            UUID fakeExecutionId = UUID.randomUUID();

            when(fireJob.enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx)))
                    .thenReturn(Future.succeededFuture(fakeExecutionId));
            when(timerStore.insertScheduled(any(TimerRecord.class), eq(tx))).thenReturn(Future.succeededFuture());

            WorkflowSideEffectIntent intent = buildIntent(fireAt, workflowId, "step-timer-1");

            // Act
            Future<RecorderResult> result = recorder.record(intent, tx);

            // Assert: future succeeded
            assertThat(result.succeeded()).isTrue();

            // Assert: enqueue called with correct runAt
            ArgumentCaptor<DelayedJobOptions> optionsCaptor = ArgumentCaptor.forClass(DelayedJobOptions.class);
            ArgumentCaptor<TimerFirePayload> payloadCaptor = ArgumentCaptor.forClass(TimerFirePayload.class);
            verify(fireJob).enqueue(payloadCaptor.capture(), optionsCaptor.capture(), eq(tx));

            assertThat(optionsCaptor.getValue().runAt()).isEqualTo(fireAt);
            assertThat(payloadCaptor.getValue().workflowId()).isEqualTo(workflowId);
        }

        @Test
        @DisplayName("inserts TimerRecord with SCHEDULED status and matching executionId")
        void record_insertsTimerRecordWithCorrectFields() {
            // Arrange
            Instant fireAt = Instant.parse("2026-05-08T12:00:00Z");
            UUID instanceUuid = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(instanceUuid);
            UUID fakeExecutionId = UUID.randomUUID();

            when(fireJob.enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx)))
                    .thenReturn(Future.succeededFuture(fakeExecutionId));
            when(timerStore.insertScheduled(any(TimerRecord.class), eq(tx))).thenReturn(Future.succeededFuture());

            WorkflowSideEffectIntent intent = buildIntent(fireAt, workflowId, "step-timer-2");

            // Act
            recorder.record(intent, tx);

            // Assert: insertScheduled called with expected TimerRecord
            ArgumentCaptor<TimerRecord> recordCaptor = ArgumentCaptor.forClass(TimerRecord.class);
            verify(timerStore).insertScheduled(recordCaptor.capture(), eq(tx));
            TimerRecord inserted = recordCaptor.getValue();

            assertThat(inserted.workflowId()).isEqualTo(workflowId);
            assertThat(inserted.stepId()).isEqualTo("step-timer-2");
            assertThat(inserted.fireAt()).isEqualTo(fireAt);
            assertThat(inserted.status()).isEqualTo(TimerStatus.SCHEDULED);
            assertThat(inserted.delayedJobExecutionId()).isEqualTo(fakeExecutionId);
            assertThat(inserted.scheduledAt()).isEqualTo(FIXED_NOW);
            assertThat(inserted.firedAt()).isNull();
            assertThat(inserted.cancelledAt()).isNull();
            assertThat(inserted.failedAt()).isNull();
            assertThat(inserted.failureReason()).isNull();
        }

        @Test
        @DisplayName("returns RecorderResult.ofTimer whose timerId matches the inserted row")
        void record_returnsTimerResultWithMatchingTimerId() {
            // Arrange
            Instant fireAt = Instant.parse("2026-05-08T13:00:00Z");
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID fakeExecutionId = UUID.randomUUID();

            when(fireJob.enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx)))
                    .thenReturn(Future.succeededFuture(fakeExecutionId));

            ArgumentCaptor<TimerRecord> recordCaptor = ArgumentCaptor.forClass(TimerRecord.class);
            when(timerStore.insertScheduled(recordCaptor.capture(), eq(tx))).thenReturn(Future.succeededFuture());

            WorkflowSideEffectIntent intent = buildIntent(fireAt, workflowId, "step-timer-3");

            // Act
            Future<RecorderResult> result = recorder.record(intent, tx);

            // Assert: result is Timer variant with the same timerId as the inserted record
            assertThat(result.succeeded()).isTrue();
            assertThat(result.result()).isInstanceOf(RecorderResult.Timer.class);
            UUID returnedTimerId = ((RecorderResult.Timer) result.result()).timerId();
            assertThat(returnedTimerId).isEqualTo(recordCaptor.getValue().timerId());
        }
    }

    // --- Branch identity propagation (PRD-WF-002 AC #5) ---

    @Nested
    @DisplayName("record() — branch identity propagation")
    class BranchIdentityPropagation {

        @Test
        @DisplayName("propagates branchTokenId/forkStepId/branchId from intent.correlation to TimerRecord")
        void record_propagatesBranchIdentity() {
            Instant fireAt = Instant.parse("2026-05-08T14:00:00Z");
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID branchTokenId = UUID.randomUUID();
            UUID fakeExecutionId = UUID.randomUUID();

            when(fireJob.enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx)))
                    .thenReturn(Future.succeededFuture(fakeExecutionId));
            when(timerStore.insertScheduled(any(TimerRecord.class), eq(tx))).thenReturn(Future.succeededFuture());

            WorkflowSideEffectIntent.Correlation branchCorrelation = new WorkflowSideEffectIntent.Correlation(
                    workflowId, 1L, "test-def", "branch-timer-step", branchTokenId, "fork-step", "branch-a");
            TimerIntentPayload payload = new TimerIntentPayload(fireAt, TimerPurpose.STANDALONE, null);
            WorkflowSideEffectIntent intent = new WorkflowSideEffectIntent(
                    IntentKind.WORKFLOW_TIMER, "branch-timer-step", payload, Map.of(), branchCorrelation);

            recorder.record(intent, tx);

            ArgumentCaptor<TimerRecord> recordCaptor = ArgumentCaptor.forClass(TimerRecord.class);
            verify(timerStore).insertScheduled(recordCaptor.capture(), eq(tx));
            TimerRecord inserted = recordCaptor.getValue();

            assertThat(inserted.branchTokenId()).isEqualTo(branchTokenId);
            assertThat(inserted.forkStepId()).isEqualTo("fork-step");
            assertThat(inserted.branchId()).isEqualTo("branch-a");
        }

        @Test
        @DisplayName("leaves branch identity null when correlation is single-path")
        void record_singlePathLeavesBranchIdentityNull() {
            Instant fireAt = Instant.parse("2026-05-08T15:00:00Z");
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID fakeExecutionId = UUID.randomUUID();

            when(fireJob.enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx)))
                    .thenReturn(Future.succeededFuture(fakeExecutionId));
            when(timerStore.insertScheduled(any(TimerRecord.class), eq(tx))).thenReturn(Future.succeededFuture());

            WorkflowSideEffectIntent intent = buildIntent(fireAt, workflowId, "single-path-step");

            recorder.record(intent, tx);

            ArgumentCaptor<TimerRecord> recordCaptor = ArgumentCaptor.forClass(TimerRecord.class);
            verify(timerStore).insertScheduled(recordCaptor.capture(), eq(tx));
            TimerRecord inserted = recordCaptor.getValue();

            assertThat(inserted.branchTokenId()).isNull();
            assertThat(inserted.forkStepId()).isNull();
            assertThat(inserted.branchId()).isNull();
        }
    }

    // --- Identity carriage (P2.S0 commit 4: workflow-timer carrier binding) ---

    @Nested
    @DisplayName("record() — identity carriage")
    class IdentityCarriage {

        @Test
        @DisplayName("enqueues the timer-fire job via ordinary enqueue — premergedMetadata is not set")
        void record_enqueuesFireJobWithoutPremergedMetadata() {
            // Arrange
            Instant fireAt = Instant.parse("2026-05-08T16:00:00Z");
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID fakeExecutionId = UUID.randomUUID();

            when(fireJob.enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx)))
                    .thenReturn(Future.succeededFuture(fakeExecutionId));
            when(timerStore.insertScheduled(any(TimerRecord.class), eq(tx))).thenReturn(Future.succeededFuture());

            WorkflowSideEffectIntent intent = buildIntent(fireAt, workflowId, "step-timer-identity");

            // Act
            recorder.record(intent, tx);

            // Assert: the timer-fire job enqueue must not carry premergedMetadata — identity
            // carriage for the job's own execution must flow through DelayedJobService.enqueue()'s
            // standard capture path (F5 row binding, PRD identity-002 §14.6/A9), not a premerged
            // blob signed for a different carrier (DelayedJobService.enqueuePremerged now rejects
            // premerged metadata carrying the identity-snapshot namespace).
            ArgumentCaptor<DelayedJobOptions> optionsCaptor = ArgumentCaptor.forClass(DelayedJobOptions.class);
            verify(fireJob).enqueue(any(TimerFirePayload.class), optionsCaptor.capture(), eq(tx));
            assertThat(optionsCaptor.getValue().premergedMetadata())
                    .as("the timer-fire job must go through ordinary enqueue (no premergedMetadata) so "
                            + "DelayedJobService captures and signs the identity snapshot for the job's own "
                            + "execution carrier, not a blob signed for the workflow-timer carrier")
                    .isNull();
        }
    }

    // --- Helpers ---

    /**
     * Builds a minimal {@code WORKFLOW_TIMER} intent for the given fire time and step id.
     *
     * @param fireAt     the resolved fire instant (placed as the intent payload)
     * @param workflowId the workflow instance id
     * @param stepId     the step id (placed as the intent targetId)
     * @return the constructed intent
     */
    private static WorkflowSideEffectIntent buildIntent(Instant fireAt, WorkflowInstanceId workflowId, String stepId) {
        WorkflowSideEffectIntent.Correlation correlation =
                WorkflowSideEffectIntent.Correlation.singlePath(workflowId, 1L, "test-def", stepId);
        TimerIntentPayload payload = new TimerIntentPayload(fireAt, TimerPurpose.STANDALONE, null);
        return new WorkflowSideEffectIntent(IntentKind.WORKFLOW_TIMER, stepId, payload, Map.of(), correlation);
    }
}
