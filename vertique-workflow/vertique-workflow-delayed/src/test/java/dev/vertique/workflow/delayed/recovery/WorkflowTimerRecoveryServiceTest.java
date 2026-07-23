// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.recovery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.context.DurableMetadata;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import dev.vertique.job.delayed.DelayedJobOptions;
import dev.vertique.workflow.delayed.job.TimerFirePayload;
import dev.vertique.workflow.delayed.job.WorkflowTimerFireJob;
import dev.vertique.workflow.ops.TimerFiringResult;
import dev.vertique.workflow.ops.TransactionalTimerCallbacks;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import dev.vertique.workflow.timer.TimerPurpose;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.core.json.JsonObject;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import io.vertx.sqlclient.SqlConnection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link WorkflowTimerRecoveryService#reconcile()}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Empty scan result — no {@link JobRepository#findById} calls.</li>
 *   <li>Orphan (job absent) — {@link WorkflowTimerFireJob#enqueue} and
 *       {@link TimerStore#updateExecutionId} are called.</li>
 *   <li>Job in SUCCEEDED state — {@link TransactionalTimerCallbacks#timerFiringFailed} called
 *       with {@code TIMER_INCONSISTENCY} errorType.</li>
 *   <li>Job in DEAD_LETTER state — {@link TransactionalTimerCallbacks#timerFiringFailed} called
 *       with {@code DELAYED_JOB_DEAD_LETTERED} errorType.</li>
 *   <li>Job in FAILED, ABANDONED, ENQUEUED, or PROCESSING state — no callback (recoverable
 *       in-flight).</li>
 *   <li>One row fails reconciliation — the other rows are still processed.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class WorkflowTimerRecoveryServiceTest {

    @Mock
    private Pool pool;

    @Mock
    private TimerStore<SqlClient> timerStore;

    @Mock
    private JobRepository jobRepository;

    @Mock
    private WorkflowTimerFireJob fireJob;

    @Mock
    private TransactionalTimerCallbacks<SqlClient> timerCallbacks;

    @Mock
    private SqlConnection tx;

    private static final Instant FIXED_NOW = Instant.parse("2026-05-08T10:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
    private static final WorkflowTimerRecoveryConfig CONFIG = WorkflowTimerRecoveryConfig.defaults();

    private WorkflowTimerRecoveryService service;

    @BeforeEach
    void setUp() {
        dev.vertique.context.DefaultContextHolder holder = new dev.vertique.context.DefaultContextHolder();
        dev.vertique.context.DurableContextPropagator propagator = new dev.vertique.context.DurableContextPropagator(
                new dev.vertique.context.DurableContextMetadataRegistry(java.util.Set.of(), java.util.Set.of()),
                holder,
                new dev.vertique.context.ContextScopeBinder(holder));
        dev.vertique.context.InboundExecutionContextScope inboundExecutionContextScope =
                new dev.vertique.context.InboundExecutionContextScope(
                        new dev.vertique.context.InboundDispatchScope(), propagator, java.util.Set.of());
        service = new WorkflowTimerRecoveryService(
                pool,
                timerStore,
                jobRepository,
                fireJob,
                () -> timerCallbacks,
                CONFIG,
                FIXED_CLOCK,
                inboundExecutionContextScope);

        // Stub pool.withTransaction to invoke the lambda with the test connection.
        when(pool.withTransaction(any())).thenAnswer(inv -> {
            var fn = inv.<java.util.function.Function<SqlConnection, Future<Object>>>getArgument(0);
            return fn.apply(tx);
        });
    }

    // --- Empty scan result ---

    @Nested
    @DisplayName("findRecoverableScheduled returns empty list")
    class EmptyScan {

        @Test
        @DisplayName("no findById calls are made")
        void reconcile_emptyResult_noLookups() {
            when(timerStore.findRecoverableScheduled(any(), eq(CONFIG.batchSize()), eq(tx)))
                    .thenReturn(Future.succeededFuture(List.of()));

            Future<Void> result = service.reconcile();

            assertThat(result.succeeded()).isTrue();
            verify(jobRepository, never()).findById(any());
        }
    }

    // --- Orphan: job execution row absent ---

    @Nested
    @DisplayName("job execution row absent (orphan)")
    class OrphanRow {

        @Test
        @DisplayName("re-enqueues the fire job and updates the executionId")
        void reconcile_orphan_reenqueues() {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID oldExecId = UUID.randomUUID();
            UUID newExecId = UUID.randomUUID();
            TimerRecord row = buildRecord(timerId, workflowId, oldExecId);

            when(timerStore.findRecoverableScheduled(any(), eq(CONFIG.batchSize()), eq(tx)))
                    .thenReturn(Future.succeededFuture(List.of(row)));
            when(jobRepository.findById(eq(oldExecId))).thenReturn(Future.succeededFuture(Optional.empty()));
            when(fireJob.enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx)))
                    .thenReturn(Future.succeededFuture(newExecId));
            when(timerStore.updateExecutionId(eq(timerId), eq(newExecId), eq(tx)))
                    .thenReturn(Future.succeededFuture());

            Future<Void> result = service.reconcile();

            assertThat(result.succeeded()).isTrue();
            verify(fireJob).enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx));
            verify(timerStore).updateExecutionId(timerId, newExecId, tx);
            verify(timerCallbacks, never()).timerFiringFailed(any(), any(), any(), any(), any());
        }

        // --- P2.S0 commit 4: workflow-timer carrier binding ---
        // DelayedJobService.enqueuePremerged now rejects premerged metadata carrying the
        // identity-snapshot namespace (F5, PRD identity-002 §14.6/A9). This test formerly pinned
        // the OLD wholesale-premerge-of-metadata behavior (a premergedMetadata blob threaded
        // straight from workflow_timers.metadata into the replacement job); it is retargeted here
        // to the NEW divergent behavior: recovery no longer premerges ANY of the timer row's
        // metadata (identity or otherwise) into the replacement job. Instead it binds the row's
        // metadata on the holder (verified against the reproduced workflow-timer carrier — see
        // WorkflowTimerRecoveryDurableBindTest#orphanReEnqueueVerifiesAgainstReproducedTimerCarrier)
        // and re-enqueues via ordinary enqueue, so DelayedJobService re-captures and re-signs
        // whatever was bound for the replacement job's own execution carrier.
        @Test
        @DisplayName("re-enqueues via ordinary enqueue — non-identity metadata is bound and re-captured, "
                + "not threaded through premergedMetadata")
        void reconcile_orphan_rebindsAndRecapturesMetadata() {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID oldExecId = UUID.randomUUID();
            UUID newExecId = UUID.randomUUID();
            DurableMetadata persistedMetadata = DurableMetadata.of(
                    "test", new JsonObject().put("corr", "c-recovery").put("tenant", "t-r"));
            TimerRecord row = buildRecord(timerId, workflowId, oldExecId, persistedMetadata);

            when(timerStore.findRecoverableScheduled(any(), eq(CONFIG.batchSize()), eq(tx)))
                    .thenReturn(Future.succeededFuture(List.of(row)));
            when(jobRepository.findById(eq(oldExecId))).thenReturn(Future.succeededFuture(Optional.empty()));
            when(fireJob.enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx)))
                    .thenReturn(Future.succeededFuture(newExecId));
            when(timerStore.updateExecutionId(eq(timerId), eq(newExecId), eq(tx)))
                    .thenReturn(Future.succeededFuture());

            Future<Void> result = service.reconcile();

            assertThat(result.succeeded()).isTrue();
            org.mockito.ArgumentCaptor<DelayedJobOptions> captor =
                    org.mockito.ArgumentCaptor.forClass(DelayedJobOptions.class);
            verify(fireJob).enqueue(any(TimerFirePayload.class), captor.capture(), eq(tx));
            assertThat(captor.getValue().premergedMetadata())
                    .as("recovery no longer threads workflow_timers.metadata through "
                            + "DelayedJobOptions.premergedMetadata — it binds row.metadata() on the holder "
                            + "(verified against the workflow-timer carrier) and re-enqueues via ordinary "
                            + "enqueue so DelayedJobService re-captures and re-signs it for the replacement "
                            + "job's own execution carrier")
                    .isNull();
        }

        @Test
        @DisplayName("re-enqueues via ordinary enqueue — premergedMetadata is not set (identity must not be premerged)")
        void reconcile_orphanDoesNotPremergeIdentity() {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID oldExecId = UUID.randomUUID();
            UUID newExecId = UUID.randomUUID();
            DurableMetadata persistedMetadata =
                    DurableMetadata.of("identity-snapshot", new JsonObject().put("subjectId", "u-42"));
            TimerRecord row = buildRecord(timerId, workflowId, oldExecId, persistedMetadata);

            when(timerStore.findRecoverableScheduled(any(), eq(CONFIG.batchSize()), eq(tx)))
                    .thenReturn(Future.succeededFuture(List.of(row)));
            when(jobRepository.findById(eq(oldExecId))).thenReturn(Future.succeededFuture(Optional.empty()));
            when(fireJob.enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx)))
                    .thenReturn(Future.succeededFuture(newExecId));
            when(timerStore.updateExecutionId(eq(timerId), eq(newExecId), eq(tx)))
                    .thenReturn(Future.succeededFuture());

            Future<Void> result = service.reconcile();

            assertThat(result.succeeded()).isTrue();
            org.mockito.ArgumentCaptor<DelayedJobOptions> captor =
                    org.mockito.ArgumentCaptor.forClass(DelayedJobOptions.class);
            verify(fireJob).enqueue(any(TimerFirePayload.class), captor.capture(), eq(tx));
            assertThat(captor.getValue().premergedMetadata())
                    .as("recovery must re-enqueue via ordinary enqueue — a premerged identity blob is "
                            + "unbindable and thus a replay vector (F5, PRD identity-002 §14.6/A9); "
                            + "DelayedJobService must re-sign the identity for the NEW execution's own carrier")
                    .isNull();
        }
    }

    // --- SUCCEEDED job with SCHEDULED timer ---

    @Nested
    @DisplayName("job in SUCCEEDED state")
    class SucceededJob {

        @Test
        @DisplayName("calls timerFiringFailed with TIMER_INCONSISTENCY errorType")
        void reconcile_jobSucceeded_callsFiringFailed() {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID execId = UUID.randomUUID();
            TimerRecord row = buildRecord(timerId, workflowId, execId);

            when(timerStore.findRecoverableScheduled(any(), eq(CONFIG.batchSize()), eq(tx)))
                    .thenReturn(Future.succeededFuture(List.of(row)));
            when(jobRepository.findById(eq(execId)))
                    .thenReturn(Future.succeededFuture(Optional.of(buildJob(execId, JobState.SUCCEEDED))));
            when(timerCallbacks.timerFiringFailed(
                            eq(workflowId), eq(timerId), eq("TIMER_INCONSISTENCY"), any(), eq(tx)))
                    .thenReturn(Future.succeededFuture(TimerFiringResult.APPLIED));

            Future<Void> result = service.reconcile();

            assertThat(result.succeeded()).isTrue();
            verify(timerCallbacks)
                    .timerFiringFailed(eq(workflowId), eq(timerId), eq("TIMER_INCONSISTENCY"), any(), eq(tx));
            verify(fireJob, never()).enqueue(any(), any(), any());
        }
    }

    // --- DEAD_LETTER job ---

    @Nested
    @DisplayName("job in DEAD_LETTER state")
    class DeadLetterJob {

        @Test
        @DisplayName("calls timerFiringFailed with DELAYED_JOB_DEAD_LETTERED errorType")
        void reconcile_jobDeadLettered_callsFiringFailed() {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID execId = UUID.randomUUID();
            TimerRecord row = buildRecord(timerId, workflowId, execId);

            when(timerStore.findRecoverableScheduled(any(), eq(CONFIG.batchSize()), eq(tx)))
                    .thenReturn(Future.succeededFuture(List.of(row)));
            when(jobRepository.findById(eq(execId)))
                    .thenReturn(Future.succeededFuture(Optional.of(buildJob(execId, JobState.DEAD_LETTER))));
            when(timerCallbacks.timerFiringFailed(
                            eq(workflowId), eq(timerId), eq("DELAYED_JOB_DEAD_LETTERED"), any(), eq(tx)))
                    .thenReturn(Future.succeededFuture(TimerFiringResult.APPLIED));

            Future<Void> result = service.reconcile();

            assertThat(result.succeeded()).isTrue();
            verify(timerCallbacks)
                    .timerFiringFailed(eq(workflowId), eq(timerId), eq("DELAYED_JOB_DEAD_LETTERED"), any(), eq(tx));
        }
    }

    // --- Recoverable in-flight states ---

    @Nested
    @DisplayName("job in recoverable in-flight state")
    class RecoverableInFlight {

        @Test
        @DisplayName("FAILED state — no timerFiringFailed callback")
        void reconcile_jobFailed_noCallback() {
            assertNoCallbackForState(JobState.FAILED);
        }

        @Test
        @DisplayName("ABANDONED state — no timerFiringFailed callback")
        void reconcile_jobAbandoned_noCallback() {
            assertNoCallbackForState(JobState.ABANDONED);
        }

        @Test
        @DisplayName("ENQUEUED state — no timerFiringFailed callback")
        void reconcile_jobEnqueued_noCallback() {
            assertNoCallbackForState(JobState.ENQUEUED);
        }

        @Test
        @DisplayName("PROCESSING state — no timerFiringFailed callback")
        void reconcile_jobProcessing_noCallback() {
            assertNoCallbackForState(JobState.PROCESSING);
        }

        private void assertNoCallbackForState(JobState state) {
            UUID timerId = UUID.randomUUID();
            WorkflowInstanceId workflowId = new WorkflowInstanceId(UUID.randomUUID());
            UUID execId = UUID.randomUUID();
            TimerRecord row = buildRecord(timerId, workflowId, execId);

            when(timerStore.findRecoverableScheduled(any(), eq(CONFIG.batchSize()), eq(tx)))
                    .thenReturn(Future.succeededFuture(List.of(row)));
            when(jobRepository.findById(eq(execId)))
                    .thenReturn(Future.succeededFuture(Optional.of(buildJob(execId, state))));

            Future<Void> result = service.reconcile();

            assertThat(result.succeeded()).isTrue();
            verify(timerCallbacks, never()).timerFiringFailed(any(), any(), any(), any(), any());
            verify(fireJob, never()).enqueue(any(), any(), any());
        }
    }

    // --- Per-row isolation ---

    @Nested
    @DisplayName("per-row failure isolation")
    class RowIsolation {

        @Test
        @DisplayName("one failing row does not prevent processing of other rows")
        void reconcile_oneRowFails_otherRowsProcessed() {
            UUID timerId1 = UUID.randomUUID();
            UUID timerId2 = UUID.randomUUID();
            WorkflowInstanceId workflowId1 = new WorkflowInstanceId(UUID.randomUUID());
            WorkflowInstanceId workflowId2 = new WorkflowInstanceId(UUID.randomUUID());
            UUID execId1 = UUID.randomUUID();
            UUID execId2 = UUID.randomUUID();
            UUID newExecId = UUID.randomUUID();

            TimerRecord row1 = buildRecord(timerId1, workflowId1, execId1);
            TimerRecord row2 = buildRecord(timerId2, workflowId2, execId2);

            when(timerStore.findRecoverableScheduled(any(), eq(CONFIG.batchSize()), eq(tx)))
                    .thenReturn(Future.succeededFuture(List.of(row1, row2)));

            // row1: findById returns a failed Future (simulates a DB error)
            when(jobRepository.findById(eq(execId1)))
                    .thenReturn(Future.failedFuture(new RuntimeException("DB error on row1")));

            // row2: orphan — re-enqueue succeeds
            when(jobRepository.findById(eq(execId2))).thenReturn(Future.succeededFuture(Optional.empty()));
            when(fireJob.enqueue(any(TimerFirePayload.class), any(DelayedJobOptions.class), eq(tx)))
                    .thenReturn(Future.succeededFuture(newExecId));
            when(timerStore.updateExecutionId(eq(timerId2), eq(newExecId), eq(tx)))
                    .thenReturn(Future.succeededFuture());

            // Act: reconcile() must succeed even though row1 failed
            Future<Void> result = service.reconcile();

            assertThat(result.succeeded()).isTrue();
            // row2 was still processed
            verify(fireJob, times(1)).enqueue(any(), any(), eq(tx));
            verify(timerStore).updateExecutionId(timerId2, newExecId, tx);
        }
    }

    // --- Helpers ---

    /**
     * Builds a minimal SCHEDULED {@link TimerRecord}.
     *
     * @param timerId    the timer UUID
     * @param workflowId the workflow instance id
     * @param execId     the delayed-job execution id
     * @return a scheduled timer record
     */
    private static TimerRecord buildRecord(UUID timerId, WorkflowInstanceId workflowId, UUID execId) {
        return buildRecord(timerId, workflowId, execId, DurableMetadata.empty());
    }

    private static TimerRecord buildRecord(
            UUID timerId, WorkflowInstanceId workflowId, UUID execId, DurableMetadata metadata) {
        return new TimerRecord(
                timerId,
                workflowId,
                "step-1",
                Instant.parse("2026-05-08T09:00:00Z"),
                TimerStatus.SCHEDULED,
                execId,
                FIXED_NOW,
                null,
                null,
                null,
                null,
                TimerPurpose.STANDALONE,
                null,
                null, // branchTokenId
                null, // forkStepId
                null, // branchId
                metadata);
    }

    /**
     * Builds a minimal {@link JobExecution} stub with the given state.
     *
     * @param execId the execution id
     * @param state  the job state
     * @return a minimal job execution record
     */
    private static JobExecution buildJob(UUID execId, JobState state) {
        return new JobExecution(
                execId,
                "job-" + execId,
                JobType.DELAYED,
                "vertique.workflow.timer.fire",
                "default",
                state,
                0,
                5,
                null,
                0,
                null,
                FIXED_NOW,
                FIXED_NOW,
                null,
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                Map.of(),
                Map.of(),
                DurableMetadata.empty());
    }
}
