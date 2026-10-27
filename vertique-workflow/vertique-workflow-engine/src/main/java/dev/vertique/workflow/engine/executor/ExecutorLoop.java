// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.engine.executor.WorkflowSegments.Warm.Marks;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.CommandSettled;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.LeaseExpired;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.SqlClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: the peek, claim and segment loop of one executor instance.
 *
 * <p>Each pass is one one-shot timer: it peeks the commands that could be claimed, claims as many as there are free
 * slots and starts a segment for each claim on a fresh duplicate of the instance's own context. A pass that peeks,
 * claims or starts a segment with a failure, a synchronous throw or no future is logged at warn level, with no
 * payload, and the next pass is scheduled all the same, exactly once per pass, from a {@code finally}. An
 * {@link Error} the loop does not handle propagates after the next pass is scheduled.
 *
 * <p>A wake-up hint from the command source makes the next pass run now. On every wake and poll, the loop asks the
 * repository for the database-relative delay to the earliest pending or deferred command and keeps one monotonic timer
 * for it. This scheduling read is separate from the bounded diagnostic command list. A wake-up never claims; it only
 * asks for a pass, and repeated wake-ups coalesce. Polling every poll interval stays the fallback.
 *
 * <p>Hint-driven passes are spaced: a wake-up that arrives within {@link #DEFAULT_HINT_SPACING_MS} of the previous
 * one that was acted on neither refreshes the earliest delay nor asks for a pass at once. The burst is answered by one
 * pass at the end of the window, so a flood of notifications costs a bounded number of passes and reads.
 *
 * <p>All state is touched on the instance's event loop. Instances share nothing but the database: the execution lock,
 * the claim token and the lease coordinate them.
 */
final class ExecutorLoop {

    private static final Logger log = LoggerFactory.getLogger(ExecutorLoop.class);

    /** The pause before a pass that follows work, in milliseconds. */
    private static final long IMMEDIATE_MS = 1;

    private static final long NO_DELAY = -1;

    /** The least time between two hint-driven passes of one instance, in milliseconds. */
    static final long DEFAULT_HINT_SPACING_MS = 50;

    private final Vertx vertx;
    private final ContextInternal root;
    private final WorkflowExecutorConfig config;
    private final String instanceId;
    private final CommandPeek peek;
    private final WorkflowCommandRepository<SqlClient> commands;
    private final SegmentRunner segmentRunner;
    private final WorkflowSegments segments;
    private final ExecutorEvents events;
    private final StuckReporter reporter;
    private final long hintSpacingMs;
    private final AtomicInteger inFlight = new AtomicInteger();

    private volatile boolean running;
    private volatile boolean passing;
    private volatile boolean kicked;
    private volatile long timerId = -1;
    private volatile Promise<Void> stopped;

    // touched on the instance's event loop only
    private long earliestTimerId = -1;
    private long earliestDelayMs = NO_DELAY;
    private long earliestScheduledAtNanos;
    private long hints;
    private boolean refreshing;
    private long lastHintNanos;
    private boolean hintActed;
    private long hintTimerId = -1;

    ExecutorLoop(
            Vertx vertx,
            ContextInternal root,
            WorkflowExecutorConfig config,
            String instanceId,
            CommandPeek peek,
            WorkflowCommandRepository<SqlClient> commands,
            SegmentRunner segmentRunner,
            WorkflowSegments segments,
            ExecutorEvents events,
            StuckReporter reporter) {
        this(
                vertx,
                root,
                config,
                instanceId,
                peek,
                commands,
                segmentRunner,
                segments,
                events,
                reporter,
                DEFAULT_HINT_SPACING_MS);
    }

    ExecutorLoop(
            Vertx vertx,
            ContextInternal root,
            WorkflowExecutorConfig config,
            String instanceId,
            CommandPeek peek,
            WorkflowCommandRepository<SqlClient> commands,
            SegmentRunner segmentRunner,
            WorkflowSegments segments,
            ExecutorEvents events,
            StuckReporter reporter,
            long hintSpacingMs) {
        this.hintSpacingMs = hintSpacingMs;
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.root = Objects.requireNonNull(root, "root");
        this.config = Objects.requireNonNull(config, "config");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.peek = Objects.requireNonNull(peek, "peek");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.segmentRunner = Objects.requireNonNull(segmentRunner, "segmentRunner");
        this.segments = Objects.requireNonNull(segments, "segments");
        this.events = Objects.requireNonNull(events, "events");
        this.reporter = Objects.requireNonNull(reporter, "reporter");
    }

    /** Starts looking for commands: the first pass runs after one poll interval. */
    void start() {
        running = true;
        schedule(config.pollIntervalMs());
    }

    /**
     * Ends claiming and completes once every segment that was in flight has committed or been rolled back by its own
     * deadline. No claim starts after this call.
     *
     * @return a future that completes when the instance is idle
     */
    Future<Void> stop() {
        running = false;
        cancelTimer();
        cancelEarliest();
        cancelHintTimer();
        Promise<Void> promise = stopped;
        if (promise == null) {
            promise = Promise.promise();
            stopped = promise;
        }
        completeStopIfIdle();
        return promise.future();
    }

    /**
     * Takes a wake-up hint: a command became pending or deferred. Asks for a pass now and refreshes the database-relative
     * timer. The notification timestamp is not compared with the JVM clock. Must be called on the instance's own
     * context.
     *
     * @param availableAtEpochMs the notification's available-time hint in epoch milliseconds
     */
    void wake(long availableAtEpochMs) {
        if (!running) {
            return;
        }
        long now = System.nanoTime();
        if (hintActed) {
            long remainingMs = hintSpacingMs - TimeUnit.NANOSECONDS.toMillis(Math.max(0, now - lastHintNanos));
            if (remainingMs > 0) {
                deferHint(remainingMs);
                return;
            }
        }
        actOnHint(now);
    }

    private void actOnHint(long nowNanos) {
        hintActed = true;
        lastHintNanos = nowNanos;
        hints++;
        refreshEarliest();
        kick();
    }

    /** Answers the hints of a burst with one pass when the spacing window ends. */
    private void deferHint(long delayMs) {
        if (hintTimerId >= 0) {
            return;
        }
        try {
            // raw timer: one-shot, at most one pending, ends the spacing window of a burst of hints
            hintTimerId = vertx.setTimer(delayMs, id -> {
                hintTimerId = -1;
                if (running) {
                    actOnHint(System.nanoTime());
                }
            });
        } catch (RuntimeException e) {
            log.debug("Executor {}: no hint pass could be scheduled", instanceId, e);
        }
    }

    private void cancelHintTimer() {
        if (hintTimerId >= 0) {
            vertx.cancelTimer(hintTimerId);
        }
        hintTimerId = -1;
    }

    // --- Passes ---

    private void schedule(long delayMs) {
        if (!running) {
            return;
        }
        try {
            // raw timer: one-shot pass timer whose next delay depends on the result of the pass
            timerId = vertx.setTimer(delayMs, id -> {
                if (running) {
                    poll();
                }
            });
        } catch (RuntimeException e) {
            // Vert.x is closing: no timer can be set and the loop ends with it
            running = false;
            log.debug("Executor {}: no next pass could be scheduled", instanceId, e);
            completeStopIfIdle();
        }
    }

    private void cancelTimer() {
        long id = timerId;
        if (id >= 0) {
            vertx.cancelTimer(id);
            timerId = -1;
        }
    }

    private void poll() {
        passing = true;
        kicked = false;
        Future<Integer> pass;
        try {
            pass = Objects.requireNonNull(pass(), "the pass returned null instead of a future");
        } catch (Exception | LinkageError | AssertionError e) {
            pass = Future.failedFuture(e);
        } catch (Throwable t) {
            passing = false;
            schedule(config.pollIntervalMs());
            throw t;
        }
        pass.onComplete(result -> {
            long next = config.pollIntervalMs();
            try {
                if (result.failed()) {
                    log.warn(
                            "Executor {}: a pass failed: {}",
                            instanceId,
                            result.cause().getClass().getName());
                    log.debug("Executor {}: pass failure", instanceId, result.cause());
                } else if (result.result() > 0 || kicked) {
                    next = IMMEDIATE_MS;
                }
                reporter.sampleIfDue();
                if (running) {
                    refreshEarliest();
                }
            } finally {
                passing = false;
                schedule(next);
            }
        });
    }

    // --- Earliest available command ---

    /** Reads one database-relative delay and keeps a timer for the earliest pending or deferred availability. */
    private void refreshEarliest() {
        if (refreshing) {
            return;
        }
        refreshing = true;
        long hintsBefore = hints;
        Future<Optional<Long>> read;
        try {
            read = Objects.requireNonNull(
                    commands.findEarliestAvailabilityDelayMs(),
                    "findEarliestAvailabilityDelayMs returned null instead of a future");
        } catch (Exception | LinkageError | AssertionError e) {
            read = Future.failedFuture(e);
        }
        read.onComplete(result -> {
            refreshing = false;
            if (result.failed()) {
                log.debug("Executor {}: the earliest availability delay could not be read", instanceId, result.cause());
                return;
            }
            if (!running) {
                return;
            }
            if (hints != hintsBefore) {
                // A notification arrived during the read; refresh against its committed database state.
                refreshEarliest();
                return;
            }
            Optional<Long> delay =
                    Objects.requireNonNull(result.result(), "findEarliestAvailabilityDelayMs completed with null");
            if (delay.isEmpty()) {
                cancelEarliest();
            } else {
                setEarliestDelay(delay.get());
            }
        });
    }

    /** Keeps at most one timer, replacing it only when the new monotonic deadline is earlier. */
    private void setEarliestDelay(long delayMs) {
        long delay = Math.max(IMMEDIATE_MS, delayMs);
        long now = System.nanoTime();
        if (earliestTimerId >= 0 && delay >= remainingEarliestDelayMs(now)) {
            return;
        }
        cancelEarliest();
        try {
            // The database-relative delay is scheduled against Vert.x's monotonic timer.
            earliestTimerId = vertx.setTimer(delay, id -> {
                earliestTimerId = -1;
                earliestDelayMs = NO_DELAY;
                if (running) {
                    kick();
                }
            });
            earliestDelayMs = delay;
            earliestScheduledAtNanos = now;
        } catch (RuntimeException e) {
            log.debug("Executor {}: no wake-up could be scheduled", instanceId, e);
        }
    }

    private long remainingEarliestDelayMs(long nowNanos) {
        long elapsedNanos = Math.max(0, nowNanos - earliestScheduledAtNanos);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(elapsedNanos);
        return Math.max(0, earliestDelayMs - elapsedMs);
    }

    private void cancelEarliest() {
        if (earliestTimerId >= 0) {
            vertx.cancelTimer(earliestTimerId);
        }
        earliestTimerId = -1;
        earliestDelayMs = NO_DELAY;
    }

    /** Peeks, defers and claims; completes with the number of commands claimed or deferred. */
    private Future<Integer> pass() {
        if (!running) {
            return Future.succeededFuture(0);
        }
        int free = config.concurrency() - inFlight.get();
        if (free <= 0) {
            return Future.succeededFuture(0);
        }
        return peek.resident(free).compose(peeked -> {
            List<Future<Boolean>> claims = new ArrayList<>();
            for (CommandPeek.WarmedHead warmed : peeked.heads()) {
                if (!running || inFlight.get() >= config.concurrency()) {
                    // not claimed in this pass: the plans warmed for it are not needed, and it is peeked again
                    warmed.release().run();
                    continue;
                }
                inFlight.incrementAndGet();
                claims.add(claim(warmed));
            }
            return Future.all(claims).map(ignored -> {
                // a deferral is work too: the next pass runs now and sees the heads behind the deferred ones
                int claimed = peeked.deferred();
                for (Future<Boolean> claim : claims) {
                    if (claim.result()) {
                        claimed++;
                    }
                }
                return claimed;
            });
        });
    }

    /** Claims a head and starts its segment; the future never fails and tells whether a claim was taken. */
    private Future<Boolean> claim(CommandPeek.WarmedHead warmed) {
        CommandHead head = warmed.head();
        Runnable releasePlans = warmed.release();
        Marks marks = warmed.marks();
        long claimStartNanos = System.nanoTime();
        Future<Optional<ClaimedCommand>> claimed;
        try {
            claimed = Objects.requireNonNull(
                    commands.claim(
                            head, instanceId, config.leaseSeconds(), config.maxAttempts(), segments::instanceFailed),
                    "claim returned null instead of a future");
        } catch (Exception | LinkageError | AssertionError e) {
            claimed = Future.failedFuture(e);
        }
        return claimed.map(result -> {
                    if (result == null || result.isEmpty()) {
                        releasePlans.run();
                        release();
                        return false;
                    }
                    ClaimedCommand claim = result.get();
                    if (claim.leaseLapsed()) {
                        events.emit(new LeaseExpired(claim.kind()));
                    }
                    if (!claim.isClaim()) {
                        // dead-lettered or settled by the claim itself: there is no segment to run
                        events.emit(new CommandSettled(
                                claim.kind(),
                                claim.result() == ClaimedCommand.Result.DEAD_LETTERED ? "DEAD_LETTERED" : "REJECTED",
                                Duration.ofNanos(System.nanoTime() - claimStartNanos)));
                        releasePlans.run();
                        release();
                        return true;
                    }
                    startSegment(head, claim, claimStartNanos, releasePlans, marks);
                    return true;
                })
                .recover(failure -> {
                    log.warn(
                            "Executor {}: a claim failed: {}",
                            instanceId,
                            failure.getClass().getName());
                    log.debug("Executor {}: claim failure", instanceId, failure);
                    releasePlans.run();
                    release();
                    return Future.succeededFuture(false);
                });
    }

    private void startSegment(
            CommandHead head, ClaimedCommand claim, long claimStartNanos, Runnable releasePlans, Marks marks) {
        ContextInternal segmentContext = root.duplicate();
        segmentContext.runOnContext(ignored -> {
            Future<Void> done;
            try {
                done = Objects.requireNonNull(
                        segmentRunner.run(head, claim, claimStartNanos, marks.failing()),
                        "the segment returned null instead of a future");
            } catch (Exception | LinkageError | AssertionError e) {
                done = Future.failedFuture(e);
            }
            done.onComplete(result -> {
                releasePlans.run();
                if (result.failed()) {
                    log.warn(
                            "Executor {}: a segment ended with a failure: {}",
                            instanceId,
                            result.cause().getClass().getName());
                }
                // the marks are written after the segment has ended, and never change its outcome
                marks.run().onComplete(written -> {
                    release();
                    if (running) {
                        kick();
                    }
                });
            });
        });
    }

    /** Frees a slot and completes a stop that waited for it. */
    private void release() {
        inFlight.decrementAndGet();
        completeStopIfIdle();
    }

    /** Asks for a pass soon: now when none is running, or right after the one that is. */
    private void kick() {
        try {
            root.runOnContext(ignored -> {
                if (!running) {
                    return;
                }
                if (passing) {
                    kicked = true;
                    return;
                }
                cancelTimer();
                schedule(IMMEDIATE_MS);
            });
        } catch (RuntimeException e) {
            log.debug("Executor {}: the loop could not be woken", instanceId, e);
        }
    }

    private void completeStopIfIdle() {
        Promise<Void> promise = stopped;
        if (promise != null && !running && inFlight.get() == 0) {
            promise.tryComplete();
        }
    }
}
