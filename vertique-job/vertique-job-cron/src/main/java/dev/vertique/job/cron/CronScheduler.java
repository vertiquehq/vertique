// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import dev.vertique.core.context.DeferredExecutionOrigin;
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

    /**
     * Job ids that have already logged a target-resolution failure at {@code ERROR}; cleared on the
     * job's next successful resolution.
     *
     * <p>Exists to bound log volume. A resolution failure is usually <em>permanent</em>, not
     * transient — the built-in resolver snapshots its index at construction, so a target id that
     * misses at startup misses for the process lifetime — and the scheduler deliberately retries
     * every tick. Without this, one mistyped target on a one-second cron writes an ERROR plus a
     * stack trace every second, indefinitely, on every node: enough to fill a log volume and to
     * bury genuine security events in the same stream. First failure per job logs at {@code ERROR}
     * with the cause; subsequent ones drop to {@code DEBUG} until the target resolves again.
     */
    private final Set<String> resolutionFailureLogged = ConcurrentHashMap.newKeySet();

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
     *                              event bus addresses, once per admitted fire
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
     *                                event bus addresses, once per admitted fire
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
        // Clear alongside `jobs` for the same reason: a redeploy of the lifecycle verticle
        // re-registers from scratch, and a retained entry would demote the first resolution failure
        // of the new cycle to DEBUG, losing the ERROR an operator needs to see.
        resolutionFailureLogged.clear();
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
        JobExecution execution = buildExecution(job, scheduledAt, effectiveAddress);
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
                    // Log the throwable, not just its message: the repository wraps the driver
                    // failure, so the message alone names the statement but not the constraint or
                    // SQL state that actually rejected it — which is the only thing that tells an
                    // operator whether the leader election is failing on data or on the schema.
                    log.warn("SINGLE_INSTANCE insert failed for '{}'", forLog(job.id()), err);
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
     * only after {@code tryAcquireInFlight} succeeds, so a resolver failure at <em>admission</em>
     * cannot convert an {@link OverlapPolicy#QUEUE_ONE} overlap into a silently dropped tick — the
     * overlapping tick is queued without the resolver being consulted at all. It is <em>not</em> an
     * unqualified guarantee: if resolution later fails when the queued fire is taken in
     * {@link #markCompleted(CronJobDefinition)}, {@code pendingFires} has already been drained and
     * that one queued tick is discarded. See {@link #resolveEffectiveAddress(CronJobDefinition)}.
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
        // Dispatch with no execution record — used both when tracking is off and when persisting
        // the record failed (tracking is best-effort; a dispatch still happens).
        Runnable dispatchUntracked =
                () -> dispatcher.dispatch(job, scheduledAt, null, effectiveAddress, this::markCompleted);
        if (job.tracked() && repository != null) {
            JobExecution execution = buildExecution(job, scheduledAt, effectiveAddress);
            repository
                    .save(execution)
                    .onSuccess(id -> concurrency.acquireSlotAndRun(
                            job.id(),
                            () -> dispatcher.dispatch(
                                    job, scheduledAt, execution, effectiveAddress, this::markCompleted)))
                    .onFailure(err -> {
                        log.warn("Failed to persist execution for '{}' — dispatching in-memory", forLog(job.id()), err);
                        concurrency.acquireSlotAndRun(job.id(), dispatchUntracked);
                    });
        } else {
            concurrency.acquireSlotAndRun(job.id(), dispatchUntracked);
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
                JobExecution queuedExecution = buildExecution(job, pending, effectiveAddress);
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
     * <p>Both variants read the address from the {@link CronTargetReference} itself — the stable
     * target id through {@link ServiceTargetResolver} for a
     * {@link CronTargetReference.ServiceTarget}, and
     * {@link CronTargetReference.EventBusTarget#address()} for the other — rather than from
     * {@link CronJobDefinition#handlerAddress()}, which is only a copy the registrar derives from
     * the target and leaves {@code null} for service targets. Trusting the derived copy is what
     * produced the defect ADR-0201 records; the target is the authority. Both variants are then
     * subject to the same non-blank check, so no caller can produce an execution record with a
     * {@code null} or blank {@link JobExecution#handler()} — including a caller that builds a
     * definition directly rather than going through {@link CronJobRegistrar}.
     *
     * <p>Returns {@code null} — after logging the job id and the target's canonical form — when the
     * resolver throws or the effective address is null/blank, so the caller can abandon the fire and
     * release whatever guard it already holds. No {@link Exception} is rethrown: a resolution
     * failure skips that fire only, and the next tick retries. The first failure per job logs at
     * {@code ERROR} with the cause and subsequent ones at {@code DEBUG} (see
     * {@link #resolutionFailureLogged}), because the failure is typically permanent while the retry
     * is per-tick.
     *
     * <p>An {@link Error} is deliberately <em>not</em> caught, consistent with this module's rule
     * that an {@code Error} leaves the JVM in an undefined state and must propagate rather than be
     * swallowed (see {@code CronLifecycleVerticle#start}). The cost of that choice is explicit: an
     * {@code Error} raised by a custom {@link ServiceTargetResolver} escapes with the caller's
     * in-flight guard still held, silently stalling that one job on this node. That is an accepted
     * trade — a JVM in an undefined state is the larger problem — but it is the reason the guard is
     * released on the {@code null} return rather than in a {@code finally}.
     *
     * <p>Both interpolated identifiers are config-supplied, so they are sanitized before they reach
     * a log record; see {@link #forLog(String)}.
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
        String targetDescription = target != null ? forLog(target.toCanonical()) : "<none>";
        String address;
        if (target instanceof CronTargetReference.ServiceTarget serviceTarget) {
            try {
                address = serviceTargetResolver
                        .resolve(serviceTarget.stableTargetId())
                        .address();
            } catch (Exception e) {
                logResolutionFailure(job, targetDescription, "could not be resolved", e);
                return null;
            }
        } else if (target instanceof CronTargetReference.EventBusTarget eventBusTarget) {
            address = eventBusTarget.address();
        } else {
            // CronTargetReference is sealed with exactly two variants, so this is reachable only
            // for a malformed definition whose target is null. Fall back to the derived copy so a
            // definition built that way behaves exactly as it did before, then fail the non-blank
            // check below.
            address = job.handlerAddress();
        }
        if (address == null || address.isBlank()) {
            logResolutionFailure(job, targetDescription, "resolved to a null or blank event bus address", null);
            return null;
        }
        resolutionFailureLogged.remove(job.id());
        return address;
    }

    /**
     * Logs a target-resolution failure once per job at {@code ERROR}, then at {@code DEBUG} until
     * that job resolves again. See {@link #resolutionFailureLogged} for why the volume is bounded.
     *
     * @param job               the job whose target failed to resolve
     * @param targetDescription the already-sanitized target description
     * @param reason            what went wrong, phrased to follow the target in the message
     * @param cause             the resolver failure to attach on the first report, or {@code null}
     */
    private void logResolutionFailure(CronJobDefinition job, String targetDescription, String reason, Exception cause) {
        String jobId = forLog(job.id());
        if (resolutionFailureLogged.add(job.id())) {
            log.error(
                    "Cron job '{}' target '{}' {} — skipping this fire; further failures for this"
                            + " job log at DEBUG until it resolves",
                    jobId,
                    targetDescription,
                    reason,
                    cause);
        } else {
            log.debug("Cron job '{}' target '{}' {} — skipping this fire", jobId, targetDescription, reason);
        }
    }

    /**
     * Sanitizes a config-supplied identifier for safe interpolation into a log record.
     *
     * <p>Cron job ids and {@code eventbus:} target addresses are accepted from configuration with
     * only a non-blank check, and configuration can be supplied by environment variables and remote
     * config stores. Written verbatim into a log record under the shipped pattern layouts — which
     * end in {@code %msg%n} with no escaping conversion — an embedded newline would terminate the
     * record and emit the remainder as an independent, fully caller-shaped line: log forging
     * (CWE-117). Length is unbounded too, which multiplies the cost of any repeated report.
     *
     * <p>Delegates to {@link DeferredExecutionOrigin#of(String, String)}, whose documented job is to
     * sanitize a "raw, possibly attacker-influenced boundary identifier" — stripping control and
     * separator code points and bounding length without splitting a surrogate pair. Reused rather
     * than reimplemented so cron cannot drift from the sanitizing rule the rest of the
     * deferred-execution boundary already applies to these same strings.
     *
     * @param raw the raw config-supplied identifier, possibly {@code null}
     * @return the sanitized, length-bounded form, safe to interpolate into a log record, or
     *         {@code "<none>"} when {@code raw} is absent or sanitizes away entirely
     */
    private static String forLog(String raw) {
        if (raw == null || raw.isBlank()) {
            return "<none>";
        }
        // of(kind, reference) substitutes the sanitized kind when the reference sanitizes to blank,
        // which would silently log a control-character-only id as the literal "cron". Detect that
        // substitution and report absence explicitly instead of a plausible-looking value.
        String sanitized = DeferredExecutionOrigin.of("cron", raw).reference();
        return "cron".equals(sanitized) && !"cron".equals(raw) ? "<none>" : sanitized;
    }

    /**
     * Builds a {@link JobExecution} record for a cron fire. The execution ID is freshly
     * generated; the record captures the current instant as {@code startedAt} and uses the job
     * definition's parameters. The initial state is always {@link JobState#PROCESSING} — a cron
     * fire is dispatched immediately, never enqueued.
     *
     * @param job              the cron job being executed
     * @param scheduledAt      the scheduled fire time
     * @param effectiveAddress the non-blank event bus address this fire dispatches to, as returned
     *                         by {@link #resolveEffectiveAddress(CronJobDefinition)}; recorded as
     *                         the execution's {@code handler} so the persisted address always
     *                         equals the dispatched one
     * @return the constructed execution record
     */
    private JobExecution buildExecution(CronJobDefinition job, Instant scheduledAt, String effectiveAddress) {
        UUID executionId = UUID.randomUUID();
        return new JobExecution(
                executionId,
                job.id(),
                JobType.CRON,
                effectiveAddress,
                "cron",
                JobState.PROCESSING,
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
