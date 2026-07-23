// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.job;

import dev.vertique.job.JobContext;
import dev.vertique.job.delayed.DelayedJobExecutor;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TimerFiringResult;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import dev.vertique.workflow.ops.TransactionalTimerCallbacks;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStatus;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.UUID;

/**
 * Server-side executor for {@link WorkflowTimerFireJob} delayed jobs.
 *
 * <p>Processes a timer-fire job by:
 * <ol>
 *   <li>Opening a database transaction.</li>
 *   <li>Locking the {@code workflow_timers} row for the given timer via
 *       {@link TimerStore#lockForFiring(UUID, Object)}.</li>
 *   <li>If the row is absent (e.g., CASCADE-deleted alongside a cancelled workflow instance) or
 *       is already in a non-{@link TimerStatus#SCHEDULED} state, returning successfully without
 *       any engine callback (idempotent delivery).</li>
 *   <li>If the row is {@code SCHEDULED}, dispatching to the appropriate callback based on
 *       {@link dev.vertique.workflow.timer.TimerPurpose}:
 *       <ul>
 *         <li>{@code STANDALONE} / {@code SIGNAL_TIMEOUT} → {@link TransactionalTimerCallbacks#timerFired}
 *             ({@link TimerFiringResult#APPLIED} → {@code markFired};
 *             {@link TimerFiringResult#STALE_NOOP} → {@code markFailed} with
 *             {@code TIMER_INCONSISTENCY} reason).</li>
 *         <li>{@code TASK_DUE} → {@link TransactionalTaskCallbacks#taskDueFired}
 *             ({@link TaskMutationResult#APPLIED}/{@link TaskMutationResult#LOST_TO_RACE} →
 *             {@code markFired}; {@link TaskMutationResult#STALE_NOOP} → {@code markFailed}
 *             with {@code TIMER_INCONSISTENCY} reason).</li>
 *         <li>{@code TASK_REMINDER} → {@link TransactionalTaskCallbacks#taskReminderFired}
 *             (succeeded future → {@code markFired} unconditionally; failed future →
 *             propagated to trigger delayed-job retry). There is no {@code STALE_NOOP} path
 *             for reminders — a closed task is a normal outcome, not a timer inconsistency.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>All work is done inside a single database transaction. If the transaction fails, the
 * delayed-job infrastructure will retry the job according to
 * {@link WorkflowTimerFireJob}'s {@code maxAttempts} setting.
 *
 * <p><strong>Lock-order invariant:</strong> {@code lockForFiring} acquires the
 * {@code workflow_timers} row first; subsequent task and instance reads or locks happen inside
 * the callbacks per the cycle-3 lock order
 * ({@code workflow_timers → workflow_tasks → workflow_instances}).
 *
 * <p><strong>Timer terminal-state ownership:</strong> this executor is the <em>sole</em> owner of
 * timer terminal status transitions ({@code markFired}/{@code markFailed}) for ALL purposes.
 * Callbacks ({@link TransactionalTimerCallbacks} and {@link TransactionalTaskCallbacks}) must
 * NEVER call {@code markFired}, {@code markFailed}, or {@code lockForFiring} directly.
 */
@Singleton
public final class WorkflowTimerFireExecutor implements DelayedJobExecutor<TimerFirePayload, WorkflowTimerFireJob> {

    private final Pool pool;
    private final TimerStore<SqlClient> timerStore;
    private final Provider<TransactionalTimerCallbacks<SqlClient>> timerCallbacksProvider;
    private final Provider<TransactionalTaskCallbacks<SqlClient>> taskCallbacksProvider;
    private final Clock clock;

