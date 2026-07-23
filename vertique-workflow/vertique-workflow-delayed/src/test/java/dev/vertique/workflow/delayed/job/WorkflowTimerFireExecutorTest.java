// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.job.JobContext;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TimerFiringResult;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import dev.vertique.workflow.ops.TransactionalTimerCallbacks;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import dev.vertique.workflow.timer.TimerStatusTransition;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
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
 * Unit tests for {@link WorkflowTimerFireExecutor}.
 *
 * <p>Verifies that the executor:
 * <ul>
 *   <li>Returns a succeeded {@link Future} and makes no engine callback when the timer row is
 *       absent (CASCADE-deleted).</li>
 *   <li>Returns a succeeded {@link Future} and makes no engine callback when the timer row is in
 *       a non-{@link TimerStatus#SCHEDULED} status.</li>
 *   <li>Dispatches {@code STANDALONE}/{@code SIGNAL_TIMEOUT} timers through
 *       {@link TransactionalTimerCallbacks#timerFired}:
 *       {@link TimerFiringResult#APPLIED} → {@code markFired};
 *       {@link TimerFiringResult#STALE_NOOP} → {@code markFailed} with
 *       {@code TIMER_INCONSISTENCY} reason.</li>
 *   <li>Dispatches {@code TASK_DUE} timers through
 *       {@link TransactionalTaskCallbacks#taskDueFired}:
 *       {@link TaskMutationResult#APPLIED}/{@link TaskMutationResult#LOST_TO_RACE} →
 *       {@code markFired}; {@link TaskMutationResult#STALE_NOOP} → {@code markFailed}; null
 *       {@code taskId} → {@code IllegalStateException}.</li>
 *   <li>Dispatches {@code TASK_REMINDER} timers through
 *       {@link TransactionalTaskCallbacks#taskReminderFired}: succeeded future →
 *       {@code markFired}; failed future → propagated; null {@code taskId} →
 *       {@code IllegalStateException}.</li>
 *   <li>Returns a failed {@link Future} (triggering a retry) when the engine throws
 *       {@link WorkflowConflictException}.</li>
 *   <li>Returns a failed {@link Future} when the payload workflow-id disagrees with the locked
 *       timer row.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WorkflowTimerFireExecutorTest {

    @Mock
    private Pool pool;

    @Mock
    private TimerStore<SqlClient> timerStore;

    @Mock
    private TransactionalTimerCallbacks<SqlClient> timerCallbacks;

    @Mock
    private TransactionalTaskCallbacks<SqlClient> taskCallbacks;

    @Mock
    private SqlConnection tx;

    @Mock
    private JobContext ctx;

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private WorkflowTimerFireExecutor executor;

    @BeforeEach
    void setUp() {
        executor =
                new WorkflowTimerFireExecutor(pool, timerStore, () -> timerCallbacks, () -> taskCallbacks, FIXED_CLOCK);
        // Make pool.withTransaction delegate directly to the lambda using the test connection.
        when(pool.withTransaction(any())).thenAnswer(inv -> {
            var fn = inv.<java.util.function.Function<SqlConnection, Future<Object>>>getArgument(0);
            return fn.apply(tx);
        });
    }

    // --- No-op: timer row absent ---

    @Nested
    @DisplayName("lockForFiring returns empty")
    class TimerAbsent {

        @Test
        @DisplayName("returns succeeded Future and makes no engine callback")
        void execute_timerAbsent_noCallback() {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            TimerFirePayload payload = new TimerFirePayload(timerId, workflowId, null);

            when(timerStore.lockForFiring(eq(timerId), eq(tx))).thenReturn(Future.succeededFuture(Optional.empty()));

            Future<Void> result = executor.execute(payload, ctx);

            assertThat(result.succeeded()).isTrue();
            verify(timerCallbacks, never()).timerFired(any(), any(), any());
            verify(taskCallbacks, never()).taskDueFired(any(), any(), any());
            verify(taskCallbacks, never()).taskReminderFired(any(), any(), any(), any(), any());
            verify(timerStore, never()).markFired(any(), any(), any());
            verify(timerStore, never()).markFailed(any(), any(), any(), any());
        }
    }

    // --- No-op: timer already in non-SCHEDULED status ---

    @Nested
    @DisplayName("lockForFiring returns non-SCHEDULED row")
    class TimerNotScheduled {

        @Test
        @DisplayName("CANCELLED status — returns succeeded Future and makes no engine callback")
        void execute_timerCancelled_noCallback() {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            TimerFirePayload payload = new TimerFirePayload(timerId, workflowId, null);

            TimerRecord cancelledRow =
                    buildRecord(timerId, workflowId, TimerStatus.CANCELLED, TimerPurpose.STANDALONE, null);
            when(timerStore.lockForFiring(eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(Optional.of(cancelledRow)));

            Future<Void> result = executor.execute(payload, ctx);

            assertThat(result.succeeded()).isTrue();
            verify(timerCallbacks, never()).timerFired(any(), any(), any());
        }

        @Test
        @DisplayName("FIRED status — returns succeeded Future and makes no engine callback")
        void execute_timerAlreadyFired_noCallback() {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            TimerFirePayload payload = new TimerFirePayload(timerId, workflowId, null);

            TimerRecord firedRow = buildRecord(timerId, workflowId, TimerStatus.FIRED, TimerPurpose.STANDALONE, null);
            when(timerStore.lockForFiring(eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(Optional.of(firedRow)));

            Future<Void> result = executor.execute(payload, ctx);

            assertThat(result.succeeded()).isTrue();
            verify(timerCallbacks, never()).timerFired(any(), any(), any());
        }
    }

    // --- STANDALONE + APPLIED ---

    @Nested
    @DisplayName("STANDALONE timer SCHEDULED + engine returns APPLIED")
    class StandaloneAppliedPath {

        @Test
        @DisplayName("calls timerFired then markFired with current instant")
        void execute_standalone_applied_callsMarkFired() {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            TimerFirePayload payload = new TimerFirePayload(timerId, workflowId, null);

            TimerRecord scheduledRow =
                    buildRecord(timerId, workflowId, TimerStatus.SCHEDULED, TimerPurpose.STANDALONE, null);
            when(timerStore.lockForFiring(eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(Optional.of(scheduledRow)));
            when(timerCallbacks.timerFired(eq(workflowId), eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(TimerFiringResult.APPLIED));
            when(timerStore.markFired(eq(timerId), eq(FIXED_NOW), eq(tx)))
                    .thenReturn(Future.succeededFuture(TimerStatusTransition.APPLIED));

            Future<Void> result = executor.execute(payload, ctx);

            assertThat(result.succeeded()).isTrue();
            verify(timerCallbacks).timerFired(workflowId, timerId, tx);
            verify(timerStore).markFired(timerId, FIXED_NOW, tx);
            verify(timerStore, never()).markFailed(any(), any(), any(), any());
            verify(taskCallbacks, never()).taskDueFired(any(), any(), any());
            verify(taskCallbacks, never()).taskReminderFired(any(), any(), any(), any(), any());
        }
    }

    // --- STANDALONE + STALE_NOOP ---

    @Nested
    @DisplayName("STANDALONE timer SCHEDULED + engine returns STALE_NOOP")
    class StandaloneStaleNoopPath {

        @Test
        @DisplayName("calls timerFired then markFailed with TIMER_INCONSISTENCY reason")
        void execute_standalone_staleNoop_callsMarkFailed() {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            TimerFirePayload payload = new TimerFirePayload(timerId, workflowId, null);

            TimerRecord scheduledRow =
                    buildRecord(timerId, workflowId, TimerStatus.SCHEDULED, TimerPurpose.STANDALONE, null);
            when(timerStore.lockForFiring(eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(Optional.of(scheduledRow)));
            when(timerCallbacks.timerFired(eq(workflowId), eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(TimerFiringResult.STALE_NOOP));

            ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
            when(timerStore.markFailed(eq(timerId), eq(FIXED_NOW), reasonCaptor.capture(), eq(tx)))
                    .thenReturn(Future.succeededFuture(TimerStatusTransition.APPLIED));

            Future<Void> result = executor.execute(payload, ctx);

            assertThat(result.succeeded()).isTrue();
            verify(timerCallbacks).timerFired(workflowId, timerId, tx);
            verify(timerStore).markFailed(eq(timerId), eq(FIXED_NOW), any(), eq(tx));
            assertThat(reasonCaptor.getValue()).contains("TIMER_INCONSISTENCY");
            verify(timerStore, never()).markFired(any(), any(), any());
        }
    }

    // --- SIGNAL_TIMEOUT + STALE_NOOP ---

    @Nested
    @DisplayName("SIGNAL_TIMEOUT timer SCHEDULED + engine returns STALE_NOOP")
    class SignalTimeoutStaleNoopPath {

        @Test
        @DisplayName("calls timerFired then markFailed with TIMER_INCONSISTENCY reason")
        void execute_signalTimeout_staleNoop_callsMarkFailed() {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            TimerFirePayload payload = new TimerFirePayload(timerId, workflowId, null);

            TimerRecord scheduledRow =
                    buildRecord(timerId, workflowId, TimerStatus.SCHEDULED, TimerPurpose.SIGNAL_TIMEOUT, null);
            when(timerStore.lockForFiring(eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(Optional.of(scheduledRow)));
            when(timerCallbacks.timerFired(eq(workflowId), eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(TimerFiringResult.STALE_NOOP));

            ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
            when(timerStore.markFailed(eq(timerId), eq(FIXED_NOW), reasonCaptor.capture(), eq(tx)))
                    .thenReturn(Future.succeededFuture(TimerStatusTransition.APPLIED));

            Future<Void> result = executor.execute(payload, ctx);

            assertThat(result.succeeded()).isTrue();
            verify(timerCallbacks).timerFired(workflowId, timerId, tx);
            verify(timerStore).markFailed(eq(timerId), eq(FIXED_NOW), any(), eq(tx));
            assertThat(reasonCaptor.getValue()).contains("TIMER_INCONSISTENCY");
            verify(timerStore, never()).markFired(any(), any(), any());
            verify(taskCallbacks, never()).taskDueFired(any(), any(), any());
        }
    }

    // --- TASK_DUE dispatch ---

    @Nested
    @DisplayName("TASK_DUE timer dispatch")
    class TaskDuePath {

        @Test
        @DisplayName("APPLIED — calls taskDueFired then markFired")
        void execute_taskDue_applied_callsMarkFired() {
            UUID timerId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            TimerFirePayload payload = new TimerFirePayload(timerId, workflowId, null);

            TimerRecord scheduledRow =
                    buildRecord(timerId, workflowId, TimerStatus.SCHEDULED, TimerPurpose.TASK_DUE, taskId);
            when(timerStore.lockForFiring(eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(Optional.of(scheduledRow)));
            when(taskCallbacks.taskDueFired(eq(workflowId), eq(taskId), eq(tx)))
                    .thenReturn(Future.succeededFuture(TaskMutationResult.APPLIED));
            when(timerStore.markFired(eq(timerId), eq(FIXED_NOW), eq(tx)))
                    .thenReturn(Future.succeededFuture(TimerStatusTransition.APPLIED));

            Future<Void> result = executor.execute(payload, ctx);

            assertThat(result.succeeded()).isTrue();
            verify(taskCallbacks).taskDueFired(workflowId, taskId, tx);
            verify(timerStore).markFired(timerId, FIXED_NOW, tx);
            verify(timerStore, never()).markFailed(any(), any(), any(), any());
            verify(timerCallbacks, never()).timerFired(any(), any(), any());
        }

        @Test
        @DisplayName("STALE_NOOP — calls taskDueFired then markFailed with TIMER_INCONSISTENCY")
        void execute_taskDue_staleNoop_callsMarkFailed() {
            UUID timerId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            TimerFirePayload payload = new TimerFirePayload(timerId, workflowId, null);

            TimerRecord scheduledRow =
                    buildRecord(timerId, workflowId, TimerStatus.SCHEDULED, TimerPurpose.TASK_DUE, taskId);
            when(timerStore.lockForFiring(eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(Optional.of(scheduledRow)));
            when(taskCallbacks.taskDueFired(eq(workflowId), eq(taskId), eq(tx)))
                    .thenReturn(Future.succeededFuture(TaskMutationResult.STALE_NOOP));

            ArgumentCaptor<String> reasonCaptor = ArgumentCaptor.forClass(String.class);
            when(timerStore.markFailed(eq(timerId), eq(FIXED_NOW), reasonCaptor.capture(), eq(tx)))
                    .thenReturn(Future.succeededFuture(TimerStatusTransition.APPLIED));

            Future<Void> result = executor.execute(payload, ctx);

            assertThat(result.succeeded()).isTrue();
            verify(taskCallbacks).taskDueFired(workflowId, taskId, tx);
            verify(timerStore).markFailed(eq(timerId), eq(FIXED_NOW), any(), eq(tx));
            assertThat(reasonCaptor.getValue()).contains("TIMER_INCONSISTENCY");
            verify(timerStore, never()).markFired(any(), any(), any());
        }

        @Test
        @DisplayName("LOST_TO_RACE — treated defensively as APPLIED, calls markFired")
        void execute_taskDue_lostToRace_callsMarkFired() {
            UUID timerId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            TimerFirePayload payload = new TimerFirePayload(timerId, workflowId, null);

            TimerRecord scheduledRow =
                    buildRecord(timerId, workflowId, TimerStatus.SCHEDULED, TimerPurpose.TASK_DUE, taskId);
            when(timerStore.lockForFiring(eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(Optional.of(scheduledRow)));
            when(taskCallbacks.taskDueFired(eq(workflowId), eq(taskId), eq(tx)))
                    .thenReturn(Future.succeededFuture(TaskMutationResult.LOST_TO_RACE));
            when(timerStore.markFired(eq(timerId), eq(FIXED_NOW), eq(tx)))
                    .thenReturn(Future.succeededFuture(TimerStatusTransition.APPLIED));

            Future<Void> result = executor.execute(payload, ctx);

            assertThat(result.succeeded()).isTrue();
            verify(taskCallbacks).taskDueFired(workflowId, taskId, tx);
            verify(timerStore).markFired(timerId, FIXED_NOW, tx);
            verify(timerStore, never()).markFailed(any(), any(), any(), any());
        }

        @Test
        @DisplayName("null taskId — fails with IllegalStateException before calling taskDueFired")
        void execute_taskDue_nullTaskId_failsWithIllegalState() {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            TimerFirePayload payload = new TimerFirePayload(timerId, workflowId, null);

            // Build the record bypassing the compact-constructor check by using STANDALONE
            // with a null taskId, then manually create an equivalent with TASK_DUE but null
            // taskId — this tests the null-guard in dispatchTaskDueFired itself.
            // Since TimerRecord's compact constructor would throw for TASK_DUE + null taskId,
            // we reflect-craft the record by extending a mocked TimerRecord.
            // Instead, use a workaround: inject via a custom record that is structurally
            // identical but has purpose=TASK_DUE and taskId=null by using Mockito spy.
            // The simplest correct approach is to verify the null check via the guard in
            // the executor — we test this by verifying that when we bypass TimerRecord's
            // own validation (via a mock), the executor catches it.
            TimerRecord mockRecord = org.mockito.Mockito.mock(TimerRecord.class);
            when(mockRecord.status()).thenReturn(TimerStatus.SCHEDULED);
            when(mockRecord.workflowId()).thenReturn(workflowId);
            when(mockRecord.purpose()).thenReturn(TimerPurpose.TASK_DUE);
            when(mockRecord.taskId()).thenReturn(null);

            when(timerStore.lockForFiring(eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(Optional.of(mockRecord)));

            Future<Void> result = executor.execute(payload, ctx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isInstanceOf(IllegalStateException.class);
            assertThat(result.cause().getMessage()).contains("TASK_DUE").contains("null task_id");
            verify(taskCallbacks, never()).taskDueFired(any(), any(), any());
            verify(timerStore, never()).markFired(any(), any(), any());
            verify(timerStore, never()).markFailed(any(), any(), any(), any());
        }
    }

    // --- TASK_REMINDER dispatch ---

    @Nested
    @DisplayName("TASK_REMINDER timer dispatch")
    class TaskReminderPath {

        @Test
        @DisplayName("succeeded void future — calls taskReminderFired then markFired")
        void execute_taskReminder_success_callsMarkFired() {
            UUID timerId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            TimerFirePayload payload = new TimerFirePayload(timerId, workflowId, null);

            TimerRecord scheduledRow =
                    buildRecord(timerId, workflowId, TimerStatus.SCHEDULED, TimerPurpose.TASK_REMINDER, taskId);
            when(timerStore.lockForFiring(eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(Optional.of(scheduledRow)));
            when(taskCallbacks.taskReminderFired(eq(workflowId), eq(taskId), eq(timerId), any(), eq(tx)))
                    .thenReturn(Future.succeededFuture());
            when(timerStore.markFired(eq(timerId), eq(FIXED_NOW), eq(tx)))
                    .thenReturn(Future.succeededFuture(TimerStatusTransition.APPLIED));

            Future<Void> result = executor.execute(payload, ctx);

            assertThat(result.succeeded()).isTrue();
            verify(taskCallbacks).taskReminderFired(eq(workflowId), eq(taskId), eq(timerId), any(), eq(tx));
            verify(timerStore).markFired(timerId, FIXED_NOW, tx);
            verify(timerStore, never()).markFailed(any(), any(), any(), any());
            verify(timerCallbacks, never()).timerFired(any(), any(), any());
        }

        @Test
        @DisplayName("failed void future — propagates failure without markFired or markFailed")
        void execute_taskReminder_callbackFails_propagatesFailure() {
            UUID timerId = UUID.randomUUID();
            UUID taskId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            TimerFirePayload payload = new TimerFirePayload(timerId, workflowId, null);

            TimerRecord scheduledRow =
                    buildRecord(timerId, workflowId, TimerStatus.SCHEDULED, TimerPurpose.TASK_REMINDER, taskId);
            when(timerStore.lockForFiring(eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(Optional.of(scheduledRow)));
            RuntimeException cause = new RuntimeException("transient DB error");
            when(taskCallbacks.taskReminderFired(eq(workflowId), eq(taskId), eq(timerId), any(), eq(tx)))
                    .thenReturn(Future.failedFuture(cause));

            Future<Void> result = executor.execute(payload, ctx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isSameAs(cause);
            verify(taskCallbacks).taskReminderFired(eq(workflowId), eq(taskId), eq(timerId), any(), eq(tx));
            verify(timerStore, never()).markFired(any(), any(), any());
            verify(timerStore, never()).markFailed(any(), any(), any(), any());
        }

        @Test
        @DisplayName("null taskId — fails with IllegalStateException before calling taskReminderFired")
        void execute_taskReminder_nullTaskId_failsWithIllegalState() {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            TimerFirePayload payload = new TimerFirePayload(timerId, workflowId, null);

            TimerRecord mockRecord = org.mockito.Mockito.mock(TimerRecord.class);
            when(mockRecord.status()).thenReturn(TimerStatus.SCHEDULED);
            when(mockRecord.workflowId()).thenReturn(workflowId);
            when(mockRecord.purpose()).thenReturn(TimerPurpose.TASK_REMINDER);
            when(mockRecord.taskId()).thenReturn(null);

            when(timerStore.lockForFiring(eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(Optional.of(mockRecord)));

            Future<Void> result = executor.execute(payload, ctx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isInstanceOf(IllegalStateException.class);
            assertThat(result.cause().getMessage()).contains("TASK_REMINDER").contains("null task_id");
            verify(taskCallbacks, never()).taskReminderFired(any(), any(), any(), any(), any());
            verify(timerStore, never()).markFired(any(), any(), any());
            verify(timerStore, never()).markFailed(any(), any(), any(), any());
        }
    }

    // --- Engine throws WorkflowConflictException ---

    @Nested
    @DisplayName("engine callback throws WorkflowConflictException")
    class ConflictException {

        @Test
        @DisplayName("execute returns failed Future so the delayed-job infrastructure retries")
        void execute_conflictException_returnsFailed() {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            TimerFirePayload payload = new TimerFirePayload(timerId, workflowId, null);

            TimerRecord scheduledRow =
                    buildRecord(timerId, workflowId, TimerStatus.SCHEDULED, TimerPurpose.STANDALONE, null);
            when(timerStore.lockForFiring(eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(Optional.of(scheduledRow)));
            when(timerCallbacks.timerFired(eq(workflowId), eq(timerId), eq(tx)))
                    .thenReturn(Future.failedFuture(new WorkflowConflictException("concurrent modification")));

            Future<Void> result = executor.execute(payload, ctx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isInstanceOf(WorkflowConflictException.class);
        }
    }

    // --- Payload vs locked-row workflow-id mismatch ---

    @Nested
    @DisplayName("payload workflowId disagrees with locked timer row")
    class WorkflowIdMismatch {

        @Test
        @DisplayName("fails loud with IllegalStateException and makes no engine callback")
        void execute_payloadWorkflowMismatch_failsLoud() {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId rowWorkflowId = new WorkflowInstanceId(UUID.randomUUID());
            WorkflowInstanceId payloadWorkflowId = new WorkflowInstanceId(UUID.randomUUID());
            TimerFirePayload payload = new TimerFirePayload(timerId, payloadWorkflowId, null);

            // Locked timer row binds the timer to a DIFFERENT workflow instance than the payload.
            TimerRecord scheduledRow =
                    buildRecord(timerId, rowWorkflowId, TimerStatus.SCHEDULED, TimerPurpose.STANDALONE, null);
            when(timerStore.lockForFiring(eq(timerId), eq(tx)))
                    .thenReturn(Future.succeededFuture(Optional.of(scheduledRow)));

            Future<Void> result = executor.execute(payload, ctx);

            assertThat(result.failed()).isTrue();
            assertThat(result.cause()).isInstanceOf(IllegalStateException.class);
            assertThat(result.cause().getMessage())
                    .contains(timerId.toString())
                    .contains(rowWorkflowId.value().toString())
                    .contains(payloadWorkflowId.value().toString());
            verify(timerCallbacks, never()).timerFired(any(), any(), any());
            verify(taskCallbacks, never()).taskDueFired(any(), any(), any());
            verify(taskCallbacks, never()).taskReminderFired(any(), any(), any(), any(), any());
            verify(timerStore, never()).markFired(any(), any(), any());
            verify(timerStore, never()).markFailed(any(), any(), any(), any());
        }
    }

    // --- Helpers ---

    /**
     * Builds a minimal {@link TimerRecord} for the given timer, workflow, status, purpose, and
     * optional task id.
     *
     * @param timerId    the timer UUID
     * @param workflowId the workflow instance id
     * @param status     the timer status
     * @param purpose    the timer purpose
     * @param taskId     the task UUID (required for {@link TimerPurpose#TASK_DUE} and
     *                   {@link TimerPurpose#TASK_REMINDER}; must be null for
     *                   {@link TimerPurpose#STANDALONE} and {@link TimerPurpose#SIGNAL_TIMEOUT})
     * @return a constructed timer record
     */
    private static TimerRecord buildRecord(
            UUID timerId, WorkflowInstanceId workflowId, TimerStatus status, TimerPurpose purpose, UUID taskId) {
        return new TimerRecord(
                timerId,
                workflowId,
                "step-1",
                Instant.parse("2026-05-08T11:00:00Z"),
                status,
                UUID.randomUUID(),
                FIXED_NOW,
                null,
                null,
                null,
                null,
                purpose,
                taskId,
                null, // branchTokenId
                null, // forkStepId
                null,
                DurableMetadata.empty()); // branchId
    }
}
