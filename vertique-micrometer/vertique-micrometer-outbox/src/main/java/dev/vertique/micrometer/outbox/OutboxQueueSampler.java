// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.outbox;

import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.inboxoutbox.OutboxQueueProbe;
import dev.vertique.inboxoutbox.OutboxQueueSample;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads the outbox queue size on a timer and hands each sample to {@link OutboxQueueMetrics}.
 *
 * <p>The timer starts in {@link LifecyclePhase#AFTER_START} and stops with the application. A read
 * never starts while the previous one is running, and a failed read is logged at debug level and
 * skipped: the gauges keep their last values until a read succeeds. When metrics
 * are disabled the timer never starts.
 *
 * <p>The timer runs on a Vert.x context of the instance given at construction; the sampler's state is
 * guarded, so a read's result may complete on any thread.
 */
final class OutboxQueueSampler implements ApplicationStartupStep, ApplicationShutdownStep {

    private static final Logger log = LoggerFactory.getLogger(OutboxQueueSampler.class);

    private final Vertx vertx;
    private final OutboxQueueProbe probe;
    private final OutboxQueueMetrics metrics;
    private final long intervalMs;
    private final boolean enabled;

    private long timerId = -1;
    private boolean reading;

    OutboxQueueSampler(
            Vertx vertx, OutboxQueueProbe probe, OutboxQueueMetrics metrics, long intervalMs, boolean enabled) {
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.probe = Objects.requireNonNull(probe, "probe");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.intervalMs = intervalMs;
        this.enabled = enabled;
    }

    @Override
    public LifecyclePhase phase() {
        return LifecyclePhase.AFTER_START;
    }

    @Override
    public Future<Void> start() {
        synchronized (this) {
            if (enabled && timerId == -1) {
                timerId = vertx.setPeriodic(intervalMs, id -> sample());
            }
        }
        return Future.succeededFuture();
    }

    @Override
    public Future<Void> stop() {
        synchronized (this) {
            if (timerId != -1) {
                vertx.cancelTimer(timerId);
                timerId = -1;
            }
        }
        return Future.succeededFuture();
    }

    /** Reads the queue once unless a read is still running. Package-private for tests. */
    void sample() {
        synchronized (this) {
            if (reading) {
                return;
            }
            reading = true;
        }
        Future<OutboxQueueSample> read;
        boolean probed = false;
        try {
            try {
                read = Objects.requireNonNull(probe.sample(), "probe returned null instead of a future");
            } catch (Exception | AssertionError e) {
                read = Future.failedFuture(e);
            }
            probed = true;
        } finally {
            if (!probed) {
                // A throwable that is not contained (a LinkageError, a fatal error) propagates; the
                // read it abandoned must not leave the guard set for every later read.
                synchronized (this) {
                    reading = false;
                }
            }
        }
        read.onComplete(result -> {
            synchronized (this) {
                reading = false;
            }
            if (result.failed()) {
                log.debug("The outbox queue size could not be read", result.cause());
                return;
            }
            try {
                metrics.record(result.result());
            } catch (Exception | AssertionError e) {
                log.debug("The outbox queue size could not be recorded", e);
            }
        });
    }
}