    /**
     * Creates a new timer-fire executor.
     *
     * <p>Both callback providers are injected as {@link Provider} rather than direct dependencies
     * to break a Dagger dependency cycle that arises when {@link dev.vertique.workflow.delayed.di.WorkflowDelayedModule}
     * is combined with {@link dev.vertique.job.delayed.dagger.DelayedJobModule}:
     * <pre>
     *   ServiceContractRegistry
     *     ← DelayedJobContractContributor(@DelayedJobs Set&lt;Object&gt;)
     *     ← WorkflowTimerFireExecutor
     *     ← TransactionalTimerCallbacks / TransactionalTaskCallbacks (= PgWorkflowEngine)
     *     ← RecorderRouter(@WorkflowRecorders)
     *     ← OutboxSideEffectRecorder
     *     ← ServiceTargetResolver
     *     ← ServiceContractRegistry  ← CYCLE
     * </pre>
     * Using {@link Provider} defers the resolution of both callback SPIs to first use (at
     * job-execution time), not at Dagger graph construction time, breaking the cycle.
     *
     * @param pool                     the connection pool for opening transactions
     * @param timerStore               SPI for reading and updating timer rows
     * @param timerCallbacksProvider   lazy provider for the timer-callbacks SPI; handles
     *                                 {@code STANDALONE} and {@code SIGNAL_TIMEOUT} timer
     *                                 purposes; resolved at job-execution time
     * @param taskCallbacksProvider    lazy provider for the task-callbacks SPI; handles
     *                                 {@code TASK_DUE} and {@code TASK_REMINDER} timer
     *                                 purposes; resolved at job-execution time
     * @param clock                    source of current time for {@code firedAt}/{@code failedAt}
     *                                 timestamps
     */
    @Inject
    public WorkflowTimerFireExecutor(
            Pool pool,
            TimerStore<SqlClient> timerStore,
            Provider<TransactionalTimerCallbacks<SqlClient>> timerCallbacksProvider,
            Provider<TransactionalTaskCallbacks<SqlClient>> taskCallbacksProvider,
            Clock clock) {
        this.pool = pool;
        this.timerStore = timerStore;
        this.timerCallbacksProvider = timerCallbacksProvider;
        this.taskCallbacksProvider = taskCallbacksProvider;
        this.clock = clock;
    }

    /**
     * Executes the timer-fire job for the given payload.
     *
     * <p>Opens a transaction, locks the timer row, and dispatches to the appropriate engine
     * callback based on the locked row's {@link dev.vertique.workflow.timer.TimerPurpose}. Returns
     * a succeeded {@link Future} when the job is complete (including idempotent no-ops); returns a
     * failed {@link Future} to trigger a retry when a transient error occurs.
     *
     * @param payload the deserialized timer-fire payload carrying the timer and workflow IDs
     * @param ctx     the job execution context (used for logging by the job infrastructure)
     * @return a {@link Future} that completes when the firing attempt is done
     */
    @Override
    public Future<Void> execute(TimerFirePayload payload, JobContext ctx) {
        return pool.withTransaction(
                tx -> timerStore.lockForFiring(payload.timerId(), tx).compose(opt -> {
                    if (opt.isEmpty()) {
                        // Timer row is gone (e.g., CASCADE-deleted with the workflow instance).
                        return Future.succeededFuture();
                    }
                    TimerRecord record = opt.get();
                    if (record.status() != TimerStatus.SCHEDULED) {
                        // Timer is already in a terminal status — duplicate or stale delivery.
                        return Future.succeededFuture();
                    }
                    // The locked timer row is the transactional source of truth for the
                    // workflow-id binding. Trust the row, not the delivered payload — the
                    // payload could be stale or reflect a remap if a future cycle ever
                    // re-uses delayed-job rows. If they disagree, fail loud rather than
                    // silently advance the wrong workflow.
                    if (!record.workflowId().equals(payload.workflowId())) {
                        return Future.failedFuture(
                                new IllegalStateException("Timer-fire payload workflowId " + payload.workflowId()
                                        + " disagrees with locked workflow_timers row "
                                        + record.workflowId() + " for timer " + payload.timerId()));
                    }
                    return switch (record.purpose()) {
                        case STANDALONE, SIGNAL_TIMEOUT -> dispatchTimerFired(record, payload.timerId(), tx);
                        case TASK_DUE -> dispatchTaskDueFired(record, payload.timerId(), tx);
                        case TASK_REMINDER -> dispatchTaskReminderFired(record, payload.timerId(), tx);
                    };
                }));
    }

    // --- Purpose dispatch helpers ---

