// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.delayed;

import dev.vertique.context.DurableContextPropagator;
import dev.vertique.core.async.Combinators;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.core.context.DispatchBoundary;
import dev.vertique.core.context.DurableCarrierDescriptor;
import dev.vertique.core.context.DurableTarget;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.core.extension.OrderedExtension;
import dev.vertique.core.resilience.BackoffStrategy;
import dev.vertique.job.DefaultJobContext;
import dev.vertique.job.JobCompletionHandler;
import dev.vertique.job.JobContext;
import dev.vertique.job.JobDispatchContext;
import dev.vertique.job.JobExecution;
import dev.vertique.job.JobInterceptor;
import dev.vertique.job.JobInterceptors;
import dev.vertique.job.JobLogFlusher;
import dev.vertique.job.JobRepository;
import dev.vertique.job.JobState;
import dev.vertique.job.JobType;
import dev.vertique.job.ProgressSnapshot;
import dev.vertique.job.delayed.config.DelayedJobQueueConfig;
import dev.vertique.logging.MDCContexts;
import io.vertx.core.AbstractVerticle;
import io.vertx.core.Promise;
import io.vertx.core.eventbus.MessageConsumer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;

/**
 * Vert.x verticle that polls a single named queue for {@link JobState#ENQUEUED} jobs and
 * dispatches each claimed execution via fire-and-report through the event bus.
 *
 * <p>By default, one instance is deployed per configured queue. Multiple instances per queue are
 * safe — {@code FOR UPDATE SKIP LOCKED} prevents duplicate claims — and will multiply throughput
 * (each instance maintains its own concurrency semaphore). The poller runs a single background
 * timer:
 * <ul>
 *   <li><b>Poll timer:</b> A recurring one-shot timer (using {@link io.vertx.core.Vertx#setTimer})
 *       that wakes up every {@link DelayedJobQueueConfig#sleepDelayMs()} milliseconds, claims up to
 *       {@link DelayedJobQueueConfig#maxConcurrentJobs()} executions from the repository, dispatches each
 *       one, and then reschedules itself.</li>
 * </ul>
 *
 * <p>Future-scheduled jobs are inserted as {@link JobState#ENQUEUED} with a future
 * {@code scheduled_at} value. The {@link JobRepository#claimNextJob(String, int)} query already
 * filters by {@code scheduled_at <= NOW()}, so no separate SCHEDULED→ENQUEUED transition timer is
 * required.
 *
 * <p><b>Concurrency control:</b> An {@link AtomicInteger} semaphore tracks in-flight dispatches.
 * When the in-flight count reaches {@link DelayedJobQueueConfig#maxConcurrentJobs()}, the poll cycle
 * skips claiming and reschedules for the next cycle.
 *
 * <p><b>Dispatch pattern:</b> For each claimed job, the poller:
 * <ol>
 *   <li>Builds a {@link DispatchEnvelope} with payload, MDC context, and dispatch context.</li>
 *   <li>Registers a one-time reply consumer on {@code job.completions.<executionId>}.</li>
 *   <li>Fires {@link JobInterceptor#onDispatch} in {@link dev.vertique.core.extension.OrderedExtension}
 *       order (phase → priority → orderKey).</li>
 *   <li>Sends the body to the handler's event bus address.</li>
 *   <li>On reply: extracts the {@link Result}, fires {@link JobInterceptor#onComplete}, and
 *       delegates to {@link JobCompletionHandler} for DB update (success/retry/dead-letter).</li>
 * </ol>
 *
 * <p><b>Threading model:</b> Each verticle instance runs on a single Vert.x event loop context
 * (standard verticle behavior). Timer callbacks, event bus handlers, and concurrency guard
 * operations within one instance are single-threaded. Do not deploy with
 * {@code ThreadingModel.WORKER}. Multiple instances of the same queue are safe to deploy.
 *
 * <p><b>Graceful shutdown:</b> On {@link #stop(Promise)}, the poll timer is cancelled and all
 * active reply consumers are unregistered. In-flight executions are not interrupted — they will
 * complete independently but their completion callbacks may run after the verticle is stopped.
 * Each in-flight execution's buffered job logs are drained one last time before the stop promise
 * completes; because the handler keeps running, that is a cutoff snapshot rather than a final
 * flush.
 *
 * <p><b>Consumer timeout:</b> When {@code executionTimeoutMs} is positive, a local Vert.x timer
 * is set per dispatched execution. If no reply arrives before the timer fires, the execution is
 * treated as an interruption: if retries remain it is atomically re-enqueued via
 * {@link dev.vertique.job.JobRepository#abandonAndScheduleRetry} (recording one
 * {@link dev.vertique.job.JobState#ABANDONED} transition); if attempts are exhausted it is
 * dead-lettered. Set to {@code 0} to disable.
 *
 * <p><b>Cooperative cancellation:</b> Each dispatched execution registers a consumer on
 * {@code job.cancel.<executionId>}. When a cancel message arrives the
 * {@link dev.vertique.job.DefaultJobContext#isCancelled()} flag is set. Handlers should check
 * this flag periodically and return early when set.
 *
 * <p><b>Progress flush:</b> When {@code progressFlushIntervalMs} is positive, a periodic timer
 * writes {@link dev.vertique.job.ProgressSnapshot} changes to the repository. Only changed
 * snapshots are flushed, avoiding redundant DB writes for jobs that do not report progress.
 *
 * <p><b>Job log flush:</b> Buffered {@link dev.vertique.job.JobLogger} entries are drained to the
 * repository through a per-execution {@link JobLogFlusher} — on the same periodic tick (there
 * unconditionally, since log entries change independently of the progress snapshot) and on every
 * path that ends the execution: completion, timeout, and verticle stop. With
 * {@code progressFlushIntervalMs = 0} only the ending sites flush, so logs remain durable but are
 * not visible until the execution ends.
 */
