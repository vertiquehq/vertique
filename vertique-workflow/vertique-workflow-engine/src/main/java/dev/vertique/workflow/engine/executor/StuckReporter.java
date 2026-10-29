// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine.executor;

import dev.vertique.workflow.engine.spi.QueueCounts;
import dev.vertique.workflow.engine.spi.WorkflowCommandRepository;
import dev.vertique.workflow.executor.WorkflowExecutorEvent.QueueSnapshot;
import io.vertx.core.Future;
import io.vertx.sqlclient.SqlClient;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * INTERNAL: samples the size of the command queue for the observers: the commands available, stuck, parked and
 * dead-lettered.
 *
 * <p>A sample is read at most once per {@link #MIN_INTERVAL_MS} per executor instance, only while an observer is
 * registered, and never while the previous sample is still being read. A failed read is logged at debug level and
 * skipped. All calls come from the executor instance's event loop.
 */
final class StuckReporter {

    private static final Logger log = LoggerFactory.getLogger(StuckReporter.class);

    /** The shortest time between two samples of one executor instance, in milliseconds. */
    static final long MIN_INTERVAL_MS = 1000;

    private final WorkflowCommandRepository<SqlClient> commands;
    private final long stuckAfterSeconds;
    private final ExecutorEvents events;

    private long lastSampleNanos = System.nanoTime() - TimeUnit.MILLISECONDS.toNanos(MIN_INTERVAL_MS);
    private boolean reading;

    StuckReporter(WorkflowCommandRepository<SqlClient> commands, long stuckAfterSeconds, ExecutorEvents events) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.stuckAfterSeconds = stuckAfterSeconds;
        this.events = Objects.requireNonNull(events, "events");
    }

    /** Reads and reports a sample when an observer is registered and the last sample is old enough. */
    void sampleIfDue() {
        if (!events.any() || reading) {
            return;
        }
        long now = System.nanoTime();
        if (now - lastSampleNanos < TimeUnit.MILLISECONDS.toNanos(MIN_INTERVAL_MS)) {
            return;
        }
        lastSampleNanos = now;
        reading = true;
        Future<QueueCounts> counts;
        try {
            counts = Objects.requireNonNull(
                    commands.countQueue(stuckAfterSeconds), "countQueue returned null instead of a future");
        } catch (Exception | LinkageError | AssertionError e) {
            counts = Future.failedFuture(e);
        }
        counts.onComplete(result -> {
            reading = false;
            if (result.failed()) {
                log.debug("The queue snapshot could not be read", result.cause());
                return;
            }
            QueueCounts sample = result.result();
            events.emit(
                    new QueueSnapshot(sample.available(), sample.stuck(), sample.parked(), sample.deadLettered(), 0));
        });
    }
}
