// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.job;

import dev.vertique.job.JobContext;
import dev.vertique.job.delayed.DelayedJobExecutor;
import dev.vertique.job.delayed.DelayedJobOptions;
import dev.vertique.workflow.exception.WorkflowVersionNotRunnableException;
import dev.vertique.workflow.ops.TaskMutationResult;
import dev.vertique.workflow.ops.TimerFiringResult;
import dev.vertique.workflow.ops.TransactionalTaskCallbacks;
import dev.vertique.workflow.ops.TransactionalTimerCallbacks;
import dev.vertique.workflow.registry.store.DefinitionKey;
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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 * <p><strong>Work this node cannot run:</strong> when a callback fails with
 * {@link WorkflowVersionNotRunnableException} (this node's deployed code lacks the pinned definition version), the
 * failure is recovered inside the same transaction: a fresh {@link WorkflowTimerFireJob} is enqueued for the same timer,
 * {@code workflow_timers.delayed_job_execution_id} points at it, and this job succeeds. The timer row stays
 * {@code SCHEDULED}, {@code markFired} and {@code markFailed} are not called, and the job's attempt limit is not
 * consumed, so a node that has the code picks the timer up later. The new job runs after the time the timer has been
 * due, within {@value #REQUEUE_MIN_DELAY_SECONDS} seconds and 5 minutes, so the interval doubles on every re-queue
 * until it reaches the cap. A re-queue logs a warning at most once per definition key per minute on this node; the
 * other re-queues log at debug. Any other failure still propagates and retries the job.
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

    private static final Logger log = LoggerFactory.getLogger(WorkflowTimerFireExecutor.class);

    /** The shortest delay of a re-queued job, in seconds. */
    static final long REQUEUE_MIN_DELAY_SECONDS = 5;

    private static final Duration NOT_RUNNABLE_REQUEUE_MIN_DELAY = Duration.ofSeconds(REQUEUE_MIN_DELAY_SECONDS);

    /** The longest delay of a re-queued job. */
    private static final Duration NOT_RUNNABLE_REQUEUE_MAX_DELAY = Duration.ofMinutes(5);

    /** The shortest interval between two warnings about one definition key. */
    private static final Duration WARNING_INTERVAL = Duration.ofSeconds(60);

    private final Pool pool;
    private final TimerStore<SqlClient> timerStore;
    private final Provider<TransactionalTimerCallbacks<SqlClient>> timerCallbacksProvider;
    private final Provider<TransactionalTaskCallbacks<SqlClient>> taskCallbacksProvider;
    private final Provider<WorkflowTimerFireJob> fireJobProvider;
    private final Clock clock;
    private final RequeueWarnings warnings = new RequeueWarnings();

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
     *     ← TransactionalTimerCallbacks / TransactionalTaskCallbacks (= WorkflowEngine)
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
     * @param fireJobProvider          lazy provider of the timer-fire job client, used to queue a timer
     *                                 again when this node cannot run it; deferred for the same reason
     *                                 as the callback providers
     * @param clock                    source of current time for {@code firedAt}/{@code failedAt}
     *                                 timestamps and for the re-queue delay
     */
    @Inject
    public WorkflowTimerFireExecutor(
            Pool pool,
            TimerStore<SqlClient> timerStore,
            Provider<TransactionalTimerCallbacks<SqlClient>> timerCallbacksProvider,
            Provider<TransactionalTaskCallbacks<SqlClient>> taskCallbacksProvider,
            Provider<WorkflowTimerFireJob> fireJobProvider,
            Clock clock) {
        this.pool = pool;
        this.timerStore = timerStore;
        this.timerCallbacksProvider = timerCallbacksProvider;
        this.taskCallbacksProvider = taskCallbacksProvider;
        this.fireJobProvider = fireJobProvider;
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
        return requeueIfNotRunnable(
                record,
                tx,
                timerCallbacksProvider
                        .get()
                        .timerFired(record.workflowId(), timerId, tx)
                        .compose(result -> switch (result) {
                            case APPLIED ->
                                timerStore
                                        .markFired(timerId, clock.instant(), tx)
                                        .<Void>mapEmpty();
                            case STALE_NOOP ->
                                timerStore
                                        .markFailed(
                                                timerId,
                                                clock.instant(),
                                                "TIMER_INCONSISTENCY: workflow not waiting for this timer",
                                                tx)
                                        .<Void>mapEmpty();
                        }));
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
        return requeueIfNotRunnable(
                record,
                tx,
                taskCallbacksProvider
                        .get()
                        .taskDueFired(record.workflowId(), taskId, tx)
                        .compose(result -> switch (result) {
                            case APPLIED, LOST_TO_RACE ->
                                timerStore
                                        .markFired(timerId, clock.instant(), tx)
                                        .<Void>mapEmpty();
                            case STALE_NOOP ->
                                timerStore
                                        .markFailed(
                                                timerId,
                                                clock.instant(),
                                                "TIMER_INCONSISTENCY: task no longer expecting due-date timer",
                                                tx)
                                        .<Void>mapEmpty();
                        }));
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
        return requeueIfNotRunnable(
                record,
                tx,
                taskCallbacksProvider
                        .get()
                        // Pass the firing timer's persisted fire_at as the anchor for the next recurring
                        // reminder so RecurringInterval reminders maintain a steady cadence across
                        // executor delays (next fire = scheduledFireAt + interval, not now + interval).
                        .taskReminderFired(record.workflowId(), taskId, timerId, record.fireAt(), tx)
                        .compose(__ -> timerStore
                                .markFired(timerId, clock.instant(), tx)
                                .<Void>mapEmpty()));
    }

    /**
     * Recovers a dispatch whose callback failed because this node cannot run the pinned definition version, and
     * passes every other outcome through. The recovery runs in the caller's transaction: it enqueues a fresh job for
     * the timer, points the timer row at it and succeeds, so the timer stays {@code SCHEDULED} and the attempt is not
     * spent. Only a failure whose cause is the not-runnable exception itself is recovered.
     *
     * @param record the locked timer record
     * @param tx the active transaction context
     * @param dispatch the callback and status update of one purpose
     * @return the dispatch outcome, or a succeeded future after the timer was queued again
     */
    private Future<Void> requeueIfNotRunnable(TimerRecord record, SqlClient tx, Future<Void> dispatch) {
        return dispatch.recover(failure -> failure instanceof WorkflowVersionNotRunnableException notRunnable
                ? requeue(record, notRunnable.definitionKey(), tx)
                : Future.failedFuture(failure));
    }

    private Future<Void> requeue(TimerRecord record, DefinitionKey definitionKey, SqlClient tx) {
        Instant now = clock.instant();
        Duration overdue = Duration.between(record.fireAt(), now);
        Duration delay = overdue.compareTo(NOT_RUNNABLE_REQUEUE_MIN_DELAY) < 0
                ? NOT_RUNNABLE_REQUEUE_MIN_DELAY
                : overdue.compareTo(NOT_RUNNABLE_REQUEUE_MAX_DELAY) > 0 ? NOT_RUNNABLE_REQUEUE_MAX_DELAY : overdue;
        if (warnings.shouldWarn(definitionKey, now)) {
            log.warn(
                    "Timer {} ({}) of definition {} could not run on this node because its code is not deployed here;"
                            + " queued again in {}",
                    record.timerId(),
                    record.purpose(),
                    describe(definitionKey),
                    delay);
        } else {
            log.debug(
                    "Timer {} ({}) of definition {} could not run on this node because its code is not deployed here;"
                            + " queued again in {}",
                    record.timerId(),
                    record.purpose(),
                    describe(definitionKey),
                    delay);
        }
        return fireJobProvider
                .get()
                .enqueue(
                        new TimerFirePayload(record.timerId(), record.workflowId(), null),
                        DelayedJobOptions.builder().runAt(now.plus(delay)).build(),
                        tx)
                .compose(newExecutionId -> timerStore.updateExecutionId(record.timerId(), newExecutionId, tx));
    }

    private static String describe(DefinitionKey key) {
        return key.scopeKey() + "/" + key.definitionId() + "@v" + key.definitionVersion();
    }

    /**
     * Remembers on this node which definition keys were warned about in the last minute, so that a timer no node can
     * run does not flood the log. Entries older than the interval are dropped whenever the limiter is used, which
     * bounds its size by the keys that are currently stuck.
     */
    private static final class RequeueWarnings {

        private final Map<DefinitionKey, Instant> warnedAt = new HashMap<>();

        /**
         * Whether a warning for the key is due at {@code now}; when it is, the key is remembered as warned.
         *
         * @param key the definition key of the timer queued again
         * @param now the current time of the executor's clock
         * @return {@code true} when no warning for the key was logged within the last minute
         */
        synchronized boolean shouldWarn(DefinitionKey key, Instant now) {
            warnedAt.values().removeIf(at -> Duration.between(at, now).compareTo(WARNING_INTERVAL) > 0);
            return warnedAt.putIfAbsent(key, now) == null;
        }
    }
}
