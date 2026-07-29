// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobInterceptor;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import dev.vertique.services.ServiceTargetResolver;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Manages timer-based scheduling for all registered {@link CronJobDefinition}s.
 *
 * <p>Each job gets its own Vert.x timer that fires once at the computed next fire time. After
 * firing, the next timer is scheduled immediately, creating a self-rescheduling loop.
 *
 * <p>Concurrency is controlled at two levels:
 *
 * <ul>
 *   <li><b>Per-job overlap:</b> Controlled by each job's {@link OverlapPolicy}. With
 *       {@link OverlapPolicy#SKIP} (default), a job will not fire again if its previous execution
 *       is still in progress. With {@link OverlapPolicy#QUEUE_ONE}, one missed fire is queued and
 *       runs immediately after the current execution completes.
 *   <li><b>Global concurrency:</b> A configurable {@link #maxConcurrentJobs} limits the total
 *       number of concurrently executing jobs across all definitions. When the limit is reached,
 *       dispatches are queued and run as slots become available.
 * </ul>
 *
 * <p>{@link ExecutionMode#EVERY_INSTANCE} jobs fire on every application instance independently.
 * {@link ExecutionMode#SINGLE_INSTANCE} jobs use INSERT ON CONFLICT leader election via a
 * {@link JobRepository} — only one node per cluster executes the job per fire time.
 *
 * <p>When a {@link JobRepository} is provided, tracked jobs persist execution records for
 * dashboard visibility. In-memory-only operation (no repository) is supported for EVERY_INSTANCE
 * jobs with {@code tracked=false}.
 *
 * <p>MDC context is populated in the dispatched {@link dev.vertique.core.eventbus.DispatchEnvelope} (restored
 * by {@link dev.vertique.services.dispatch.ServiceMethodInvoker} on the handler side) and also set
 * on the scheduler side around log-producing blocks for correlated logging.
 *
 * <p>Registered {@link JobInterceptor}s are invoked in ascending {@link JobInterceptor#priority()}
 * order around each dispatch: {@link JobInterceptor#onDispatch} before the event bus send and
 * {@link JobInterceptor#onComplete} after the reply is received.
 *
 * <p><b>Consumer timeout:</b> When {@code executionTimeoutMs} is positive, a local Vert.x timer
 * is set per execution. If the handler does not reply before the timeout fires, the execution is
 * marked {@link JobState#ABANDONED} and the concurrency slot is released. No DB heartbeats are
 * written — the timeout is purely local. Set to {@code 0} to disable.
 *
 * <p><b>Cooperative cancellation:</b> Each dispatched execution registers a consumer on the
 * event-bus address {@code job.cancel.<executionId>}. When a cancel message arrives (e.g. from
 * {@link dev.vertique.job.JobCoordinator#cancelExecution}), the
 * {@link dev.vertique.job.DefaultJobContext#isCancelled()} flag is set. Handlers should
 * periodically check this flag and exit gracefully.
 *
 * <p><b>Progress flush:</b> When {@code progressFlushIntervalMs} is positive and a repository is
 * available, a periodic timer writes {@link ProgressSnapshot} updates to the repository. Only
 * changed snapshots are flushed (change detection avoids redundant DB writes).
 *
 * <p><b>Job log flush:</b> Buffered {@link dev.vertique.job.JobLogger} entries are drained to the
 * repository through a per-execution {@link dev.vertique.job.JobLogFlusher} — on the same periodic
 * tick (there unconditionally, since log entries change independently of the progress snapshot)
 * and on every path that ends the execution: completion, timeout, and {@link #stop()}. The ending
 * sites use {@link dev.vertique.job.JobLogFlusher#drain()}, which awaits an outstanding write and
 * re-flushes while entries remain, because they have just cancelled the tick that would otherwise
 * have retried. Untracked fires never flush: they have no {@code job_executions} row for
 * {@code job_logs.execution_id} to reference.
 *
 * <p><b>Threading model:</b> This class must be used on a single Vert.x event loop context.
 * All timer callbacks, event bus handlers, and slot management operations assume single-threaded
 * execution. Concurrent data structures are used defensively but do not guarantee correctness
 * under multi-threaded access.
 */
@Slf4j
public class CronScheduler {

    /** Default maximum concurrent jobs across all definitions. */
    public static final int DEFAULT_MAX_CONCURRENT_JOBS = 10;

    /**
     * Maximum number of missed fires executed during misfire recovery with
     * {@link MisfirePolicy#FIRE_ALL}. Caps recovery to avoid overwhelming the system after a
     * prolonged outage. With {@link MisfirePolicy#FIRE_NOW}, only the last missed fire is executed
     * regardless of this limit.
     */
    public static final int MAX_MISFIRE_FIRES = 100;

    private final Vertx vertx;
    private final int maxConcurrentJobs;

    /**
     * Optional repository for persistent execution tracking and SINGLE_INSTANCE leader election.
     * May be {@code null} for in-memory-only operation.
     */
    private final JobRepository repository;

    private final String nodeId;
    private final ServiceTargetResolver serviceTargetResolver;
    private final List<CronJobDefinition> jobs = new ArrayList<>();
    private final Map<String, Long> activeTimers = new ConcurrentHashMap<>();

    // --- Helpers ---

    private final CronConcurrencyManager concurrency;
    private final CronJobDispatcher dispatcher;

    /** Misfire recovery helper; {@code null} when no repository is available. */
    private final CronMisfireRecovery misfireRecovery;

    private volatile boolean running;

    /**
     * Creates a new cron scheduler with the default concurrent job limit and no timeout or
     * progress-flush support.
     *
     * @param vertx                 the Vert.x instance for timer management and event bus dispatch
     * @param interceptors          the set of job interceptors to invoke around each dispatch
     * @param repository            optional job repository for persistent execution tracking and
     *                              SINGLE_INSTANCE leader election; may be {@code null} for
     *                              in-memory mode
     * @param serviceTargetResolver resolver for translating stable service target ids to runtime
     *                              event bus addresses at dispatch time
     * @param eventBusClient        the event bus client for dispatch protocol sends
     * @param envelopeBuilder       envelope builder used to construct outgoing job-dispatch
     *                              envelopes through the context substrate (FR-CTX-015); tests
     *                              that do not exercise context propagation pass
     *                              {@link dev.vertique.context.DispatchEnvelopeBuilder#forTesting()}
     */
    public CronScheduler(
            Vertx vertx,
            Set<JobInterceptor> interceptors,
            JobRepository repository,
            ServiceTargetResolver serviceTargetResolver,
            EventBusClient eventBusClient,
            dev.vertique.context.DispatchEnvelopeBuilder envelopeBuilder) {
        this(
                vertx,
                interceptors,
                repository,
                serviceTargetResolver,
                eventBusClient,
                DEFAULT_MAX_CONCURRENT_JOBS,
                0L,
                0L,
                envelopeBuilder);
    }

    /**
     * Full constructor including the {@link dev.vertique.context.DispatchEnvelopeBuilder}
     * used to construct outgoing job-dispatch envelopes through the context substrate (FR-CTX-015).
     *
     * @param vertx                   the Vert.x instance for timer management and event bus dispatch
     * @param interceptors            the set of job interceptors to invoke around each dispatch
     * @param repository              optional job repository for persistent execution tracking and
     *                                SINGLE_INSTANCE leader election; may be {@code null} for
     *                                in-memory mode
     * @param serviceTargetResolver   resolver for translating stable service target ids to runtime
     *                                event bus addresses at dispatch time
     * @param eventBusClient          the event bus client for dispatch protocol sends
     * @param maxConcurrentJobs       the maximum number of jobs that can execute concurrently;
     *                                must be positive
     * @param executionTimeoutMs      per-execution timeout in milliseconds; {@code 0} disables
     * @param progressFlushIntervalMs interval in milliseconds for flushing progress snapshots;
     *                                {@code 0} disables
     * @param envelopeBuilder         envelope builder used to construct outgoing job-dispatch
     *                                envelopes through the context substrate (FR-CTX-015)
     */
    public CronScheduler(
            Vertx vertx,
            Set<JobInterceptor> interceptors,
            JobRepository repository,
            ServiceTargetResolver serviceTargetResolver,
            EventBusClient eventBusClient,
            int maxConcurrentJobs,
            long executionTimeoutMs,
            long progressFlushIntervalMs,
            dev.vertique.context.DispatchEnvelopeBuilder envelopeBuilder) {
        if (maxConcurrentJobs <= 0) {
            throw new IllegalArgumentException("maxConcurrentJobs must be positive, got: " + maxConcurrentJobs);
        }
        java.util.Objects.requireNonNull(envelopeBuilder, "envelopeBuilder must not be null (FR-CTX-015)");
        this.vertx = vertx;
        this.maxConcurrentJobs = maxConcurrentJobs;
        this.repository = repository;
        this.serviceTargetResolver = serviceTargetResolver;
        this.nodeId = resolveNodeId();
        List<JobInterceptor> sortedInterceptors =
                interceptors.stream().sorted(OrderedExtension.comparator()).toList();
        this.concurrency = new CronConcurrencyManager(maxConcurrentJobs);
        this.dispatcher = new CronJobDispatcher(
                vertx,
                eventBusClient,
                repository,
                sortedInterceptors,
                executionTimeoutMs,
                progressFlushIntervalMs,
                nodeId,
                envelopeBuilder);
        this.misfireRecovery = (repository != null) ? new CronMisfireRecovery(repository) : null;
    }

    /**
     * Registers a cron job definition. Must be called before {@link #start()}.
     *
     * <p>Validates that:
     * <ul>
     *   <li>{@link ExecutionMode#SINGLE_INSTANCE} jobs require a repository</li>
     *   <li>{@link ExecutionMode#SINGLE_INSTANCE} jobs require {@link OverlapPolicy#SKIP}</li>
     *   <li>Tracked jobs without a repository log a warning (tracking is best-effort)</li>
     * </ul>
     *
     * <p>Idempotent by {@link CronJobDefinition#id() id}: a second call with an already-registered
     * id is dropped with a {@code WARN}. This protects against the lifecycle verticle calling
     * {@link CronJobRegistrar#scan()} on every deploy and against any legacy app code that still
     * calls {@code scan()}/{@code start()} manually after the auto-deployment landed — without
     * idempotency every job would fire 2× per tick on those upgrade paths.
     *
     * @param job the cron job definition to register
     * @throws IllegalArgumentException if the definition violates scheduling constraints
     */
    public void register(CronJobDefinition job) {
        if (job.mode() == ExecutionMode.SINGLE_INSTANCE && repository == null) {
            throw new IllegalArgumentException(
                    "SINGLE_INSTANCE requires JobRepository for cron job '" + job.id() + "'");
        }
        if (job.mode() == ExecutionMode.SINGLE_INSTANCE && job.overlapPolicy() != OverlapPolicy.SKIP) {
            throw new IllegalArgumentException(
                    "SINGLE_INSTANCE requires SKIP overlap policy for cron job '" + job.id() + "'");
        }
        if (job.tracked() && repository == null) {
            log.warn(
                    "Cron job '{}' has tracked=true but no JobRepository is bound — "
                            + "executions will not be persisted",
                    job.id());
        }
        for (CronJobDefinition existing : jobs) {
            if (existing.id().equals(job.id())) {
                log.warn(
                        "Cron job '{}' is already registered — second register() call dropped"
                                + " (likely a duplicate scan() invocation; the auto-deployed lifecycle"
                                + " verticle already calls scan() on every deploy)",
                        job.id());
                return;
            }
        }
        jobs.add(job);
        log.debug(
                "Registered cron job '{}' with expression '{}' mode={} overlapPolicy={} tracked={}",
                job.id(),
                job.cronExpression().expression(),
                job.mode(),
                job.overlapPolicy(),
                job.tracked());
    }

    /**
     * Starts the scheduler by setting up the initial timer for each registered job and running
     * misfire recovery for eligible jobs.
     *
     * <p>Misfire recovery is fire-and-forget: it loads {@code last_fired_at} from the repository
     * for each {@link ExecutionMode#SINGLE_INSTANCE} job whose {@link MisfirePolicy} is not
     * {@link MisfirePolicy#SKIP}, then fires any missed ticks according to the policy. Failures
     * in misfire recovery are logged as warnings but do not prevent the scheduler from starting —
     * this includes synchronous throws from a misbehaving {@link JobRepository} (e.g. NPE,
     * {@code IllegalStateException} from a closed pool), which would otherwise escape {@code start()}
     * unwrapped and bypass the lifecycle verticle's failed-future rollback.
     *
     * <p>Idempotent: a second {@code start()} with no intervening {@link #stop()} is dropped with
     * a {@code WARN}. Without this guard, the second iteration of {@code jobs} would arm a fresh
     * timer per job and overwrite the previous {@code activeTimers} entry, orphaning the prior
     * timer and doubling every job's fires per tick.
     *
     * @return a future that succeeds immediately after timers are set
     */
    public Future<Void> start() {
        if (running) {
            log.warn("CronScheduler.start() called while already running — dropped (no-op)");
            return Future.succeededFuture();
        }
        running = true;
        for (CronJobDefinition job : jobs) {
            scheduleNext(job);
        }
        if (misfireRecovery != null) {
            try {
                misfireRecovery.recover(jobs, this::fire);
            } catch (Exception e) {
                // Documented contract: misfire recovery failures must not block startup. Async
                // failures already bottom out in onFailure handlers; catch sync throws here so
                // they don't escape start() and bypass the lifecycle verticle's rollback path.
                log.warn("Misfire recovery failed synchronously — skipping (jobs are still scheduled)", e);
            }
        }
        log.info("CronScheduler started with {} job(s), maxConcurrent={}", jobs.size(), maxConcurrentJobs);
        return Future.succeededFuture();
    }

    /**
     * Stops the scheduler by cancelling all active timers, clearing the registered job set,
     * cleaning up per-execution resources, and taking a final cutoff snapshot of each in-flight
     * execution's job logs.
     *
     * <p>Cancels all job schedule timers, drains queued dispatches, unregisters in-flight
     * completion consumers, and cancels any per-execution timeout timers, progress-flush timers,
     * and cancel consumers. Also clears {@code jobs} so a subsequent re-registration cycle
     * (e.g. after the lifecycle verticle is undeployed and redeployed) starts from a clean
     * slate rather than colliding with the previous registrations.
     *
     * <p><b>The shutdown log flush is a cutoff snapshot, not a final flush.</b> In-flight
     * executions are not interrupted, so a handler may keep logging after its buffer is drained;
     * those later entries are lost. The snapshot bounds what a graceful shutdown loses — it does
     * not eliminate loss.
     *
     * @return a future that succeeds once all timers are cancelled and every cutoff log flush has
     *     settled or hit its per-execution timeout bound
     */
    public Future<Void> stop() {
        running = false;
        activeTimers.forEach((id, timerId) -> {
            vertx.cancelTimer(timerId);
            log.debug("Cancelled timer for cron job '{}'", id);
        });
        activeTimers.clear();
        concurrency.drainQueue();
        Future<Void> cutoffFlushes = dispatcher.shutdown();
        concurrency.reset();
        jobs.clear();
        return cutoffFlushes.onComplete(ar -> log.info("CronScheduler stopped"));
    }

    /**
     * Returns the number of registered cron job definitions.
     *
     * @return the registered job count
     */
    public int registeredJobCount() {
        return jobs.size();
    }

    // --- Internal scheduling ---

    /**
     * Schedules the next timer for the given job based on its cron expression.
     *
     * @param job the cron job to schedule
     */
    private void scheduleNext(CronJobDefinition job) {
        if (!running) {
            return;
        }
        Instant now = Instant.now();
        Instant nextFire;
        try {
            nextFire = job.cronExpression().computeNextFireTime(now, job.timezone());
        } catch (Exception e) {
            log.error("Failed to compute next fire time for cron job '{}' — job will not be rescheduled", job.id(), e);
            return;
        }

        long delayMs = Math.max(1L, Duration.between(now, nextFire).toMillis());
        log.debug("Scheduling cron job '{}' to fire in {}ms (at {})", job.id(), delayMs, nextFire);

        final Instant scheduledAt = nextFire;
        long timerId = vertx.setTimer(delayMs, id -> {
            if (running) {
                scheduleNext(job);
                try {
                    fire(job, scheduledAt);
                } catch (Exception e) {
                    log.error("Cron job '{}' fire() threw unexpected exception", job.id(), e);
                }
            }
        });
        activeTimers.put(job.id(), timerId);
    }

    /**
     * Dispatches the job according to its execution mode.
     *
     * @param job         the cron job to fire
     * @param scheduledAt the time this execution was scheduled for
     */
    private void fire(CronJobDefinition job, Instant scheduledAt) {
        if (job.mode() == ExecutionMode.SINGLE_INSTANCE) {
            fireSingleInstance(job, scheduledAt);
        } else {
            fireEveryInstance(job, scheduledAt);
        }
    }

    // --- SINGLE_INSTANCE: leader election via INSERT ON CONFLICT ---

    /**
     * Handles firing for {@link ExecutionMode#SINGLE_INSTANCE} jobs. Uses INSERT ON CONFLICT
     * leader election so that only one node per cluster executes the job per fire time.
     *
     * <p>The winner (the node whose INSERT succeeds) adds the job to the in-flight set, acquires
     * a concurrency slot, and dispatches. Losers (INSERT conflicted) log a debug message and
     * return without dispatching.
     *
     * <p>The effective event bus address is resolved <em>after</em> the in-flight guard is taken
     * and <em>before</em> any row is written, so an unresolvable target never persists an
     * execution record. See {@link #resolveEffectiveAddress(CronJobDefinition)}.
     *
     * @param job         the cron job to fire
     * @param scheduledAt the time this execution was scheduled for
     */
    private void fireSingleInstance(CronJobDefinition job, Instant scheduledAt) {
        // Local in-flight guard — prevent overlapping executions on this node
        if (!concurrency.tryAcquireInFlight(job.id())) {
            log.debug("SINGLE_INSTANCE cron '{}' skipped — already in-flight on this node", job.id());
            return;
        }
        String effectiveAddress = resolveEffectiveAddress(job);
        if (effectiveAddress == null) {
            // Release what we took; no slot was acquired and no row has been written yet.
            concurrency.removeInFlight(job.id());
            return;
        }
        JobExecution execution = buildExecution(job, scheduledAt, JobState.PROCESSING, effectiveAddress);
        repository
                .tryInsert(execution)
                .onSuccess(optId -> {
                    if (optId.isPresent()) {
                        log.debug("SINGLE_INSTANCE cron '{}' won at {}", job.id(), scheduledAt);
                        concurrency.acquireSlotAndRun(
                                job.id(),
                                () -> dispatcher.dispatch(
                                        job, scheduledAt, execution, effectiveAddress, this::markCompleted));
                    } else {
                        log.debug("SINGLE_INSTANCE cron '{}' skipped — another node won", job.id());
                        concurrency.removeInFlight(job.id());
                    }
                })
                .onFailure(err -> {
                    concurrency.removeInFlight(job.id());
                    log.warn("SINGLE_INSTANCE insert failed for '{}': {}", job.id(), err.getMessage());
                });
    }

    // --- EVERY_INSTANCE: local in-flight guard + overlap policy ---

    /**
     * Handles firing for {@link ExecutionMode#EVERY_INSTANCE} jobs. Checks the local in-flight
     * set; if the job is already running, delegates to the configured {@link OverlapPolicy}.
     * If {@code tracked=true} and a repository is available, persists the execution before
     * dispatching. On persistence failure, dispatches anyway (tracking is best-effort).
     *
     * <p>Overlap admission is decided <em>first</em>: the effective event bus address is resolved
     * only after {@code tryAcquireInFlight} succeeds, so a resolver failure can never convert an
     * {@link OverlapPolicy#QUEUE_ONE} overlap into a silently dropped tick. See
     * {@link #resolveEffectiveAddress(CronJobDefinition)}.
     *
     * @param job         the cron job to fire
     * @param scheduledAt the time this execution was scheduled for
     */
    private void fireEveryInstance(CronJobDefinition job, Instant scheduledAt) {
        if (!concurrency.tryAcquireInFlight(job.id())) {
            concurrency.handleOverlap(job, scheduledAt);
            return;
        }
        String effectiveAddress = resolveEffectiveAddress(job);
        if (effectiveAddress == null) {
            // Release what we took; no slot was acquired and no row has been written yet.
            concurrency.removeInFlight(job.id());
            return;
        }
        if (job.tracked() && repository != null) {
            JobExecution execution = buildExecution(job, scheduledAt, JobState.PROCESSING, effectiveAddress);
            repository
                    .save(execution)
                    .onSuccess(id -> concurrency.acquireSlotAndRun(
                            job.id(),
                            () -> dispatcher.dispatch(
                                    job, scheduledAt, execution, effectiveAddress, this::markCompleted)))
                    .onFailure(err -> {
                        log.warn(
                                "Failed to persist execution for '{}' — dispatching in-memory: {}",
                                job.id(),
                                err.getMessage());
                        concurrency.acquireSlotAndRun(
                                job.id(),
                                () -> dispatcher.dispatch(
                                        job, scheduledAt, null, effectiveAddress, this::markCompleted));
                    });
        } else {
            concurrency.acquireSlotAndRun(
                    job.id(), () -> dispatcher.dispatch(job, scheduledAt, null, effectiveAddress, this::markCompleted));
        }
    }

    // --- Completion handling ---

    /**
     * Marks a job execution as completed: checks for a pending QUEUE_ONE fire and re-dispatches
     * it (reusing the concurrency slot) or releases the slot and drains the waiting queue.
     *
     * <p>For tracked QUEUE_ONE re-dispatches, a fresh execution record is saved before dispatching
     * the queued fire.
     *
     * <p>A queued fire is a <em>new</em> fire, so it resolves its own effective event bus address
     * — this path bypasses {@link #fire(CronJobDefinition, Instant)} entirely and therefore needs
     * the resolution gate independently. When resolution fails, <b>both</b> guards are released:
     * {@link CronConcurrencyManager#markCompleted(String)} deliberately retains the in-flight
     * entry when a pending fire exists, and the concurrency slot is still held for reuse.
     *
     * @param job the cron job that completed
     */
    private void markCompleted(CronJobDefinition job) {
        // Check for a pending QUEUE_ONE fire BEFORE releasing the slot or removing from
        // inFlightJobs — this avoids a decrement→drain→increment race and prevents a brief
        // window where the job appears not in-flight (which could allow a concurrent fire).
        Instant pending = concurrency.markCompleted(job.id());
        if (pending != null && running) {
            // Slot is reused — dispatch directly rather than going through fireEveryInstance()
            // which would double-count the slot.
            log.debug("Cron job '{}' running queued fire (scheduled={})", job.id(), pending);
            String effectiveAddress = resolveEffectiveAddress(job);
            if (effectiveAddress == null) {
                concurrency.removeInFlight(job.id());
                concurrency.releaseSlot();
                return;
            }
            if (job.tracked() && repository != null) {
                JobExecution queuedExecution = buildExecution(job, pending, JobState.PROCESSING, effectiveAddress);
                repository
                        .save(queuedExecution)
                        .onSuccess(id -> dispatcher.dispatch(
                                job, pending, queuedExecution, effectiveAddress, this::markCompleted))
                        .onFailure(err -> {
                            log.warn(
                                    "Failed to persist queued execution for '{}' — dispatching in-memory: {}",
                                    job.id(),
                                    err.getMessage());
                            dispatcher.dispatch(job, pending, null, effectiveAddress, this::markCompleted);
                        });
            } else {
                dispatcher.dispatch(job, pending, null, effectiveAddress, this::markCompleted);
            }
        } else {
            concurrency.releaseSlot();
        }
    }

    // --- Helper methods ---

    /**
     * Resolves the effective event bus address for one fire of {@code job}.
     *
     * <p>For a {@link CronTargetReference.ServiceTarget} the stable target id is resolved through
     * {@link ServiceTargetResolver}; for a {@link CronTargetReference.EventBusTarget} the stored
     * {@link CronJobDefinition#handlerAddress()} is used. Both variants are then subject to the
     * same non-blank check, so no caller can produce an execution record with a {@code null} or
     * blank {@link JobExecution#handler()}.
     *
     * <p>Returns {@code null} — after logging at {@code ERROR} with the job id and the target's
     * canonical form — when the resolver throws or the effective address is null/blank, so the
     * caller can abandon the fire and release whatever guard it already holds. The failure is
     * never rethrown: a resolution failure skips that fire only, and the next tick retries.
     *
     * <p>Resolving per fire rather than immediately before the event-bus send is sound only
     * because the built-in {@code DefaultServiceTargetResolver} snapshots its indexes with
     * {@code Map.copyOf} at construction. "Per fire" therefore means <em>fixed at fire
     * admission</em>. A mutable or reloadable {@link ServiceTargetResolver} implementation would
     * invalidate that assumption; see ADR-0201.
     *
     * @param job the cron job whose target is being resolved
     * @return the non-blank effective event bus address, or {@code null} when the target cannot be
     *         resolved to one
     */
    private String resolveEffectiveAddress(CronJobDefinition job) {
        // Never dereference job.target() unguarded, including in the log statements below. This
        // method's contract is "return null, never throw": a throw would escape into
        // fireSingleInstance/fireEveryInstance *after* they acquired the in-flight guard, leaking
        // it permanently — the exact failure mode this gate exists to prevent. CronJobDefinition
        // does not validate that target is non-null, so the guard cannot assume it.
        CronTargetReference target = job.target();
        String targetDescription = target != null ? target.toCanonical() : "<none>";
        String address;
        if (target instanceof CronTargetReference.ServiceTarget serviceTarget) {
            try {
                address = serviceTargetResolver
                        .resolve(serviceTarget.stableTargetId())
                        .address();
            } catch (Exception e) {
                log.error(
                        "Cron job '{}' target '{}' could not be resolved — skipping this fire",
                        job.id(),
                        targetDescription,
                        e);
                return null;
            }
        } else {
            address = job.handlerAddress();
        }
        if (address == null || address.isBlank()) {
            log.error(
                    "Cron job '{}' target '{}' resolved to a null or blank event bus address — skipping this fire",
                    job.id(),
                    targetDescription);
            return null;
        }
        return address;
    }

    /**
     * Builds a {@link JobExecution} record for a cron fire. The execution ID is freshly
     * generated; the record captures the current instant as {@code startedAt} and uses the job
     * definition's parameters.
     *
     * @param job              the cron job being executed
     * @param scheduledAt      the scheduled fire time
     * @param state            the initial execution state (typically {@link JobState#PROCESSING})
     * @param effectiveAddress the non-blank event bus address this fire dispatches to, as returned
     *                         by {@link #resolveEffectiveAddress(CronJobDefinition)}; recorded as
     *                         the execution's {@code handler} so the persisted address always
     *                         equals the dispatched one
     * @return the constructed execution record
     */
    private JobExecution buildExecution(
            CronJobDefinition job, Instant scheduledAt, JobState state, String effectiveAddress) {
        UUID executionId = UUID.randomUUID();
        return new JobExecution(
                executionId,
                job.id(),
                JobType.CRON,
                effectiveAddress,
                "cron",
                state,
                0,
                job.maxAttempts(),
                null,
                0,
                nodeId,
                scheduledAt,
                null,
                Instant.now(),
                null,
                null,
                null,
                ProgressSnapshot.EMPTY,
                job.parameters(),
                Map.of(),
                dev.vertique.core.context.DurableMetadata
                        .empty()); // cron executions carry no durable propagation metadata (out of scope)
    }

    /**
     * Generates a stable node identifier from hostname and a short random suffix.
     *
     * @return the node identifier
     */
    private static String resolveNodeId() {
        String hostname;
        try {
            hostname = java.net.InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            hostname = "unknown";
        }
        return hostname + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