@Slf4j
public class DelayedJobPoller extends AbstractVerticle {

    // --- Constants ---

    /**
     * Upper bound, in seconds, on how long {@link #stop(Promise)} waits for one execution's
     * shutdown log flush to settle. A wedged connection pool can yield a future that never
     * settles at all, so this bound — not error recovery — is what keeps undeploy from hanging.
     */
    private static final long SHUTDOWN_FLUSH_TIMEOUT_SECONDS = 5L;

    // --- Dependencies ---

    private final String queue;
    private final DelayedJobQueueConfig config;
    private final JobRepository repository;
    private final JobCompletionHandler completionHandler;
    private final List<JobInterceptor> interceptors;
    private final EventBusClient eventBusClient;

    /**
     * Per-execution timeout in milliseconds. When positive, a local timer fires if the handler
     * does not reply in time and triggers retry/dead-letter via the completion handler. {@code 0}
     * disables the timeout.
     */
    private final long executionTimeoutMs;

    /**
     * Interval in milliseconds for flushing {@link dev.vertique.job.ProgressSnapshot} updates to
     * the repository. Only writes when the snapshot has changed. {@code 0} disables periodic
     * flush.
     */
    private final long progressFlushIntervalMs;

    /**
     * Optional dispatch envelope builder; when present, outbound job-dispatch envelopes are built
     * through the context substrate (FR-CTX-015) so registered
     * {@link dev.vertique.core.context.ServiceDispatchContextEncoder}s capture currently-bound
     * holder values. Pass {@code null} only in tests.
     */
    private final dev.vertique.context.DispatchEnvelopeBuilder envelopeBuilder;

    /**
     * Durable context propagator used to decode {@code job_executions.metadata} into an
     * FQCN-keyed dispatch-context map at poll time (FR-CTX-177). The decoded values are seeded
     * into the outgoing envelope's {@code callerOverrides} so the receiving
     * {@code ServiceMethodInvoker} installs them via {@code InboundDispatchScope} on its own
     * duplicated event-bus context. The poller itself runs on the verticle's deployment context
     * (non-duplicated), so holder writes are not performed here (FR-CTX-172 path 2).
     */
    private final DurableContextPropagator propagator;

    // --- Runtime state ---

    /** Tracks the number of currently in-flight job dispatches for this queue. */
    private final AtomicInteger inFlight = new AtomicInteger();

    /** Tracks active reply consumers so they can be unregistered on stop. */
    private final Set<MessageConsumer<?>> activeConsumers = ConcurrentHashMap.newKeySet();

    /**
     * Tracks per-execution resources (timeout timer, progress-flush timer, cancel consumer, log
     * flusher) for cleanup when the consumer replies, the timeout fires, or the poller stops.
     */
    private final Map<UUID, ExecutionResources> activeExecutions = new ConcurrentHashMap<>();

