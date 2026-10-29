// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.config.WorkflowExecutorConfig;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.history.PendingCommand;
import dev.vertique.workflow.ops.WorkflowExecutionProbe;
import dev.vertique.workflow.ops.WorkflowInstanceId;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * INTERNAL: the {@link WorkflowExecutionProbe} over the read members of {@link WorkflowCommandRepository}.
 *
 * <p>The probe polls the repository until no command is runnable or the timeout elapses, pausing 25 ms after the first
 * read and longer after each following one, up to 250 ms, so a long wait does not re-run the eligibility scan at a
 * high rate. A timeout longer than ten minutes is shortened to ten minutes. The timeout is measured on the local
 * monotonic clock, eligibility on the database clock. When it completes it reads
 * the deferred, parked, not-yet-available and stuck commands it stopped on. A command is stuck once it is dead-lettered or
 * has been unsettled for longer than the configured stuck age.
 */
public final class DefaultWorkflowExecutionProbe implements WorkflowExecutionProbe {

    /** Shortest pause between two reads; the pause doubles after each read up to {@link #MAX_POLL_INTERVAL_MS}. */
    static final long POLL_INTERVAL_MS = 25;

    /** Longest pause between two reads. */
    static final long MAX_POLL_INTERVAL_MS = 250;

    /** The longest a probe waits; a longer timeout is shortened to this. */
    static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    /** Pauses the polling loop; the future completes after the delay. */
    @FunctionalInterface
    interface Pause {
        Future<Void> after(long delayMs);
    }

    private final WorkflowCommandRepository<?> commands;
    private final Pause pause;
    private final Duration maxTimeout;
    private final long stuckAfterSeconds;

    /**
     * Creates a probe that pauses between reads with Vert.x timers.
     *
     * @param commands the command repository
     * @param vertx    the Vert.x instance whose timers pace the polling
     */
    public DefaultWorkflowExecutionProbe(WorkflowCommandRepository<?> commands, Vertx vertx) {
        this(commands, vertx, WorkflowExecutorConfig.DEFAULT_STUCK_AFTER_SECONDS);
    }

    /**
     * Creates a probe that pauses between reads with Vert.x timers and reports a command stuck once it has been
     * unsettled for {@code stuckAfterSeconds}.
     *
     * @param commands          the command repository
     * @param vertx             the Vert.x instance whose timers pace the polling
     * @param stuckAfterSeconds the age after which an unsettled command is reported stuck, in seconds; at least 1
     */
    public DefaultWorkflowExecutionProbe(WorkflowCommandRepository<?> commands, Vertx vertx, long stuckAfterSeconds) {
        this(
                commands,
                delayMs -> {
                    Promise<Void> promise = Promise.promise();
                    vertx.setTimer(delayMs, id -> promise.complete());
                    return promise.future();
                },
                MAX_TIMEOUT,
                stuckAfterSeconds);
    }

    /**
     * Creates a probe that pauses between reads on a shared daemon thread, for assemblies that hold no Vert.x
     * instance.
     *
     * @param commands the command repository
     */
    public DefaultWorkflowExecutionProbe(WorkflowCommandRepository<?> commands) {
        this(commands, WorkflowExecutorConfig.DEFAULT_STUCK_AFTER_SECONDS);
    }

    /**
     * Creates a probe that pauses between reads on a shared daemon thread and reports a command stuck once it has
     * been unsettled for {@code stuckAfterSeconds}.
     *
     * @param commands          the command repository
     * @param stuckAfterSeconds the age after which an unsettled command is reported stuck, in seconds; at least 1
     */
    public DefaultWorkflowExecutionProbe(WorkflowCommandRepository<?> commands, long stuckAfterSeconds) {
        this(commands, Shared::pause, MAX_TIMEOUT, stuckAfterSeconds);
    }

    DefaultWorkflowExecutionProbe(WorkflowCommandRepository<?> commands, Pause pause, Duration maxTimeout) {
        this(commands, pause, maxTimeout, WorkflowExecutorConfig.DEFAULT_STUCK_AFTER_SECONDS);
    }

    DefaultWorkflowExecutionProbe(
            WorkflowCommandRepository<?> commands, Pause pause, Duration maxTimeout, long stuckAfterSeconds) {
        if (stuckAfterSeconds < 1) {
            throw new IllegalArgumentException("stuckAfterSeconds must be at least 1 but was " + stuckAfterSeconds);
        }
        this.commands = Objects.requireNonNull(commands, "commands");
        this.pause = pause;
        this.maxTimeout = maxTimeout;
        this.stuckAfterSeconds = stuckAfterSeconds;
    }

    @Override
    public Future<IdleReport> awaitIdle(WorkflowInstanceId id, Duration timeout) {
        Objects.requireNonNull(id, "id");
        return await(id, timeout, "workflow instance " + id.value());
    }

    @Override
    public Future<IdleReport> awaitAllIdle(Duration timeout) {
        return await(null, timeout, "all instances");
    }

    private Future<IdleReport> await(@Nullable WorkflowInstanceId scope, Duration timeout, String subject) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive: " + timeout);
        }
        Duration bounded = timeout.compareTo(maxTimeout) > 0 ? maxTimeout : timeout;
        long deadline = System.nanoTime() + bounded.toNanos();
        return poll(scope, subject, bounded, deadline, POLL_INTERVAL_MS);
    }

    private Future<IdleReport> poll(
            @Nullable WorkflowInstanceId scope, String subject, Duration timeout, long deadline, long pauseMs) {
        return commands.countRunnable(scope).compose(runnable -> {
            if (runnable == 0) {
                return report(scope);
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return Future.failedFuture(new TimeoutException(
                        subject + " is not idle after " + timeout + ": " + runnable + " runnable command(s) left"));
            }
            long delayMs = Math.min(pauseMs, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            long nextPauseMs = Math.min(pauseMs * 2, MAX_POLL_INTERVAL_MS);
            return pause.after(delayMs).compose(ignored -> poll(scope, subject, timeout, deadline, nextPauseMs));
        });
    }

    private Future<IdleReport> report(@Nullable WorkflowInstanceId scope) {
        Future<List<PendingCommand>> deferred = commands.findDeferred(scope);
        Future<List<PendingCommand>> parked = commands.findParked(scope);
        Future<List<PendingCommand>> notYetAvailable = commands.findNotYetAvailable(scope);
        Future<List<PendingCommand>> stuck = commands.findStuck(scope, stuckAfterSeconds);
        return Future.all(deferred, parked, notYetAvailable, stuck)
                .map(ignored ->
                        new IdleReport(deferred.result(), stuck.result(), parked.result(), notYetAvailable.result()));
    }

    /** One daemon thread paces the polling of probes built without a Vert.x instance. */
    private static final class Shared {
        private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "workflow-execution-probe");
            thread.setDaemon(true);
            return thread;
        });

        static Future<Void> pause(long delayMs) {
            Promise<Void> promise = Promise.promise();
            SCHEDULER.schedule(() -> promise.complete(), delayMs, TimeUnit.MILLISECONDS);
            return promise.future();
        }
    }
}
