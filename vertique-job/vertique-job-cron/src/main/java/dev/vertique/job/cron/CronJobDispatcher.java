// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.job.cron;

import dev.vertique.core.async.Combinators;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.DeferredExecutionOrigin;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import dev.vertique.core.eventbus.Result;
import dev.vertique.job.DefaultJobContext;
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
import dev.vertique.logging.MDCContexts;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.MessageConsumer;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * Dispatches cron job executions to handlers via the event bus, managing per-execution resources
 * (cancel consumers, progress-flush timers, timeout timers) and completion handling.
 *
 * <p>Each call to {@link #dispatch} registers four per-execution resources:
 *
 * <ul>
 *   <li><b>Cancel consumer</b> on {@code job.cancel.<executionId>} — sets the cancelled flag on
 *       the {@link DefaultJobContext} for cooperative handler cancellation.
 *   <li><b>Progress-flush timer</b> — writes changed {@link ProgressSnapshot}s to the repository
 *       on the configured interval when a repository is bound.
 *   <li><b>Execution-timeout timer</b> — fires after the configured timeout and marks the
 *       execution {@link JobState#ABANDONED} if the handler has not replied.
 *   <li><b>Job log flusher</b> — drains the {@link DefaultJobContext}'s buffered
 *       {@link dev.vertique.job.JobLogger} entries to {@code job_logs}.
 * </ul>
 *
 * <p>All resources are cleaned up on handler reply, timeout, or {@link #shutdown()}.
 *
 * <p><b>Completion ownership:</b> exactly one of three sites ends an execution — the reply
 * consumer, the execution-timeout timer, or the synchronous-failure containment in
 * {@link #dispatch} — and each must claim the execution's one-shot completion latch (an
 * {@link java.util.concurrent.atomic.AtomicBoolean} carried in {@link ExecutionResources}) before
 * it releases anything. The latch is created <em>before</em> the first per-execution resource
 * exists, which is what makes the containment region in {@link #dispatch} able to cover every
 * resource registration; {@link #shutdown()} claims it too, so a callback already queued on the
 * event loop cannot release a concurrency slot after {@link CronScheduler#stop()} has reset the
 * counters. The completion callback releases the scheduler's per-job in-flight guard and its
 * global concurrency slot, so a second claim would be a double release — which drives
 * {@code CronConcurrencyManager}'s active count negative and permanently over-admits jobs.
 *
 * <p><b>Job log flush:</b> Buffered log entries are drained through a per-execution
 * {@link JobLogFlusher} on the same periodic tick as the progress snapshot (there unconditionally,
 * since log entries change independently of the snapshot) and on every path that ends the
 * execution: completion, timeout, and {@link #shutdown()}. The periodic tick calls
 * {@link JobLogFlusher#flush()}; every ending site calls {@link JobLogFlusher#drain()}, which
 * awaits an outstanding write and re-flushes while entries remain, because it has just cancelled
 * the tick that would otherwise have retried. An <em>untracked</em> fire
 * ({@code execution == null}, i.e. {@code tracked=false} or no repository bound) has no
 * {@code job_executions} row, and {@code job_logs.execution_id} is
 * {@code NOT NULL REFERENCES job_executions(id)} — so such a fire is given a flusher built with a
 * {@code null} execution id, which is a genuine no-op rather than a foreign-key violation.
 */
@Slf4j
final class CronJobDispatcher {

    // --- Constants ---

    /**
     * Upper bound, in seconds, on how long {@link #shutdown()} waits for one execution's shutdown
     * log drain to settle. {@link JobLogFlusher#flush()} already bounds each individual write, so
     * a drain settles on its own eventually; this bound collapses the drain's whole round budget
     * into one wait so undeploy is not held for the sum of them.
     */
    private static final long SHUTDOWN_FLUSH_TIMEOUT_SECONDS = 5L;

    /**
     * Callback invoked by the dispatcher when a job execution finishes, either via a handler
     * reply or a timeout. The scheduler uses this to manage concurrency and re-dispatch pending
     * QUEUE_ONE fires.
     */
    interface CompletionCallback {
        /**
         * Called when the given job's execution has completed.
         *
         * @param job the cron job definition whose execution just ended
         */
        void onCompleted(CronJobDefinition job);
    }

    /**
     * Per-execution resource bundle: timeout timer ID, progress-flush timer ID, cancel consumer,
     * job-log flusher, and the execution's one-shot completion latch. All timer fields use
     * {@code -1L} as the sentinel for "not set".
     *
     * @param timeoutId       Vert.x timer ID for the execution timeout, or {@code -1}
     * @param progressFlushId Vert.x timer ID for the progress-flush periodic timer, or {@code -1}
     * @param cancelConsumer  event-bus consumer for cooperative cancellation signals
     * @param logFlusher      drains this execution's buffered job log entries to the repository;
     *                        retained so {@link #shutdown()} can take a cutoff snapshot of an
     *                        execution that is still in flight
     * @param completionLatch one-shot latch naming the site that owns this execution's completion;
     *                        {@code true} once claimed. Retained here so {@link #shutdown()} can
     *                        claim it and neutralize a reply or timeout callback that is already
     *                        queued on the event loop
     */
    private record ExecutionResources(
            long timeoutId,
            long progressFlushId,
            MessageConsumer<?> cancelConsumer,
            JobLogFlusher logFlusher,
            AtomicBoolean completionLatch) {}

    private final Vertx vertx;
    private final EventBusClient eventBusClient;
    private final JobRepository repository;
    private final List<JobInterceptor> interceptors;
    private final long executionTimeoutMs;
    private final long progressFlushIntervalMs;
    private final String nodeId;
    private final dev.vertique.context.DispatchEnvelopeBuilder envelopeBuilder;

    /** Tracks active completion consumers so they can be unregistered on {@link #shutdown()}. */
    private final Set<MessageConsumer<?>> activeConsumers = ConcurrentHashMap.newKeySet();

    /**
     * Tracks per-execution resources (timeout timer, progress-flush timer, cancel consumer, log
     * flusher) for cleanup when the consumer replies, the timeout fires, or the dispatcher shuts
     * down.
     */
    private final Map<UUID, ExecutionResources> activeExecutions = new ConcurrentHashMap<>();

    /**
     * Creates a new dispatcher with the given configuration.
     *
     * <p>Requires the {@link dev.vertique.context.DispatchEnvelopeBuilder} used to construct
     * outgoing envelopes through the context-substrate capturer (FR-CTX-015). Currently bound
     * {@code ContextHolder} values flow into the dispatch envelope via registered
     * {@code ServiceDispatchContextEncoder}s.
     *
     * @param vertx                   the Vert.x instance for timer management and event bus
     * @param eventBusClient          the event bus client used for fire-and-forget dispatch
     * @param repository              optional job repository for completion persistence and progress
     *                                flushing; may be {@code null} for in-memory mode
     * @param interceptors            job interceptors to invoke around each dispatch, already sorted
     *                                by priority
     * @param executionTimeoutMs      per-execution timeout in milliseconds; {@code 0} disables
     * @param progressFlushIntervalMs interval for flushing progress snapshots; {@code 0} disables
     * @param nodeId                  stable node identifier for attribution
     * @param envelopeBuilder         builder for outgoing dispatch envelopes; must not be
     *                                {@code null} (FR-CTX-015)
     */
    CronJobDispatcher(
            Vertx vertx,
            EventBusClient eventBusClient,
            JobRepository repository,
            List<JobInterceptor> interceptors,
            long executionTimeoutMs,
            long progressFlushIntervalMs,
            String nodeId,
            dev.vertique.context.DispatchEnvelopeBuilder envelopeBuilder) {
        this.vertx = vertx;
        this.eventBusClient = eventBusClient;
        this.repository = repository;
        this.interceptors = interceptors;
        this.executionTimeoutMs = executionTimeoutMs;
        this.progressFlushIntervalMs = progressFlushIntervalMs;
        this.nodeId = nodeId;
        this.envelopeBuilder =
                java.util.Objects.requireNonNull(envelopeBuilder, "envelopeBuilder must not be null (FR-CTX-015)");
    }

    /**
     * Dispatches the job to the handler via the event bus and registers a one-time completion
     * consumer on the reply address. On completion, fires interceptors, persists the result if
     * the execution was tracked, and invokes the given {@code completionCallback}.
     *
     * <p>MDC context is populated in the dispatched {@link DispatchEnvelope} (restored automatically by the
     * handler side) and also set on the scheduler side for correlated logging.
     *
     * <p>Cron does not retry: if the execution fails, it is recorded as {@link JobState#FAILED}
     * for dashboard visibility. The next scheduled fire will produce a fresh execution.
     *
     * <p>The target address is <b>not</b> resolved here: the scheduler resolves it once per fire —
     * at fire admission, before any execution record is written — and hands it in, so the address
     * a tracked execution records is by construction the address dispatched to.
     *
     * <p><b>This method does not throw.</b> Everything after the execution id — envelope
     * construction, every per-execution resource registration, and the event-bus send — runs inside
     * one contained region whose {@code catch} rolls the registrations back, best-effort marks a
     * tracked execution {@link JobState#FAILED}, drains the job log buffer, and invokes
     * {@code completionCallback} from a {@code finally}. Callers therefore never have to release the
     * in-flight guard and concurrency slot themselves for a synchronous failure of this method, and
     * must not: doing so on top of the release below would be a double release. Only an
     * {@link Error} escapes — deliberately, because it leaves the JVM in an undefined state.
     *
     * @param job                the cron job to dispatch
     * @param scheduledAt        the time this execution was scheduled for
     * @param execution          the persisted execution record, or {@code null} if not tracked
     * @param effectiveAddress   the resolved, non-blank event bus address to send to
     * @param completionCallback callback to invoke when this execution ends
     */
    void dispatch(
            CronJobDefinition job,
            Instant scheduledAt,
            JobExecution execution,
            String effectiveAddress,
            CompletionCallback completionCallback) {
        final UUID executionId = execution != null ? execution.id() : UUID.randomUUID();

        // --- One contained region: latch, then every resource, then the send ---

        // The completion latch is created before this method registers anything, and every
        // statement from here through eventBusClient.send() sits inside the single try/catch below.
        // That ordering is what forecloses a double release. Consider the hazard it replaces: the
        // execution-timeout timer is armed part-way through the region, so if a throw after the arm
        // could escape uncontained, the caller would release the guards *and* the timer would later
        // fire and release them again — driving activeConcurrentCount negative and over-admitting
        // jobs for the process lifetime. Here nothing between the latch and the send can escape, and
        // the catch claims the latch before releasing, so the timer callback and the reply consumer
        // both fail their own claim and become no-ops. The complementary half of the invariant is in
        // CronConcurrencyManager.acquireSlotAndRun: it only ever sees an exception from *before*
        // this latch existed (i.e. one that released nothing), because the region never rethrows.
        final AtomicBoolean completionLatch = new AtomicBoolean();

        // Rollback ledger: one entry per resource, pushed as that resource is created, so the catch
        // undoes exactly what exists — newest first (ArrayDeque.push + iteration order is LIFO). A
        // ledger rather than a set of sentinel copies because a sentinel that is mirrored one line
        // too late silently under-rolls-back, while a missing push cannot be hidden: the resource
        // and its undo are created in the same statement pair.
        final Deque<Runnable> rollback = new ArrayDeque<>();

        // Mirrored out of the region so the catch can honour the ending-path invariant (logs flush
        // on *every* path that ends the execution) for a synchronously failed dispatch too.
        JobLogFlusher createdLogFlusher = null;

        try {
            Instant startedAt = Instant.now();
            DefaultJobContext jobContext = new DefaultJobContext(job.id(), executionId, 0, JobType.CRON);
            JobDispatchContext dispatchCtx = new JobDispatchContext(
                    job.id(),
                    executionId,
                    JobType.CRON,
                    0,
                    job.maxAttempts(),
                    "cron",
                    scheduledAt,
                    startedAt,
                    job.parameters(),
                    Map.of());

            Map<String, String> mdc = dispatchCtx.toMdcContext();

            // The cron dispatcher runs on the verticle's deployment context (not a duplicated
            // context), so the framework MDC holder cannot be written. Compose the wire-format MDC
            // value into callerOverrides via MDCContexts.holderKey() / holderValue() so the entries
            // ride through the dispatch-context carrier map without touching the holder. The helper
            // pair is the single source of truth for the carrier key + value type — using literals
            // here would risk silent drift if either ever changes.
            Map<String, Object> ctx = new java.util.HashMap<>();
            ctx.put(JobContext.class.getName(), jobContext);
            ctx.put(JobDispatchContext.class.getName(), dispatchCtx);
            // Provenance proving this is deferred (cron) execution so the receive-side identity
            // reconstruction initializer may mint a bounded SYSTEM context (W2/A6). Keyed by FQCN
            // like the JobDispatchContext entry; reinstated before InboundContextInitializers run.
            ctx.put(DeferredExecutionOrigin.class.getName(), DeferredExecutionOrigin.of("cron", job.id()));
            if (!mdc.isEmpty()) {
                ctx.put(
                        dev.vertique.logging.MDCContexts.holderKey(),
                        dev.vertique.logging.MDCContexts.holderValue(mdc));
            }

            String replyAddress = "job.completions." + executionId;
            // FR-CTX-015: always route through DispatchEnvelopeBuilder so registered
            // ServiceDispatchContextEncoders capture currently-bound holder values (including the
            // built-in DurablePropagationMetadata encoder). The builder is required (non-null) per
            // the constructor contract. Inside the region because a registered encoder is
            // application code and may throw: no resource exists yet, so the catch simply releases.
            DispatchEnvelope<?> body = envelopeBuilder.build(
                    job.payload(), ctx, dev.vertique.core.context.DispatchBoundary.SERVICE_DISPATCH, replyAddress);

            // --- Per-execution resources: cancel consumer, progress-flush timer, timeout timer ---

            // Cooperative cancel: sets the cancelled flag on the JobContext
            final MessageConsumer<?> cancelConsumer = vertx.eventBus().consumer("job.cancel." + executionId, msg -> {
                jobContext.setCancelled(true);
                log.info("Cancel requested for cron job '{}' execution {}", job.id(), executionId);
            });
            rollback.push(cancelConsumer::unregister);

            // Job log flush: drains the context's buffered log entries to job_logs. Constructed
            // OUTSIDE the progressFlushIntervalMs guard on purpose — the ending sites below flush
            // through it too, so building it inside the guard would mean a 0 interval (periodic
            // flush disabled) silently made job logs non-durable rather than merely less timely.
            //
            // The id is execution.id(), NOT the local `executionId` above: for an untracked fire the
            // latter is a freshly minted UUID with no job_executions row, and job_logs.execution_id
            // references that table. Passing null instead makes the flusher a genuine no-op.
            final JobLogFlusher logFlusher =
                    new JobLogFlusher(repository, execution != null ? execution.id() : null, jobContext);
            createdLogFlusher = logFlusher;

            // Progress flush: periodically write changed snapshots to the repository
            final ProgressSnapshot[] lastFlushed = {ProgressSnapshot.EMPTY};
            final long progressFlushId = (execution != null && repository != null && progressFlushIntervalMs > 0)
                    ? vertx.setPeriodic(progressFlushIntervalMs, id -> {
                        ProgressSnapshot current = jobContext.progress().snapshot();
                        if (!current.equals(lastFlushed[0])) {
                            lastFlushed[0] = current;
                            repository
                                    .heartbeat(executionId, current)
                                    .onFailure(err -> log.debug(
                                            "Progress flush failed for '{}': {}", job.id(), err.getMessage()));
                        }
                        // Unconditional: log entries change independently of the progress snapshot,
                        // so gating this on the snapshot-changed check would strand the logs of any
                        // job that logs without reporting progress. The flusher is itself a no-op
                        // when nothing is buffered.
                        logFlusher.flush();
                    })
                    : -1L;
            if (progressFlushId != -1L) {
                rollback.push(() -> vertx.cancelTimer(progressFlushId));
            }

            // Execution timeout: fires if handler never replies
            final MessageConsumer<?> consumer = vertx.eventBus().consumer(replyAddress);
            activeConsumers.add(consumer);
            rollback.push(() -> {
                activeConsumers.remove(consumer);
                consumer.unregister();
            });

            final long timeoutId = (executionTimeoutMs > 0)
                    ? vertx.setTimer(executionTimeoutMs, id -> {
                        if (!completionLatch.compareAndSet(false, true)) {
                            // The reply consumer, the containment below, or shutdown() already owns
                            // this execution's completion — releasing again would double-release.
                            return;
                        }
                        try {
                            try {
                                consumer.unregister();
                                activeConsumers.remove(consumer);
                                cancelConsumer.unregister();
                                if (progressFlushId != -1L) {
                                    vertx.cancelTimer(progressFlushId);
                                }
                                activeExecutions.remove(executionId);

                                log.error(
                                        "Cron job '{}' execution {} timed out after {}ms — marking ABANDONED",
                                        job.id(),
                                        executionId,
                                        executionTimeoutMs);
                                if (execution != null && repository != null) {
                                    // Own try/catch: JobRepository is an application-implemented SPI
                                    // and may throw synchronously instead of returning a failed
                                    // future. The latch is claimed and both consumers are already
                                    // unregistered by this point, so an escaping throw would leave
                                    // nothing able to complete this execution — the in-flight guard
                                    // and the slot would be stranded for the process lifetime.
                                    try {
                                        repository
                                                .completeExecution(
                                                        executionId,
                                                        JobState.ABANDONED,
                                                        "Execution timeout",
                                                        "ExecutionTimeoutException",
                                                        jobContext.progress().snapshot())
                                                .onFailure(err -> log.warn(
                                                        "Failed to mark cron execution {} ABANDONED on timeout: {}",
                                                        executionId,
                                                        err.getMessage()));
                                    } catch (Exception persistFailure) {
                                        log.warn(
                                                "Failed to mark cron execution {} ABANDONED on timeout"
                                                        + " — repository threw synchronously",
                                                executionId,
                                                persistFailure);
                                    }
                                }
                            } finally {
                                // The single release path for this execution: kept in a finally so
                                // nothing above — including a repository that throws in a way the
                                // guard above did not anticipate — can strand the guards.
                                completionCallback.onCompleted(job);
                            }
                        } finally {
                            // The timeout ends this execution and the progress tick above is
                            // already cancelled, so this is the last chance to persist: drain()
                            // rather than flush(), because a plain flush would claim nothing while
                            // a tick write is still outstanding and no later tick would re-drain.
                            // Deliberately last: nothing that ends the execution — the ABANDONED
                            // transition or the completion callback — may sit behind the drain, and
                            // the finally keeps the drain reachable if that work throws. The
                            // handler is not interrupted and may keep appending; the drain's round
                            // cap is what stops that from holding this site open indefinitely.
                            logFlusher.drain();
                        }
                    })
                    : -1L;
            if (timeoutId != -1L) {
                rollback.push(() -> vertx.cancelTimer(timeoutId));
            }

            // Track resources for cleanup on shutdown() or early reply
            activeExecutions.put(
                    executionId,
                    new ExecutionResources(timeoutId, progressFlushId, cancelConsumer, logFlusher, completionLatch));
            rollback.push(() -> activeExecutions.remove(executionId));

            // --- Completion consumer ---

            consumer.handler(msg -> {
                if (!completionLatch.compareAndSet(false, true)) {
                    // The execution timeout (or shutdown()) already ended this execution and
                    // released the guards; this reply lost the race and is dropped.
                    log.debug(
                            "Reply for cron job '{}' execution {} ignored — execution already completed",
                            job.id(),
                            executionId);
                    return;
                }
                // Everything from the successful claim to the completion callback is contained: the
                // claim has already made this the only site that can release, so a throw in the
                // cleanup below would otherwise strand both guards with no recovery path.
                try {
                    try {
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

                        // Restore MDC for correlated completion logging. MDCContexts.bindAll
                        // snapshots prior per-key state at install time and restores it (including
                        // absence) on close — using it via try-with-resources gives us LIFO unwind
                        // without manual remove() bookkeeping. The event-bus consumer callback runs
                        // on a duplicated Vert.x context so the framework MDC write-guard accepts
                        // the bind.
                        try (ContextHolder.Scope mdcScope = MDCContexts.bindAll(mdc)) {
                            // Extract result and fire interceptors
                            Result<?> result = null;
                            if (msg.body() instanceof DispatchEnvelope<?> replyBody
                                    && replyBody.payload() instanceof Result<?> r) {
                                result = r;
                            }

                            JobInterceptors.fireOnComplete(interceptors, dispatchCtx, result, startedAt, endTime, log);

                            // Persist completion and update fire times if tracked
                            if (execution != null && repository != null) {
                                persistCompletion(job, execution, result);
                                updateFireTimes(job, scheduledAt);
                            }

                            // Log the completion result
                            if (result != null && result.isFailure()) {
                                log.warn(
                                        "Cron job '{}' execution {} failed: {}",
                                        job.id(),
                                        executionId,
                                        result.cause().getMessage());
                            } else {
                                log.debug("Cron job '{}' execution {} completed successfully", job.id(), executionId);
                            }
                        }
                    } finally {
                        completionCallback.onCompleted(job);
                    }
                } finally {
                    // The handler has reported, so nothing more will be appended: this drains
                    // whatever the periodic tick had not yet claimed. drain(), not flush() — the
                    // tick timer is cancelled above, so a claim that came back empty because a tick
                    // write was still outstanding would strand those entries with nothing left to
                    // re-drain them. Fire-and-forget, and deliberately last — no work that ends the
                    // execution may sit behind it, and the finally keeps the drain reachable even
                    // when completion handling throws (JobLogFlusher itself never throws and always
                    // returns a succeeded future).
                    logFlusher.drain();
                }
            });

            // Enrich the scheduler thread's SLF4J MDC for correlated dispatch logging. The framework
            // MDC facade requires a duplicated Vert.x context (write-side guard), which this
            // scheduler path is not. SchedulerMdcScope captures prior MDC values per key so cleanup
            // restores (rather than blindly removes) the caller's pre-enrichment state. The
            // receive-side ServiceMethodInvoker installs the envelope MDC into the unified holder on
            // its own duplicated context — this enrichment is purely for the scheduler-thread log
            // lines below.
            try (dev.vertique.job.SchedulerMdcScope scope = dev.vertique.job.SchedulerMdcScope.install(mdc)) {
                JobInterceptors.fireOnDispatch(interceptors, dispatchCtx, log);
                log.debug("Dispatching cron job '{}' execution {}", job.id(), executionId);
                eventBusClient.send(effectiveAddress, body);
            }
        } catch (Exception e) {
            // The helper releases the guards from a finally of its own, so nothing it does above the
            // release can prevent it. Deliberately not a finally here: on the success path the
            // execution is still running and completion belongs to the reply consumer or the
            // timeout timer.
            containSynchronousFailure(
                    job, execution, executionId, completionLatch, rollback, createdLogFlusher, completionCallback, e);
        }
    }

    /**
     * Contains a synchronous failure of {@link #dispatch}: claims completion, rolls back exactly the
     * per-execution resources that were created, best-effort marks a tracked execution
     * {@link JobState#FAILED}, drains the job log buffer, and releases the scheduler's guards.
     *
     * <p>Never throws — except the release itself, which is the caller's single release path and is
     * therefore invoked from a {@code finally} so no failure above it can strand the in-flight guard
     * and concurrency slot. An {@link Error} still propagates, consistent with this module's rule
     * that an {@code Error} leaves the JVM in an undefined state.
     *
     * @param job                the cron job whose dispatch failed
     * @param execution          the persisted execution record, or {@code null} if not tracked
     * @param executionId        the id of the execution being abandoned
     * @param completionLatch    the execution's one-shot completion latch
     * @param rollback           undo actions for the resources created before the failure, newest
     *                           first
     * @param logFlusher         the execution's log flusher, or {@code null} if the failure happened
     *                           before it was created
     * @param completionCallback the scheduler callback that releases the in-flight guard and the
     *                           concurrency slot
     * @param cause              the synchronous failure
     */
    private void containSynchronousFailure(
            CronJobDefinition job,
            JobExecution execution,
            UUID executionId,
            AtomicBoolean completionLatch,
            Deque<Runnable> rollback,
            JobLogFlusher logFlusher,
            CompletionCallback completionCallback,
            Exception cause) {
        if (!completionLatch.compareAndSet(false, true)) {
            // Another site already owns this execution's completion and will release the guards
            // exactly once. Releasing here too is the double release this latch exists to prevent.
            log.error(
                    "Cron job '{}' execution {} failed synchronously after completion was already claimed"
                            + " — guards are released by the owning site",
                    CronScheduler.forLog(job.id()),
                    executionId,
                    cause);
            return;
        }
        log.error(
                "Cron job '{}' execution {} failed synchronously during dispatch — rolling back and releasing"
                        + " the in-flight guard and concurrency slot",
                CronScheduler.forLog(job.id()),
                executionId,
                cause);
        try {
            for (Runnable undo : rollback) {
                // Each undo is guarded on its own so one failing cancel or unregister cannot skip
                // the rest of the rollback.
                try {
                    undo.run();
                } catch (Exception cleanupFailure) {
                    log.warn(
                            "Rollback step after the synchronous dispatch failure of cron job '{}' failed: {}",
                            CronScheduler.forLog(job.id()),
                            cleanupFailure.getMessage());
                }
            }
            if (execution != null && repository != null) {
                // Best effort, in its own try: JobRepository is an application-implemented SPI and
                // may throw synchronously. Without this the row would stay PROCESSING forever.
                try {
                    repository
                            .completeExecution(
                                    executionId,
                                    JobState.FAILED,
                                    cause.getMessage(),
                                    cause.getClass().getName(),
                                    ProgressSnapshot.EMPTY)
                            .onFailure(err -> log.warn(
                                    "Failed to mark cron execution {} FAILED after a synchronous dispatch"
                                            + " failure: {}",
                                    executionId,
                                    err.getMessage()));
                } catch (Exception persistFailure) {
                    log.warn(
                            "Failed to mark cron execution {} FAILED after a synchronous dispatch failure"
                                    + " — repository threw synchronously",
                            executionId,
                            persistFailure);
                }
            }
            if (logFlusher != null) {
                // Same ending-path invariant the reply and timeout handlers honour: this path ends
                // the execution and the periodic tick (if one was armed) has just been cancelled by
                // the rollback, so drain() rather than flush(). Without it a synchronously failed
                // dispatch would silently drop whatever the handler had already buffered.
                logFlusher.drain();
            }
        } finally {
            completionCallback.onCompleted(job);
        }
    }

    /**
     * Cleans up all active execution resources: unregisters completion consumers, cancels timeout
     * timers, cancels progress-flush timers, unregisters cancel consumers, and takes a final
     * cutoff snapshot of each in-flight execution's job logs. Should be called when the scheduler
     * stops.
     *
     * <p>Also claims each in-flight execution's completion latch, so a reply or timeout callback
     * that is already queued on the event loop cannot release a concurrency slot after
     * {@link CronScheduler#stop()} has reset the counters.
     *
     * <p><b>The shutdown flush is a cutoff snapshot, not a final flush.</b> In-flight executions
     * are not interrupted, so a handler may keep logging after its buffer is drained here; those
     * later entries are lost. The snapshot bounds what a graceful shutdown loses — it does not
     * eliminate loss.
     *
     * <p>It goes through {@link dev.vertique.job.JobLogFlusher#drain()} rather than a single flush:
     * the progress-flush timer is cancelled just above, so a claim that came back empty because a
     * tick write was still outstanding would make the "snapshot" contain nothing at all.
     *
     * @return a future that succeeds once every cutoff drain has settled or hit its per-execution
     *     timeout bound; never fails
     */
    Future<Void> shutdown() {
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
            // Claim completion so a reply or timeout callback already queued on the event loop
            // cannot invoke the scheduler's completion callback after CronScheduler.stop() has reset
            // the concurrency counters — that release would drive the active count negative. Before
            // the dedicated latch existed this was implicit in the activeConsumers.clear() above,
            // which the timeout handler used as its latch.
            resources.completionLatch().set(true);
            if (resources.timeoutId() != -1L) {
                vertx.cancelTimer(resources.timeoutId());
            }
            if (resources.progressFlushId() != -1L) {
                vertx.cancelTimer(resources.progressFlushId());
            }
            resources.cancelConsumer().unregister();
        }
        activeExecutions.clear();

        // joinAllSwallow waits for every drain to settle without short-circuiting and always
        // succeeds — the all-settled-swallow contract this shutdown needs. The timeout bound stays
        // inside the hook: JobLogFlusher.flush() already bounds each write, so this is not what
        // rescues an unsettleable future. What it buys is collapsing the drain's whole round
        // budget — and its await of an already-outstanding write — into a single bound on undeploy.
        return Combinators.joinAllSwallow(
                pending,
                resources -> resources.logFlusher().drain().timeout(SHUTDOWN_FLUSH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                (resources, err) -> log.warn("Shutdown job-log flush did not settle: {}", err.getMessage()));
    }

    /**
     * Persists the terminal state of a tracked execution to the repository. Failures are logged as
     * warnings — completion persistence is best-effort and does not affect job cleanup.
     *
     * @param job       the cron job that completed
     * @param execution the persisted execution record
     * @param result    the dispatch result, or {@code null} if no reply body was received
     */
    private void persistCompletion(CronJobDefinition job, JobExecution execution, Result<?> result) {
        JobState terminalState = (result != null && result.isFailure()) ? JobState.FAILED : JobState.SUCCEEDED;
        String errorMessage = (result != null && result.isFailure() && result.cause() != null)
                ? result.cause().getMessage()
                : null;
        String errorType = (result != null && result.isFailure() && result.cause() != null)
                ? result.cause().getClass().getName()
                : null;
        repository
                .completeExecution(execution.id(), terminalState, errorMessage, errorType, ProgressSnapshot.EMPTY)
                .onFailure(err -> log.warn("Failed to persist completion for '{}': {}", job.id(), err.getMessage()));
    }

    /**
     * Updates the schedule's last-fired and next-fire timestamps in the repository. Failures are
     * logged at debug level because they do not affect job execution.
     *
     * @param job     the cron job that just fired
     * @param firedAt the instant at which the job fired
     */
    private void updateFireTimes(CronJobDefinition job, Instant firedAt) {
        if (repository == null) {
            return;
        }
        try {
            Instant nextFire = job.cronExpression().computeNextFireTime(Instant.now(), job.timezone());
            repository
                    .updateScheduleFireTimes(job.id(), firedAt, nextFire)
                    .onFailure(err -> log.debug("Failed to update fire times for '{}': {}", job.id(), err));
        } catch (Exception e) {
            log.debug("Failed to compute next fire time for '{}': {}", job.id(), e.getMessage());
        }
    }
}