    // --- Inner types ---

    /**
     * Per-execution resource bundle: timeout timer ID, progress-flush timer ID, cancel consumer,
     * and job-log flusher. Timer IDs use {@code -1L} as the sentinel for "not set".
     *
     * @param timeoutId       Vert.x timer ID for the execution timeout, or {@code -1}
     * @param progressFlushId Vert.x timer ID for the progress-flush periodic timer, or {@code -1}
     * @param cancelConsumer  event-bus consumer for cooperative cancellation signals
     * @param logFlusher      drains this execution's buffered job log entries to the repository;
     *                        retained so {@link #stop(Promise)} can take a cutoff snapshot of an
     *                        execution that is still in flight
     */
    private record ExecutionResources(
            long timeoutId, long progressFlushId, MessageConsumer<?> cancelConsumer, JobLogFlusher logFlusher) {}

    /**
     * Current adaptive poll delay in milliseconds. Starts at {@link DelayedJobQueueConfig#sleepDelayMs()},
     * doubles on empty polls (up to 4× base, capped at 60 s), and resets when jobs are found.
     */
    private long currentPollDelay;

    private long pollTimerId = -1;
    private volatile boolean running;

    /**
     * Creates a new poller for the given queue with no execution timeout or progress-flush support.
     *
     * @param queue             the logical queue name to poll
     * @param config            per-queue polling configuration
     * @param repository        the job repository for claiming and state updates
     * @param completionHandler shared utility for handling job completion (success/retry/dead-letter)
     * @param interceptors      job interceptors to fire around each dispatch, in
     *                          {@link dev.vertique.core.extension.OrderedExtension} order
     *                          (phase → priority → orderKey)
     * @param eventBusClient    the event bus client for dispatch protocol sends
     * @param envelopeBuilder   envelope builder used to construct outgoing job-dispatch envelopes
     *                          through the context substrate (FR-CTX-015); tests that do not
     *                          exercise context propagation pass
     *                          {@link dev.vertique.context.DispatchEnvelopeBuilder#forTesting()}
     * @param propagator        durable context propagator for decoding {@code job_executions.metadata}
     *                          into the outgoing envelope's caller-overrides (FR-CTX-177)
     */
    public DelayedJobPoller(
            String queue,
            DelayedJobQueueConfig config,
            JobRepository repository,
            JobCompletionHandler completionHandler,
            Set<JobInterceptor> interceptors,
            EventBusClient eventBusClient,
            dev.vertique.context.DispatchEnvelopeBuilder envelopeBuilder,
            DurableContextPropagator propagator) {
        this(
                queue,
                config,
                repository,
                completionHandler,
                interceptors,
                eventBusClient,
                0L,
                0L,
                envelopeBuilder,
                propagator);
    }

    /**
     * Full constructor including the {@link dev.vertique.context.DispatchEnvelopeBuilder}
     * for context-substrate capture (FR-CTX-015) and the {@link DurableContextPropagator} for
     * durable metadata decoding (FR-CTX-177). Currently bound holder values flow into the outgoing
     * dispatch envelope via registered
     * {@link dev.vertique.core.context.ServiceDispatchContextEncoder}s.
     *
     * @param queue                   the logical queue name to poll
     * @param config                  per-queue polling configuration
     * @param repository              the job repository for claiming and state updates
     * @param completionHandler       shared utility for handling job completion
     * @param interceptors            job interceptors to fire around each dispatch, in
     *                                {@link dev.vertique.core.extension.OrderedExtension} order
     *                                (phase → priority → orderKey)
     * @param eventBusClient          the event bus client for dispatch protocol sends
     * @param executionTimeoutMs      per-execution timeout in milliseconds; {@code 0} disables
     * @param progressFlushIntervalMs interval in milliseconds for flushing progress snapshots;
     *                                {@code 0} disables
     * @param envelopeBuilder         envelope builder used to construct outgoing job-dispatch envelopes
     * @param propagator              durable context propagator for decoding persisted metadata into
     *                                the outgoing envelope's caller-overrides (FR-CTX-177); must not
     *                                be {@code null}
     */
    public DelayedJobPoller(
            String queue,
            DelayedJobQueueConfig config,
            JobRepository repository,
            JobCompletionHandler completionHandler,
            Set<JobInterceptor> interceptors,
            EventBusClient eventBusClient,
            long executionTimeoutMs,
            long progressFlushIntervalMs,
            dev.vertique.context.DispatchEnvelopeBuilder envelopeBuilder,
            DurableContextPropagator propagator) {
        this.queue = queue;
        this.config = config;
        this.repository = repository;
        this.completionHandler = completionHandler;
        this.eventBusClient = eventBusClient;
        this.executionTimeoutMs = executionTimeoutMs;
        this.progressFlushIntervalMs = progressFlushIntervalMs;
        this.envelopeBuilder =
                java.util.Objects.requireNonNull(envelopeBuilder, "envelopeBuilder must not be null (FR-CTX-015)");
        this.propagator = java.util.Objects.requireNonNull(propagator, "propagator must not be null (FR-CTX-177)");
        this.interceptors =
                interceptors.stream().sorted(OrderedExtension.comparator()).toList();
    }

