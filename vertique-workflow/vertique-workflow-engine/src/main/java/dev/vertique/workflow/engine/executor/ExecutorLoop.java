// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.engine.spi.ClaimedCommand;
import dev.vertique.workflow.engine.spi.CommandHead;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.internal.ContextInternal;
import io.vertx.sqlclient.SqlClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
 * <p>All state is touched on the instance's event loop. Instances share nothing but the database: the execution lock,
 * the claim token and the lease coordinate them.
 */
final class ExecutorLoop {

    private static final Logger log = LoggerFactory.getLogger(ExecutorLoop.class);

    /** The pause before a pass that follows work, in milliseconds. */
    private static final long IMMEDIATE_MS = 1;

    private final Vertx vertx;
    private final ContextInternal root;
    private final WorkflowExecutorConfig config;
    private final String instanceId;
    private final CommandPeek peek;
    private final WorkflowCommandRepository<SqlClient> commands;
    private final SegmentRunner segmentRunner;
    private final AtomicInteger inFlight = new AtomicInteger();

    private volatile boolean running;
    private volatile boolean passing;
    private volatile boolean kicked;
    private volatile long timerId = -1;
    private volatile Promise<Void> stopped;

    ExecutorLoop(
            Vertx vertx,
            ContextInternal root,
            WorkflowExecutorConfig config,
            String instanceId,
            CommandPeek peek,
            WorkflowCommandRepository<SqlClient> commands,
            SegmentRunner segmentRunner) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.root = Objects.requireNonNull(root, "root");
        this.config = Objects.requireNonNull(config, "config");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
        this.peek = Objects.requireNonNull(peek, "peek");
        this.commands = Objects.requireNonNull(commands, "commands");
        this.segmentRunner = Objects.requireNonNull(segmentRunner, "segmentRunner");
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
        Promise<Void> promise = stopped;
        if (promise == null) {
            promise = Promise.promise();
            stopped = promise;
        }
        completeStopIfIdle();
        return promise.future();
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
            } finally {
                passing = false;
                schedule(next);
            }
        });
    }

    /** Peeks and claims; completes with the number of commands claimed. */
    private Future<Integer> pass() {
        if (!running) {
            return Future.succeededFuture(0);
        }
        int free = config.concurrency() - inFlight.get();
        if (free <= 0) {
            return Future.succeededFuture(0);
        }
        return peek.resident(free).compose(heads -> {
            List<Future<Boolean>> claims = new ArrayList<>();
            for (CommandHead head : heads) {
                if (!running || inFlight.get() >= config.concurrency()) {
                    break;
                }
                inFlight.incrementAndGet();
                claims.add(claim(head));
            }
            return Future.all(claims).map(ignored -> {
                int claimed = 0;
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
    private Future<Boolean> claim(CommandHead head) {
        long claimStartNanos = System.nanoTime();
        Future<Optional<ClaimedCommand>> claimed;
        try {
            claimed = Objects.requireNonNull(
                    commands.claim(head, instanceId, config.leaseSeconds()), "claim returned null instead of a future");
        } catch (Exception | LinkageError | AssertionError e) {
            claimed = Future.failedFuture(e);
        }
        return claimed.map(result -> {
                    if (result == null || result.isEmpty()) {
                        release();
                        return false;
                    }
                    startSegment(head, result.get(), claimStartNanos);
                    return true;
                })
                .recover(failure -> {
                    log.warn(
                            "Executor {}: a claim failed: {}",
                            instanceId,
                            failure.getClass().getName());
                    log.debug("Executor {}: claim failure", instanceId, failure);
                    release();
                    return Future.succeededFuture(false);
                });
    }

    private void startSegment(CommandHead head, ClaimedCommand claim, long claimStartNanos) {
        ContextInternal segmentContext = root.duplicate();
        segmentContext.runOnContext(ignored -> {
            Future<Void> done;
            try {
                done = Objects.requireNonNull(
                        segmentRunner.run(head, claim, claimStartNanos),
                        "the segment returned null instead of a future");
            } catch (Exception | LinkageError | AssertionError e) {
                done = Future.failedFuture(e);
            }
            done.onComplete(result -> {
                if (result.failed()) {
                    log.warn(
                            "Executor {}: a segment ended with a failure: {}",
                            instanceId,
                            result.cause().getClass().getName());
                }
                release();
                if (running) {
                    kick();
                }
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