    /**
     * Dispatches a {@code STANDALONE} or {@code SIGNAL_TIMEOUT} timer fire through the
     * {@link TransactionalTimerCallbacks} SPI and updates the timer row accordingly.
     *
     * <p>{@link TimerFiringResult#APPLIED} → {@code markFired};
     * {@link TimerFiringResult#STALE_NOOP} → {@code markFailed} with
     * {@code TIMER_INCONSISTENCY} reason.
     *
     * @param record   the locked timer record
     * @param timerId  the timer UUID (pre-extracted for convenience)
     * @param tx       the active transaction context
     * @return a {@link Future} completing when the callback and status update are done
     */
    private Future<Void> dispatchTimerFired(TimerRecord record, UUID timerId, SqlClient tx) {
        return timerCallbacksProvider
                .get()
                .timerFired(record.workflowId(), timerId, tx)
                .compose(result -> switch (result) {
                    case APPLIED ->
                        timerStore.markFired(timerId, clock.instant(), tx).<Void>mapEmpty();
                    case STALE_NOOP ->
                        timerStore
                                .markFailed(
                                        timerId,
                                        clock.instant(),
                                        "TIMER_INCONSISTENCY: workflow not waiting for this timer",
                                        tx)
                                .<Void>mapEmpty();
                });
    }

    /**
     * Dispatches a {@code TASK_DUE} timer fire through the {@link TransactionalTaskCallbacks} SPI
     * and updates the timer row accordingly.
     *
     * <p>{@link TaskMutationResult#APPLIED} or {@link TaskMutationResult#LOST_TO_RACE} →
     * {@code markFired} (both indicate the work was done);
     * {@link TaskMutationResult#STALE_NOOP} → {@code markFailed} with
     * {@code TIMER_INCONSISTENCY} reason.
     *
     * <p>Per the cycle-3 SPI contract, {@link TaskMutationResult#LOST_TO_RACE} is not expected
     * from {@code taskDueFired}; it is handled defensively as {@code markFired} to preserve
     * idempotency if the contract ever relaxes.
     *
     * @param record   the locked timer record (must have non-null {@code taskId})
     * @param timerId  the timer UUID (pre-extracted for convenience)
     * @param tx       the active transaction context
     * @return a {@link Future} completing when the callback and status update are done
     * @throws IllegalStateException if the timer row has a null {@code taskId}
     */
    private Future<Void> dispatchTaskDueFired(TimerRecord record, UUID timerId, SqlClient tx) {
        UUID taskId = record.taskId();
        if (taskId == null) {
            return Future.failedFuture(new IllegalStateException(
                    "TASK_DUE timer " + timerId + " has null task_id; CHECK constraint violated"));
        }
        return taskCallbacksProvider
                .get()
                .taskDueFired(record.workflowId(), taskId, tx)
                .compose(result -> switch (result) {
                    case APPLIED, LOST_TO_RACE ->
                        timerStore.markFired(timerId, clock.instant(), tx).<Void>mapEmpty();
                    case STALE_NOOP ->
                        timerStore
                                .markFailed(
                                        timerId,
                                        clock.instant(),
                                        "TIMER_INCONSISTENCY: task no longer expecting due-date timer",
                                        tx)
                                .<Void>mapEmpty();
                });
    }

    /**
     * Dispatches a {@code TASK_REMINDER} timer fire through the
     * {@link TransactionalTaskCallbacks} SPI and unconditionally marks the timer as fired on
     * success.
     *
     * <p>There is no {@code STALE_NOOP} path for reminders — a closed task is a normal outcome.
     * A succeeded future always leads to {@code markFired}; a failed future propagates to trigger
     * delayed-job retry.
     *
     * @param record   the locked timer record (must have non-null {@code taskId})
     * @param timerId  the timer UUID (pre-extracted for convenience)
     * @param tx       the active transaction context
     * @return a {@link Future} completing when the callback and status update are done
     * @throws IllegalStateException if the timer row has a null {@code taskId}
     */
    private Future<Void> dispatchTaskReminderFired(TimerRecord record, UUID timerId, SqlClient tx) {
        UUID taskId = record.taskId();
        if (taskId == null) {
            return Future.failedFuture(new IllegalStateException(
                    "TASK_REMINDER timer " + timerId + " has null task_id; CHECK constraint violated"));
        }
        return taskCallbacksProvider
                .get()
                // Pass the firing timer's persisted fire_at as the anchor for the next recurring
                // reminder so RecurringInterval reminders maintain a steady cadence across
                // executor delays (next fire = scheduledFireAt + interval, not now + interval).
                .taskReminderFired(record.workflowId(), taskId, timerId, record.fireAt(), tx)
                .compose(
                        __ -> timerStore.markFired(timerId, clock.instant(), tx).<Void>mapEmpty());
    }
}
