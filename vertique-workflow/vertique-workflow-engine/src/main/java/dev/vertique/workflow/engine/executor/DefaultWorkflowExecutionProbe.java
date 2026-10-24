// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

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
 * <p>The probe polls the repository at a bounded interval until no command is runnable or the timeout elapses; the
 * timeout is measured on the local monotonic clock, eligibility on the database clock. When it completes it reads
 * the deferred, parked and not-yet-available commands it stopped on.
 */
public final class DefaultWorkflowExecutionProbe implements WorkflowExecutionProbe {

    /** Longest pause between two reads. */
    static final long POLL_INTERVAL_MS = 25;

    /** Pauses the polling loop; the future completes after the delay. */
    @FunctionalInterface
    interface Pause {
        Future<Void> after(long delayMs);
    }

    private final WorkflowCommandRepository<?> commands;
    private final Pause pause;

    /**
     * Creates a probe that pauses between reads with Vert.x timers.
     *
     * @param commands the command repository
     * @param vertx    the Vert.x instance whose timers pace the polling
     */
    public DefaultWorkflowExecutionProbe(WorkflowCommandRepository<?> commands, Vertx vertx) {
        this(commands, delayMs -> {
            Promise<Void> promise = Promise.promise();
            vertx.setTimer(delayMs, id -> promise.complete());
            return promise.future();
        });
    }

    /**
     * Creates a probe that pauses between reads on a shared daemon thread, for assemblies that hold no Vert.x
     * instance.
     *
     * @param commands the command repository
     */
    public DefaultWorkflowExecutionProbe(WorkflowCommandRepository<?> commands) {
        this(commands, Shared::pause);
    }

    private DefaultWorkflowExecutionProbe(WorkflowCommandRepository<?> commands, Pause pause) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.pause = pause;
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
        long deadline = System.nanoTime() + timeout.toNanos();
        return poll(scope, subject, timeout, deadline);
    }

    private Future<IdleReport> poll(
            @Nullable WorkflowInstanceId scope, String subject, Duration timeout, long deadline) {
        return commands.countRunnable(scope).compose(runnable -> {
            if (runnable == 0) {
                return report(scope);
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                return Future.failedFuture(new TimeoutException(
                        subject + " is not idle after " + timeout + ": " + runnable + " runnable command(s) left"));
            }
            long delayMs = Math.min(POLL_INTERVAL_MS, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            return pause.after(delayMs).compose(ignored -> poll(scope, subject, timeout, deadline));
        });
    }

    private Future<IdleReport> report(@Nullable WorkflowInstanceId scope) {
        Future<List<PendingCommand>> deferred = commands.findDeferred(scope);
        Future<List<PendingCommand>> parked = commands.findParked(scope);
        Future<List<PendingCommand>> notYetAvailable = commands.findNotYetAvailable(scope);
        return Future.all(deferred, parked, notYetAvailable)
                .map(ignored ->
                        new IdleReport(deferred.result(), List.of(), parked.result(), notYetAvailable.result()));
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