    // --- Verticle lifecycle ---

    /**
     * Starts the poller by registering the local message codec (idempotent) and launching the
     * poll timer.
     *
     * @param startPromise the startup promise to complete when the verticle is ready
     */
    @Override
    public void start(Promise<Void> startPromise) {
        running = true;
        currentPollDelay = config.sleepDelayMs();
        // Register the local codec; ignore if already registered by another poller or cron
        try {
            vertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
        } catch (IllegalStateException ignored) {
            // Already registered — idempotent registration is acceptable
        }

        schedulePoll();
        log.info(
                "DelayedJobPoller started for queue='{}' sleepDelayMs={}ms maxConcurrent={}",
                queue,
                config.sleepDelayMs(),
                config.maxConcurrentJobs());
        startPromise.complete();
    }

    /**
     * Stops the poller by cancelling the poll timer, unregistering all active reply consumers,
     * cleaning up per-execution resources (timeout timers, progress-flush timers, cancel
     * consumers), and taking a final cutoff snapshot of each in-flight execution's job logs.
     *
     * <p><b>The shutdown flush is a cutoff snapshot, not a final flush.</b> In-flight executions
     * are not interrupted, so a handler may keep logging after its buffer is drained here; those
     * later entries are lost. The snapshot bounds what a graceful shutdown loses — it does not
     * eliminate loss.
     *
     * @param stopPromise the shutdown promise, completed once every cutoff flush has settled or
     *                    hit its per-execution timeout bound
     */
    @Override
    public void stop(Promise<Void> stopPromise) {
        running = false;
        if (pollTimerId != -1) {
            vertx.cancelTimer(pollTimerId);
        }
        for (MessageConsumer<?> consumer : activeConsumers) {
            consumer.unregister();
        }
        activeConsumers.clear();
        // Cancel per-execution resources (timeout timers, progress-flush timers, cancel consumers)
        // and drain each execution's buffered log entries one last time. The cancels stay in their
        // own loop so a cancelTimer/unregister throw still propagates rather than being swallowed
        // and mislabelled as a flush failure.
        List<ExecutionResources> pending = new ArrayList<>(activeExecutions.values());
        for (ExecutionResources resources : pending) {
            if (resources.timeoutId() != -1L) {
                vertx.cancelTimer(resources.timeoutId());
            }
            if (resources.progressFlushId() != -1L) {
                vertx.cancelTimer(resources.progressFlushId());
            }
            resources.cancelConsumer().unregister();
        }
        activeExecutions.clear();

        // joinAllSwallow waits for every flush to settle without short-circuiting and always
        // succeeds — the all-settled-swallow contract this shutdown needs. The timeout bound stays
        // inside the hook and is load-bearing, not belt-and-braces: swallowing only handles a
        // *failed* future, and a wedged connection pool yields one that never settles at all.
        Combinators.joinAllSwallow(
                        pending,
                        resources -> resources
                                .logFlusher()
                                .flush()
                                .timeout(SHUTDOWN_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                        (resources, err) -> log.warn(
                                "Shutdown job-log flush did not settle for queue='{}': {}", queue, err.getMessage()))
                .onComplete(ar -> {
                    log.info("DelayedJobPoller stopped for queue='{}'", queue);
                    stopPromise.complete();
                });
    }

    // --- Poll loop ---

    /**
     * Schedules the next poll cycle using a one-shot timer. The timer reschedules itself after
     * each cycle so that the {@link DelayedJobQueueConfig#sleepDelayMs()} is measured from the end of
     * the previous cycle rather than from the start.
     */
    private void schedulePoll() {
        if (!running) {
            return;
        }
        pollTimerId = vertx.setTimer(currentPollDelay, id -> {
            if (!running) {
                return;
            }
            poll();
        });
    }

    /**
     * Executes one poll cycle: checks available capacity, claims eligible jobs from the
     * repository, dispatches each one, and reschedules the next cycle.
     */
    private void poll() {
        int available = config.maxConcurrentJobs() - inFlight.get();
        if (available <= 0) {
            log.debug(
                    "DelayedJobPoller queue='{}': all {} slots in use, skipping poll",
                    queue,
                    config.maxConcurrentJobs());
            schedulePoll();
            return;
        }

        repository
                .claimNextJob(queue, available)
                .onSuccess(jobs -> {
                    if (jobs.isEmpty()) {
                        // Back off: double delay up to 4x base, capped at 60 seconds
                        currentPollDelay = Math.min(currentPollDelay * 2, Math.min(config.sleepDelayMs() * 4, 60_000L));
                    } else {
                        // Reset to base delay when jobs are found
                        currentPollDelay = config.sleepDelayMs();
                        log.debug("DelayedJobPoller queue='{}': claimed {} job(s)", queue, jobs.size());
                    }
                    for (JobExecution job : jobs) {
                        inFlight.incrementAndGet();
                        dispatch(job);
                    }
                    schedulePoll();
                })
                .onFailure(err -> {
                    log.warn("DelayedJobPoller queue='{}': failed to claim jobs: {}", queue, err.getMessage(), err);
                    schedulePoll();
                });
    }

    // --- Dispatch ---

    /**
     * Dispatches a single claimed job execution to its handler via the event bus.
     *
     * <p>Follows the same pattern as {@code CronScheduler.dispatch()}: builds a {@link DispatchEnvelope}
     * with payload, MDC context map, and dispatch context; registers a one-time reply consumer;
     * fires interceptors; and sends to the handler address.
     *
     * <p>On reply, extracts the {@link Result}, fires {@link JobInterceptor#onComplete}, and
     * delegates to {@link JobCompletionHandler} for DB state update. The in-flight permit is
     * released in the finally block.
     *
     * <p>Per-execution resources registered here:
     * <ul>
     *   <li><b>Cancel consumer</b> on {@code job.cancel.<executionId>} — sets the cancelled flag
     *       on the {@link DefaultJobContext} for cooperative handler cancellation.</li>
     *   <li><b>Progress-flush timer</b> — writes changed {@link ProgressSnapshot}s to the
     *       repository on the {@link #progressFlushIntervalMs} interval. Only active when
     *       {@code progressFlushIntervalMs > 0}.</li>
     *   <li><b>Execution-timeout timer</b> — fires after {@link #executionTimeoutMs}: if attempts
     *       remain it atomically re-enqueues via {@code abandonAndScheduleRetry} (one
     *       {@link JobState#ABANDONED} transition); otherwise it dead-letters directly. Only active
     *       when {@code executionTimeoutMs > 0}.</li>
     *   <li><b>Job log flusher</b> — drains the {@link DefaultJobContext}'s buffered log entries to
     *       {@code job_logs} on the progress tick and on every execution-ending path. Built
     *       unconditionally, independently of {@link #progressFlushIntervalMs}.</li>
     * </ul>
     *
     * @param execution the claimed execution to dispatch
     */
    private void dispatch(JobExecution execution) {
        UUID executionId = execution.id();
        Instant startedAt = Instant.now();

        DefaultJobContext jobContext =
                new DefaultJobContext(execution.jobId(), executionId, execution.attemptNumber(), JobType.DELAYED);

        JobDispatchContext dispatchCtx = JobDispatchContext.fromExecution(execution, executionId, startedAt);

        // F5 row binding (PRD identity-002 §14.6/A9): decode against THIS executing row's own carrier
        // so a durable identity snapshot signed for a different execution id fails closed at the
        // receive-side decoder. The carrier mirrors the schedule side (DelayedJobService.toExecution):
        // carrierId = the executing row id; target = (delayed-job, the row's handler event-bus
        // address). execution.handler() is the persisted, validated non-blank address, so the
        // DurableTarget invariants hold.
        DurableCarrierDescriptor carrier = new DurableCarrierDescriptor(
                executionId.toString(),
                new DurableTarget(DispatchBoundary.DELAYED_JOB, execution.handler(), Optional.empty()));

        // Decode persisted durable metadata into an FQCN-keyed map without touching the holder —
        // the poller runs on the verticle's deployment context (NOT a duplicated context), so a
        // holder write would fail the substrate's duplicated-context guard (FR-CTX-172 path 2).
        // Decoded values — including DurablePropagationMetadata — ride in the outgoing envelope's
        // callerOverrides; the receiving ServiceMethodInvoker installs them into the holder via
        // InboundDispatchScope on its own duplicated event-bus context.
        //
        // JobContext / JobDispatchContext / MDCContexts.holderKey() all use FQCNs that are
        // distinct from any durable encoder's declared metadata key (durable keys are short
        // human-readable strings such as "x-corr", never Java class FQCNs), so adding those
        // entries after the decoded map cannot overwrite decoded durable values.
        Map<String, Object> ctx = new java.util.HashMap<>(
                propagator.decodeToDispatchContext(execution.metadata(), DispatchBoundary.DELAYED_JOB, carrier));
        ctx.put(JobContext.class.getName(), jobContext);
        ctx.put(JobDispatchContext.class.getName(), dispatchCtx);
        // Provenance proving this is deferred (delayed-job) execution so the receive-side identity
        // reconstruction initializer may mint a bounded SYSTEM context (W2/A6). Keyed by FQCN like
        // the JobDispatchContext entry; reinstated before InboundContextInitializers run. Uses the
        // handler's event-bus address — a stable, resolver-mappable id — rather than jobId, which is
        // often an auto-generated "delayed-" + UUID per DelayedJobService. The .of factory sanitizes
        // (blank/length/control chars), so the strict ctor cannot throw out of this dispatch path.
        ctx.put(
                DeferredExecutionOrigin.class.getName(),
                DeferredExecutionOrigin.of("delayed-job", execution.handler()));

        Map<String, String> mdc = dispatchCtx.toMdcContext();
        // The poller runs on the verticle's deployment context (NOT a duplicated context), so
        // we cannot bind MDC into the holder. Compose the MDC holder value directly into
        // callerOverrides; the receive-side ServiceMethodInvoker installs it via the
        // DiagnosticContextSnapshot decoder on its own duplicated event-bus context.
        if (!mdc.isEmpty()) {
            ctx.put(dev.vertique.logging.MDCContexts.holderKey(), dev.vertique.logging.MDCContexts.holderValue(mdc));
        }

        String replyAddress = "job.completions." + executionId;
        // FR-CTX-015: always route through DispatchEnvelopeBuilder. The builder is required
        // (non-null) per the constructor contract.
        DispatchEnvelope<?> body = envelopeBuilder.build(
                execution.payload(), ctx, dev.vertique.core.context.DispatchBoundary.SERVICE_DISPATCH, replyAddress);

        // --- Per-execution resources: cancel consumer, progress-flush timer, timeout timer ---

        // Cooperative cancel: sets the cancelled flag on the JobContext
        final MessageConsumer<?> cancelConsumer = vertx.eventBus().consumer("job.cancel." + executionId, msg -> {
            jobContext.setCancelled(true);
            log.info("Cancel requested for delayed job '{}' execution {}", execution.jobId(), executionId);
        });

        // Job log flush: drains the context's buffered log entries to job_logs. Constructed
        // OUTSIDE the progressFlushIntervalMs guard on purpose — the ending sites below flush
        // through it too, so building it inside the guard would mean a 0 interval (periodic flush
        // disabled) silently made job logs non-durable rather than merely less timely.
        final JobLogFlusher logFlusher = new JobLogFlusher(repository, executionId, jobContext);

        // Progress flush: periodically write changed snapshots to the repository
        final ProgressSnapshot[] lastFlushed = {ProgressSnapshot.EMPTY};
        final long progressFlushId = (progressFlushIntervalMs > 0)
                ? vertx.setPeriodic(progressFlushIntervalMs, id -> {
                    ProgressSnapshot current = jobContext.progress().snapshot();
                    if (!current.equals(lastFlushed[0])) {
                        lastFlushed[0] = current;
                        repository
                                .heartbeat(executionId, current)
                                .onFailure(err -> log.debug(
                                        "Progress flush failed for '{}': {}", execution.jobId(), err.getMessage()));
                    }
                    // Unconditional: log entries change independently of the progress snapshot, so
                    // gating this on the snapshot-changed check would strand the logs of any job
                    // that logs without reporting progress. The flusher is itself a no-op when
                    // nothing is buffered.
                    logFlusher.flush();
                })
                : -1L;

        MessageConsumer<?> consumer = vertx.eventBus().consumer(replyAddress);
        activeConsumers.add(consumer);

        // Execution timeout: fires if handler never replies — triggers retry/dead-letter
        final long timeoutId = (executionTimeoutMs > 0)
                ? vertx.setTimer(executionTimeoutMs, id -> {
                    if (activeConsumers.remove(consumer)) {
                        consumer.unregister();
                        cancelConsumer.unregister();
                        if (progressFlushId != -1L) {
                            vertx.cancelTimer(progressFlushId);
                        }
                        activeExecutions.remove(executionId);

                        // Enrich the scheduler thread's SLF4J MDC for the timeout log line. Timer
                        // callbacks run on the verticle's deployment context (not a duplicated
                        // context), so the framework MDC facade's write-side guard would reject
                        // these. SchedulerMdcScope captures prior MDC values per key so cleanup
                        // restores (rather than blindly removes) the caller's pre-enrichment
                        // state. The receive side has its own MDC binding on its duplicated context.
                        try (dev.vertique.job.SchedulerMdcScope mdcScope =
                                dev.vertique.job.SchedulerMdcScope.install(mdc)) {
                            log.error(
                                    "Delayed job '{}' execution {} timed out after {}ms — handling as interruption",
                                    execution.jobId(),
                                    executionId,
                                    executionTimeoutMs);
                            // A timeout is an interruption, not a handler failure. If attempts remain, mark
                            // ABANDONED (one INTERRUPTED audit record) and re-enqueue from ABANDONED; if
                            // exhausted, dead-letter directly (one record). We do NOT route through
                            // handleCompletion — that would add a second ABANDONED->FAILED transition and a
                            // contradictory second audit record for the same timeout.
                            if (execution.attemptNumber() + 1 < execution.maxAttempts()) {
                                int nextAttempt = execution.attemptNumber() + 1;
                                Instant nextScheduledAt = Instant.now()
                                        .plusMillis(resolveBackoffStrategy().delay(nextAttempt));
                                // Atomically mark ABANDONED (one INTERRUPTED audit record) and re-enqueue,
                                // so a crash cannot strand the execution in ABANDONED.
                                repository
                                        .abandonAndScheduleRetry(
                                                executionId,
                                                "Execution timeout",
                                                "ExecutionTimeoutException",
                                                jobContext.progress().snapshot(),
                                                nextScheduledAt,
                                                nextAttempt)
                                        .onFailure(err -> log.warn(
                                                "Failed to abandon and re-enqueue timed-out execution {}: {}",
                                                executionId,
                                                err.getMessage()));
                            } else {
                                repository
                                        .completeExecution(
                                                executionId,
                                                JobState.DEAD_LETTER,
                                                "Execution timeout",
                                                "ExecutionTimeoutException",
                                                jobContext.progress().snapshot())
                                        .onFailure(err -> log.warn(
                                                "Failed to dead-letter timed-out execution {}: {}",
                                                executionId,
                                                err.getMessage()));
                            }
                        } finally {
                            inFlight.decrementAndGet();
                            // Both timeout outcomes above (abandon-and-retry, dead-letter) end this
                            // execution, so the single flush in this finally covers both. It runs
                            // last so nothing that ends the execution — least of all the in-flight
                            // release — can be skipped by it, and runs in a finally so a throw from
                            // the outcome handling above cannot strand the buffered entries. The
                            // handler is not interrupted and may still append entries afterwards;
                            // those are lost unless a later attempt reuses the same execution id.
                            logFlusher.flush();
                        }
                    }
                })
                : -1L;

        // Track resources for cleanup on stop() or early reply
        activeExecutions.put(
                executionId, new ExecutionResources(timeoutId, progressFlushId, cancelConsumer, logFlusher));

        // --- Completion consumer ---

        consumer.handler(msg -> {
            // Cancel per-execution resources immediately on reply
            if (timeoutId != -1L) {
                vertx.cancelTimer(timeoutId);
            }
            if (progressFlushId != -1L) {
                vertx.cancelTimer(progressFlushId);
            }
            cancelConsumer.unregister();
            activeExecutions.remove(executionId);

            consumer.unregister();
            activeConsumers.remove(consumer);

            Instant endTime = Instant.now();

            // Wrap the MDC-scoped block in an outer try/finally so the in-flight counter is
            // released even if MDCContexts.bindAll itself throws during resource acquisition
            // (e.g. a substrate invariant violation) — a nested finally inside the
            // try-with-resources would miss that path and strand the counter.
            try {
                // MDCContexts.bindAll snapshots prior per-key state at install time and restores
                // it (including absence) on close — try-with-resources gives LIFO unwind without
                // manual remove() bookkeeping. The event-bus consumer callback runs on a
                // duplicated Vert.x context, so the framework MDC write-guard accepts the bind.
                try (ContextHolder.Scope mdcScope = MDCContexts.bindAll(mdc)) {
                    Result<?> result = null;
                    if (msg.body() instanceof DispatchEnvelope<?> replyBody
                            && replyBody.payload() instanceof Result<?> r) {
                        result = r;
                    }

                    JobInterceptors.fireOnComplete(interceptors, dispatchCtx, result, startedAt, endTime, log);

                    BackoffStrategy backoff = resolveBackoffStrategy();
                    completionHandler
                            .handleCompletion(execution, result, backoff)
                            .onFailure(err -> log.warn(
                                    "Completion handling failed for job '{}' execution {}",
                                    execution.jobId(),
                                    executionId,
                                    err));

                    if (result != null && result.isFailure()) {
                        log.warn(
                                "Delayed job '{}' execution {} failed: {}",
                                execution.jobId(),
                                executionId,
                                result.cause().getMessage());
                    } else {
                        log.debug(
                                "Delayed job '{}' execution {} completed successfully", execution.jobId(), executionId);
                    }
                }
            } finally {
                inFlight.decrementAndGet();
                // The handler has reported, so nothing more will be appended: this drains whatever
                // the periodic tick had not yet claimed. Fire-and-forget, and deliberately last —
                // no work that ends the execution may sit behind it, and the finally keeps the
                // drain reachable even when completion handling throws (JobLogFlusher itself never
                // throws and always returns a succeeded future).
                logFlusher.flush();
            }
        });

        // Enrich the scheduler thread's SLF4J MDC for the dispatch log line. The poller's dispatch
        // is invoked from the verticle's deployment context (not a duplicated context).
        // SchedulerMdcScope captures prior MDC values per key so cleanup restores (rather than
        // blindly removes) the caller's pre-enrichment state. The receive-side ServiceMethodInvoker
        // handles the substrate MDC on its own duplicated context.
        try (dev.vertique.job.SchedulerMdcScope mdcScope = dev.vertique.job.SchedulerMdcScope.install(mdc)) {
            JobInterceptors.fireOnDispatch(interceptors, dispatchCtx, log);
            log.debug(
                    "Dispatching delayed job '{}' execution {} to '{}'",
                    execution.jobId(),
                    executionId,
                    execution.handler());
            eventBusClient.send(execution.handler(), body);
        }
    }

    // --- Backoff strategy ---

    /**
     * Resolves the {@link BackoffStrategy} from the queue configuration.
     *
     * <p>Supported strategy names (case-insensitive):
     * <ul>
     *   <li>{@code "FIXED"} — constant delay of {@link DelayedJobQueueConfig#backoffBaseDelayMs()}</li>
     *   <li>{@code "EXPONENTIAL"} — exponential backoff with multiplier 2.0</li>
     *   <li>{@code "LINEAR"} (default) — linear backoff: {@code min(baseDelay × (attempt+1), maxDelay)}</li>
     * </ul>
     *
     * @return the resolved backoff strategy
     */
    private BackoffStrategy resolveBackoffStrategy() {
        return BackoffStrategyType.fromConfig(config.backoffStrategy())
                .toStrategy(config.backoffBaseDelayMs(), config.backoffMaxDelayMs());
    }

    /**
     * Returns the name of the queue this poller serves.
     *
     * @return the queue name
     */
    public String queue() {
        return queue;
    }

    /**
     * Returns the current number of in-flight job dispatches for this queue.
     *
     * @return the in-flight count
     */
    public int inFlightCount() {
        return inFlight.get();
    }
}
