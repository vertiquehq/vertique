// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.job.JobContext;
import dev.vertique.job.delayed.DelayedJobOptions;
import dev.vertique.workflow.exception.WorkflowConflictException;
import dev.vertique.workflow.exception.WorkflowVersionNotRunnableException;
import dev.vertique.workflow.exception.WorkflowVersionPinUnavailableException;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TimerFiringResult;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import dev.vertique.workflow.ops.TransactionalTimerCallbacks;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.registry.store.DefinitionKey;
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
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;

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
    private WorkflowTimerFireJob fireJob;

    @Mock
    private SqlConnection tx;

    @Mock
    private JobContext ctx;

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);

    private WorkflowTimerFireExecutor executor;

    @BeforeEach
    void setUp() {
        executor = executorWith(FIXED_CLOCK);
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

    // --- A pin this node cannot run: the timer is re-queued, never failed ---

    @ParameterizedTest(name = "{0}")
    @EnumSource(TimerPurpose.class)
    @DisplayName("a pin this node cannot run re-queues a fresh job for every purpose and marks nothing")
    void notRunnablePinRequeuesFreshJobForEveryPurpose(TimerPurpose purpose) {
        // Given a scheduled timer, due now, whose callback fails because this node lacks the pinned code
        DefinitionKey key = new DefinitionKey("default", "requeue-" + purpose, 3);
        UUID taskId =
                purpose == TimerPurpose.TASK_DUE || purpose == TimerPurpose.TASK_REMINDER ? UUID.randomUUID() : null;
        Scheduled timer = scheduled(purpose, taskId, FIXED_NOW);
        failCallbackNotRunnable(timer, key);
        UUID newExecutionId = UUID.randomUUID();
        when(fireJob.enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx)))
                .thenReturn(Future.succeededFuture(newExecutionId));
        when(timerStore.updateExecutionId(timer.timerId(), newExecutionId, tx)).thenReturn(Future.succeededFuture());

        try (RequeueLog log = new RequeueLog()) {
            // When the executor runs the timer
            Future<Void> result = executor.execute(timer.payload(), ctx);

            // Then the run succeeds, so the attempt is not spent
            assertThat(result.succeeded()).as("run outcome: %s", result.cause()).isTrue();

            // And a fresh job for the same timer is enqueued at the minimum delay
            ArgumentCaptor<TimerFirePayload> payload = ArgumentCaptor.forClass(TimerFirePayload.class);
            ArgumentCaptor<DelayedJobOptions> options = ArgumentCaptor.forClass(DelayedJobOptions.class);
            verify(fireJob).enqueue(payload.capture(), options.capture(), eq(tx));
            assertThat(payload.getValue()).isEqualTo(timer.payload());
            assertThat(options.getValue().runAt()).isEqualTo(FIXED_NOW.plusSeconds(5));

            // And the timer row points at the new execution and is neither fired nor failed
            verify(timerStore).updateExecutionId(timer.timerId(), newExecutionId, tx);
            verify(timerStore, never()).markFired(any(), any(), any());
            verify(timerStore, never()).markFailed(any(), any(), any(), any());

            // And one warning names the timer, the purpose and the definition key
            assertThat(log.warnings()).singleElement().satisfies(text -> assertThat(text)
                    .contains(timer.timerId().toString(), purpose.name(), key.definitionId()));
        }
    }

    @Test
    @DisplayName("a pin that is only unavailable, not a node-local gap, still fails the attempt")
    void unavailablePinOfAnotherKindStillFailsTheAttempt() {
        // Given a scheduled timer whose callback fails with the base pin-unavailable error
        Scheduled timer = scheduled(TimerPurpose.STANDALONE, null, FIXED_NOW);
        when(timerCallbacks.timerFired(timer.workflowId(), timer.timerId(), tx))
                .thenReturn(Future.failedFuture(
                        new WorkflowVersionPinUnavailableException("default", "pin-gone", 2, timer.workflowId())));

        // When the executor runs the timer
        Future<Void> result = executor.execute(timer.payload(), ctx);

        // Then the failure propagates and nothing is enqueued or marked
        assertThat(result.failed()).isTrue();
        assertThat(result.cause()).isInstanceOf(WorkflowVersionPinUnavailableException.class);
        verify(fireJob, never()).enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), any());
        verify(timerStore, never()).markFired(any(), any(), any());
        verify(timerStore, never()).markFailed(any(), any(), any(), any());
    }

    /** One run of the backoff table: how long the timer has been due, and the delay the re-queue must use. */
    private record BackoffRow(Duration elapsedSinceDue, Duration expectedDelay) {}

    @Test
    @DisplayName("the re-queue delay is capped exponential backoff and the warning is limited per definition key")
    void requeueDelayIsCappedBackoffAndWarningIsRateLimitedPerDefinitionKey() {
        DefinitionKey k1 = new DefinitionKey("default", "requeue-limit-k1", 2);
        DefinitionKey k2 = new DefinitionKey("default", "requeue-limit-k2", 2);
        MutableClock clock = new MutableClock(FIXED_NOW);
        WorkflowTimerFireExecutor timed = executorWith(clock);
        when(fireJob.enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx)))
                .thenAnswer(inv -> Future.succeededFuture(UUID.randomUUID()));
        when(timerStore.updateExecutionId(any(), any(), eq(tx))).thenReturn(Future.succeededFuture());
        List<BackoffRow> table = List.of(
                new BackoffRow(Duration.ZERO, Duration.ofSeconds(5)),
                new BackoffRow(Duration.ofSeconds(3), Duration.ofSeconds(5)),
                new BackoffRow(Duration.ofSeconds(10), Duration.ofSeconds(10)),
                new BackoffRow(Duration.ofSeconds(100), Duration.ofSeconds(100)),
                new BackoffRow(Duration.ofMinutes(20), Duration.ofMinutes(5)));

        try (RequeueLog log = new RequeueLog()) {
            // Given timers of one definition key that have been due for 0 s, 3 s, 10 s, 100 s and 20 minutes
            // When each is run at the same instant
            List<Instant> runAt = new java.util.ArrayList<>();
            for (BackoffRow row : table) {
                Scheduled timer =
                        scheduled(TimerPurpose.STANDALONE, null, clock.instant().minus(row.elapsedSinceDue()));
                failCallbackNotRunnable(timer, k1);
                assertThat(timed.execute(timer.payload(), ctx).succeeded()).isTrue();
                runAt.add(lastEnqueuedRunAt());
            }

            // Then the delays are the elapsed time, at least 5 s and at most 5 minutes
            assertThat(runAt)
                    .containsExactly(table.stream()
                            .map(row -> clock.instant().plus(row.expectedDelay()))
                            .toArray(Instant[]::new));
            verify(timerStore, never()).markFired(any(), any(), any());
            verify(timerStore, never()).markFailed(any(), any(), any(), any());

            // And only the first of those runs warned about the key, the others being logged below warning level
            assertThat(log.warningsMentioning(k1)).hasSize(1);
            assertThat(log.eventsMentioning(k1)).hasSizeGreaterThanOrEqualTo(table.size());

            // When another key is re-queued in the same minute
            runNotRunnable(timed, clock, k2);

            // Then it has a warning of its own
            assertThat(log.warningsMentioning(k2)).hasSize(1);
            assertThat(log.warningsMentioning(k1)).hasSize(1);

            // When the first key is re-queued 30 s after its warning
            clock.advance(Duration.ofSeconds(30));
            runNotRunnable(timed, clock, k1);

            // Then no further warning is logged for it
            assertThat(log.warningsMentioning(k1)).hasSize(1);

            // When it is re-queued 61 s after its warning
            clock.advance(Duration.ofSeconds(31));
            runNotRunnable(timed, clock, k1);

            // Then a second warning is logged for it
            assertThat(log.warningsMentioning(k1)).hasSize(2);
            assertThat(log.warningsMentioning(k2)).hasSize(1);
        }
    }

    // --- Helpers ---

    private WorkflowTimerFireExecutor executorWith(Clock clock) {
        return new WorkflowTimerFireExecutor(
                pool, timerStore, () -> timerCallbacks, () -> taskCallbacks, () -> fireJob, clock);
    }

    /** A scheduled timer row that the store hands out under lock, with the payload that delivers it. */
    private record Scheduled(
            UUID timerId, WorkflowInstanceId workflowId, TimerRecord record, TimerFirePayload payload) {}

    private Scheduled scheduled(TimerPurpose purpose, UUID taskId, Instant dueAt) {
        UUID timerId = UUID.randomUUID();
        WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
        TimerRecord record = buildRecord(timerId, workflowId, TimerStatus.SCHEDULED, purpose, taskId, dueAt);
        when(timerStore.lockForFiring(eq(timerId), eq(tx))).thenReturn(Future.succeededFuture(Optional.of(record)));
        return new Scheduled(timerId, workflowId, record, new TimerFirePayload(timerId, workflowId, null));
    }

    private void failCallbackNotRunnable(Scheduled timer, DefinitionKey key) {
        TimerRecord record = timer.record();
        Future<TimerFiringResult> timerFailure = Future.failedFuture(new WorkflowVersionNotRunnableException(key));
        Future<TaskMutationResult> taskFailure = Future.failedFuture(new WorkflowVersionNotRunnableException(key));
        Future<Void> reminderFailure = Future.failedFuture(new WorkflowVersionNotRunnableException(key));
        switch (record.purpose()) {
            case STANDALONE, SIGNAL_TIMEOUT ->
                when(timerCallbacks.timerFired(timer.workflowId(), timer.timerId(), tx))
                        .thenReturn(timerFailure);
            case TASK_DUE ->
                when(taskCallbacks.taskDueFired(timer.workflowId(), record.taskId(), tx))
                        .thenReturn(taskFailure);
            case TASK_REMINDER ->
                when(taskCallbacks.taskReminderFired(
                                timer.workflowId(), record.taskId(), timer.timerId(), record.fireAt(), tx))
                        .thenReturn(reminderFailure);
        }
    }

    private void runNotRunnable(WorkflowTimerFireExecutor timed, MutableClock clock, DefinitionKey key) {
        Scheduled timer = scheduled(TimerPurpose.STANDALONE, null, clock.instant());
        failCallbackNotRunnable(timer, key);
        assertThat(timed.execute(timer.payload(), ctx).succeeded()).isTrue();
    }

    /** The run time of the most recently enqueued job. */
    private Instant lastEnqueuedRunAt() {
        ArgumentCaptor<DelayedJobOptions> options = ArgumentCaptor.forClass(DelayedJobOptions.class);
        verify(fireJob, org.mockito.Mockito.atLeastOnce())
                .enqueue(any(TimerFirePayload.class), options.capture(), eq(tx));
        List<DelayedJobOptions> all = options.getAllValues();
        return all.get(all.size() - 1).runAt();
    }

    /** A clock the test moves by hand. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /** Collects what the executor logs while it is open, at every level. */
    private static final class RequeueLog implements AutoCloseable {

        private final ch.qos.logback.classic.Logger logger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WorkflowTimerFireExecutor.class.getName());
        private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
        private final Level previousLevel = logger.getLevel();

        private RequeueLog() {
            appender.start();
            logger.setLevel(Level.DEBUG);
            logger.addAppender(appender);
        }

        List<String> warnings() {
            return appender.list.stream()
                    .filter(event -> event.getLevel().isGreaterOrEqual(Level.WARN))
                    .map(ILoggingEvent::getFormattedMessage)
                    .toList();
        }

        List<String> warningsMentioning(DefinitionKey key) {
            return warnings().stream()
                    .filter(text -> text.contains(key.definitionId()))
                    .toList();
        }

        List<String> eventsMentioning(DefinitionKey key) {
            return appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(text -> text.contains(key.definitionId()))
                    .toList();
        }

        @Override
        public void close() {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

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
        return buildRecord(timerId, workflowId, status, purpose, taskId, Instant.parse("2026-05-08T11:00:00Z"));
    }

    private static TimerRecord buildRecord(
            UUID timerId,
            WorkflowInstanceId workflowId,
            TimerStatus status,
            TimerPurpose purpose,
            UUID taskId,
            Instant fireAt) {
        return new TimerRecord(
                timerId,
                workflowId,
                "step-1",
                fireAt,
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
