// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.delayed.recovery;

import dev.vertique.context.InboundExecutionContextScope;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableMetadata;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobRepository;
import dev.vertique.job.delayed.DelayedJobOptions;
import dev.vertique.workflow.delayed.WorkflowTimerCarriers;
import dev.vertique.workflow.delayed.job.TimerFirePayload;
import dev.vertique.workflow.delayed.job.WorkflowTimerFireJob;
import dev.vertique.workflow.ops.TransactionalTimerCallbacks;
import dev.vertique.workflow.timer.TimerRecord;
import dev.vertique.workflow.timer.TimerStore;
import io.vertx.core.Future;
import io.vertx.sqlclient.Pool;
import io.vertx.sqlclient.SqlClient;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orphan and dead-letter recovery for workflow timers. One {@link #reconcile()} pass scans
 * SCHEDULED timer rows whose {@code fire_at} is older than the configured grace period and
 * reconciles each row against the corresponding delayed-job execution:
 *
 * <ol>
 *   <li><strong>Orphan recovery</strong> — a timer row is {@code SCHEDULED} but its
 *       {@code delayed_job_execution_id} no longer exists in the jobs table (e.g., the job was
 *       deleted, or the JVM crashed before the commit landed). The row's own
 *       {@link WorkflowTimerCarriers workflow-timer carrier} is reproduced from
 *       {@code timerId}/{@code workflowId} and used to verify-and-bind {@code row.metadata()}
 *       before the timer's delayed job is re-enqueued via <em>ordinary</em> enqueue (P2.S0 commit
 *       4, F5 row binding) — the replacement job independently captures and signs its own
 *       execution carrier's durable context, rather than inheriting a premerged blob signed for
 *       the workflow-timer carrier. The execution ID column is updated atomically.</li>
 *   <li><strong>Dead-letter / inconsistency recovery</strong> — the delayed job exists but is in
 *       a terminal state that was not handled cleanly:
 *       <ul>
 *         <li>{@link dev.vertique.job.JobState#DEAD_LETTER} — the job exhausted all retry
 *             attempts; the timer is failed via
 *             {@link TransactionalTimerCallbacks#timerFiringFailed}.</li>
 *         <li>{@link dev.vertique.job.JobState#SUCCEEDED} — the job succeeded but the timer row
 *             was not transitioned; treated as a consistency error and failed via
 *             {@link TransactionalTimerCallbacks#timerFiringFailed}.</li>
 *         <li>{@link dev.vertique.job.JobState#CANCELLED} — same as SUCCEEDED; treated as a
 *             consistency error.</li>
 *         <li>{@link dev.vertique.job.JobState#ENQUEUED}, {@link dev.vertique.job.JobState#PROCESSING},
 *             {@link dev.vertique.job.JobState#FAILED},
 *             {@link dev.vertique.job.JobState#ABANDONED} — recoverable in-flight; the standard
 *             delayed-job polling or coordinator will handle them.</li>
 *       </ul>
 *   </li>
 * </ol>
 *
 * <p>Per-row failures are isolated: a failure reconciling one timer is logged and does not prevent
 * the remaining timers in the batch from being processed.
 *
 * <p>Scheduling is owned by {@link WorkflowTimerRecoveryServiceImpl} via {@code @CronJob} with
 * {@code SINGLE_INSTANCE} mode and {@code SKIP} overlap policy; this class only owns the
 * reconcile logic and is unaware of how it is invoked.
 */
@Singleton
public final class WorkflowTimerRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowTimerRecoveryService.class);

    private final Pool pool;
    private final TimerStore<SqlClient> timerStore;
    private final JobRepository jobRepository;
    private final WorkflowTimerFireJob fireJob;
    private final Provider<TransactionalTimerCallbacks<SqlClient>> timerCallbacksProvider;
    private final WorkflowTimerRecoveryConfig config;
    private final Clock clock;
    private final InboundExecutionContextScope inboundExecutionContextScope;

    /**
     * Creates a new timer recovery service.
     *
     * <p>{@code timerCallbacksProvider} is injected as a {@link Provider} rather than a direct
     * dependency to break the Dagger construction-time cycle through
     * {@link dev.vertique.workflow.delayed.job.WorkflowTimerFireExecutor}. The callbacks are
     * resolved at reconciliation time, not at service construction time.
     *
     * @param pool                          the connection pool for opening transactions
     * @param timerStore                    SPI for reading scheduled timer rows
     * @param jobRepository                 repository for looking up the state of delayed job
     *                                      executions
     * @param fireJob                       typed delayed job client for re-enqueueing orphaned
     *                                      timers
     * @param timerCallbacksProvider        lazy provider for the internal timer-callbacks SPI
     * @param config                        batch size and grace period configuration
     * @param clock                         source of the current time for computing the recovery
     *                                      cutoff
     * @param inboundExecutionContextScope  substrate lifecycle helper used to rebind the timer
     *                                      row's metadata for terminal {@code timerFiringFailed}
     *                                      paths whose {@code WORKFLOW_FAILED} event is captured
     *                                      into the outbox via ambient durable context. Going
     *                                      through {@code installDurable} (rather than the raw
     *                                      {@code DurableContextPropagator.bindFrom}) also runs
     *                                      registered {@code InboundContextInitializer}s
     *                                      (including the correlation seeder), so the recovery
     *                                      paths get a {@code CorrelationContext} even when the
     *                                      timer row's metadata carries none.
     */
    @Inject
    public WorkflowTimerRecoveryService(
            Pool pool,
            TimerStore<SqlClient> timerStore,
            JobRepository jobRepository,
            WorkflowTimerFireJob fireJob,
            Provider<TransactionalTimerCallbacks<SqlClient>> timerCallbacksProvider,
            WorkflowTimerRecoveryConfig config,
            Clock clock,
            InboundExecutionContextScope inboundExecutionContextScope) {
        this.pool = pool;
        this.timerStore = timerStore;
        this.jobRepository = jobRepository;
        this.fireJob = fireJob;
        this.timerCallbacksProvider = timerCallbacksProvider;
        this.config = config;
        this.clock = clock;
        this.inboundExecutionContextScope = inboundExecutionContextScope;
    }

    /**
     * Executes one scan cycle: finds overdue scheduled timers and reconciles each one.
     *
     * <p>Per-row failures are isolated via {@code recover()} so a single problematic timer does
     * not block processing of the remaining rows in the batch. Full-scan failures (transaction
     * open, {@code findRecoverableScheduled}) still propagate as a failed future so the cron
     * dispatcher records the execution as failed.
     *
     * @return a {@link Future} that completes when the scan cycle is done; may fail on full-scan
     *         errors
     */
    public Future<Void> reconcile() {
        return pool.withTransaction(tx -> {
                    var cutoff = clock.instant().minus(config.gracePeriod());
                    return timerStore.findRecoverableScheduled(cutoff, config.batchSize(), tx);
                })
                .compose(rows -> {
                    // Process each row sequentially, isolating per-row failures.
                    Future<Void> chain = Future.succeededFuture();
                    for (TimerRecord row : rows) {
                        chain = chain.compose(v -> reconcileOne(row).recover(err -> {
                            log.warn(
                                    "WorkflowTimerRecovery: failed to reconcile timer {} for workflow {}",
                                    row.timerId(),
                                    row.workflowId(),
                                    err);
                            return Future.succeededFuture();
                        }));
                    }
                    return chain;
                });
    }

    /**
     * Reconciles a single overdue timer row by inspecting the state of its delayed job.
     *
     * @param row the scheduled timer row to reconcile
     * @return a {@link Future} that completes when reconciliation is done
     */
    private Future<Void> reconcileOne(TimerRecord row) {
        return jobRepository.findById(row.delayedJobExecutionId()).compose(opt -> {
            if (opt.isEmpty()) {
                // Orphan: the job execution row is gone — re-enqueue the timer job.
                return reenqueueOrphan(row);
            }
            JobExecution job = opt.get();
            return switch (job.state()) {
                // Recoverable in-flight states — let the job infrastructure handle them.
                case ENQUEUED, PROCESSING, FAILED, ABANDONED -> Future.succeededFuture();
                // Dead-letter: the job exhausted all retry attempts.
                case DEAD_LETTER ->
                    withTimerDurableBound(row, () -> pool.withTransaction(tx -> timerCallbacksProvider
                                    .get()
                                    .timerFiringFailed(
                                            row.workflowId(),
                                            row.timerId(),
                                            "DELAYED_JOB_DEAD_LETTERED",
                                            "Timer firing exhausted retry attempts",
                                            tx))
                            .<Void>mapEmpty());
                // SUCCEEDED with timer still SCHEDULED — consistency error.
                case SUCCEEDED ->
                    withTimerDurableBound(row, () -> pool.withTransaction(tx -> timerCallbacksProvider
                                    .get()
                                    .timerFiringFailed(
                                            row.workflowId(),
                                            row.timerId(),
                                            "TIMER_INCONSISTENCY",
                                            "Job SUCCEEDED but workflow_timers.status was SCHEDULED",
                                            tx))
                            .<Void>mapEmpty());
                // CANCELLED externally with timer still SCHEDULED — consistency error.
                case CANCELLED ->
                    withTimerDurableBound(row, () -> pool.withTransaction(tx -> timerCallbacksProvider
                                    .get()
                                    .timerFiringFailed(
                                            row.workflowId(),
                                            row.timerId(),
                                            "TIMER_INCONSISTENCY",
                                            "Job CANCELLED externally but workflow_timers.status was SCHEDULED",
                                            tx))
                            .<Void>mapEmpty());
            };
        });
    }

    /**
     * Binds the timer row's persisted durable metadata (under {@link DispatchBoundary#DELAYED_JOB})
     * for the lifetime of the given action and closes the scope on {@code Future.eventually(...)}.
     *
     * <p>Terminal recovery paths (DEAD_LETTER, SUCCEEDED, CANCELLED) call
     * {@link TransactionalTimerCallbacks#timerFiringFailed}, which emits {@code WORKFLOW_FAILED}
     * through the engine's outbox-event recorder. The recorder captures ambient durable context via
     * {@code mergeCaptured(...)} — without this rebind the cron sweep's empty ambient context
     * (rather than the timer's original context) would be persisted on the {@code workflow_events}
     * outbox row, breaking durable context propagation for downstream consumers.
     *
     * <p>Goes through {@link InboundExecutionContextScope#installDurable(DurableMetadata, String,
     * DurableCarrierDescriptor)} (not the raw {@code DurableContextPropagator.bindFrom}, and not the
     * boundary-only 2-arg {@code installDurable} overload) so registered
     * {@code InboundContextInitializer}s — notably the correlation seeder — run after the durable
     * bind and the recovery path gets a {@code CorrelationContext} bound even when the timer row's
     * metadata carries none. The row's own {@link WorkflowTimerCarriers workflow-timer carrier} is
     * reproduced from {@code timerId}/{@code workflowId} and threaded into the bind so the registered
     * identity decoder can verify {@code row.metadata()} against the exact carrier it was signed for
     * — the same carrier {@link #reenqueueOrphan(TimerRecord)} reproduces for the orphan path. Binding
     * through the boundary-only overload here would mismatch the signed carrier and force the
     * decoder to bind an unverifiable snapshot, losing the original identity on the terminal
     * {@code WORKFLOW_FAILED} emission (F2, PRD identity-002 §14.6/A9).
     *
     * @param row    the timer row whose persisted metadata should be rebound
     * @param action the action to run while the metadata is bound on the holder
     * @return a future that completes when the action completes and the scope has been closed
     */
    private Future<Void> withTimerDurableBound(TimerRecord row, Supplier<Future<Void>> action) {
        DurableMetadata meta = row.metadata() == null ? DurableMetadata.empty() : row.metadata();
        DurableCarrierDescriptor timerCarrier = WorkflowTimerCarriers.of(row.timerId(), row.workflowId());
        ContextHolder.Scope scope;
        try {
            scope = inboundExecutionContextScope.installDurable(meta, DispatchBoundary.DELAYED_JOB, timerCarrier);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        Future<Void> result;
        try {
            result = action.get();
        } catch (RuntimeException e) {
            scope.close();
            return Future.failedFuture(e);
        }
        return result.eventually(() -> {
            scope.close();
            return Future.succeededFuture();
        });
    }

    /**
     * Re-enqueues an orphaned timer's delayed job: the timer row is {@code SCHEDULED} but its
     * {@code delayed_job_execution_id} no longer resolves to a job row.
     *
     * <p>Reproduces the row's own {@link WorkflowTimerCarriers workflow-timer carrier} from its
     * {@code timerId}/{@code workflowId} columns and binds {@code row.metadata()} against it via
     * the carrier-aware {@link InboundExecutionContextScope#installDurable(DurableMetadata, String,
     * DurableCarrierDescriptor)} — the registered identity decoder verifies the persisted envelope
     * was signed for this exact carrier before binding a verified {@code IdentitySnapshotContext}
     * (or leaves it unbound if verification fails; this class never inspects identity or policy
     * itself, see F5 row binding, PRD identity-002 §14.6/A9).
     *
     * <p>The replacement job is then enqueued via <em>ordinary</em> enqueue — no
     * {@code premergedMetadata} — <em>inside</em> that bound scope, so
     * {@code DelayedJobService.enqueue}'s {@code mergeCaptured} re-captures whatever was bound
     * (a verified identity snapshot re-signed for the new execution's own carrier, and any other
     * durable namespace decoded from {@code row.metadata()}) and the identity encoder re-signs it
     * for the replacement row. A verified snapshot is carried forward with its immutable
     * {@code capturedAt}; an unverifiable one binds no subject, leaving the replacement row
     * subjectless so the existing fire-time degradation gate applies policy at execution.
     *
     * @param row the orphaned timer row to re-enqueue
     * @return a future that completes once the replacement job is enqueued and
     *         {@code workflow_timers.delayed_job_execution_id} is updated, and the bound scope has
     *         been closed
     */
    private Future<Void> reenqueueOrphan(TimerRecord row) {
        DurableCarrierDescriptor timerCarrier = WorkflowTimerCarriers.of(row.timerId(), row.workflowId());
        DurableMetadata meta = row.metadata() == null ? DurableMetadata.empty() : row.metadata();
        ContextHolder.Scope scope;
        try {
            scope = inboundExecutionContextScope.installDurable(meta, DispatchBoundary.DELAYED_JOB, timerCarrier);
        } catch (RuntimeException e) {
            return Future.failedFuture(e);
        }
        Future<Void> result;
        try {
            result = pool.withTransaction(tx -> fireJob.enqueue(
                            new TimerFirePayload(row.timerId(), row.workflowId(), null),
                            DelayedJobOptions.builder().runAt(row.fireAt()).build(),
                            tx)
                    .compose(newId -> timerStore.updateExecutionId(row.timerId(), newId, tx)));
        } catch (RuntimeException e) {
            scope.close();
            return Future.failedFuture(e);
        }
        return result.eventually(() -> {
            scope.close();
            return Future.succeededFuture();
        });
    }
}
