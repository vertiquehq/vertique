// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.outbox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.inboxoutbox.OutboxQueueProbe;
import dev.vertique.inboxoutbox.OutboxQueueSample;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("OutboxQueueSampler")
class OutboxQueueSamplerTest {

    private final Vertx vertx = Vertx.vertx();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @AfterEach
    void closeVertx() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private OutboxQueueSampler sampler(Supplier<Future<OutboxQueueSample>> probe, long intervalMs, boolean enabled) {
        OutboxQueueProbe queueProbe = probe::get;
        return new OutboxQueueSampler(vertx, queueProbe, new OutboxQueueMetrics(registry), intervalMs, enabled);
    }

    private double depth() {
        return registry.get(OutboxQueueMetrics.DEPTH).gauge().value();
    }

    @Test
    @DisplayName("starts in the AFTER_START phase")
    void phase() {
        assertEquals(LifecyclePhase.AFTER_START, sampler(() -> null, 1000, true).phase());
    }

    @Test
    @DisplayName("a read records its sample into the gauges")
    void readRecordsSample() {
        sampler(() -> Future.succeededFuture(new OutboxQueueSample(4, Duration.ofSeconds(9), 2)), 1000, true)
                .sample();

        assertEquals(4, depth());
    }

    @Test
    @DisplayName("a failed read leaves the last values and the next read still runs")
    void failedReadKeepsLastValues() {
        AtomicInteger calls = new AtomicInteger();
        OutboxQueueSampler sampler = sampler(
                () -> calls.incrementAndGet() == 2
                        ? Future.failedFuture(new IllegalStateException("db down"))
                        : Future.succeededFuture(new OutboxQueueSample(calls.get(), Duration.ZERO, 0)),
                1000,
                true);

        sampler.sample();
        sampler.sample();
        assertEquals(1, depth(), "a failed read keeps the previous value");
        sampler.sample();

        assertEquals(3, depth());
    }

    @Test
    @DisplayName("a probe that throws does not stop later reads")
    void throwingProbeIsContained() {
        AtomicInteger calls = new AtomicInteger();
        OutboxQueueSampler sampler = sampler(
                () -> {
                    if (calls.incrementAndGet() == 1) {
                        throw new IllegalStateException("broken probe");
                    }
                    return Future.succeededFuture(new OutboxQueueSample(5, Duration.ZERO, 0));
                },
                1000,
                true);

        sampler.sample();
        sampler.sample();

        assertEquals(5, depth());
    }

    @Test
    @DisplayName("no new read starts while the previous one is running")
    void noOverlappingReads() {
        AtomicInteger calls = new AtomicInteger();
        Promise<OutboxQueueSample> pending = Promise.promise();
        OutboxQueueSampler sampler = sampler(
                () -> {
                    calls.incrementAndGet();
                    return pending.future();
                },
                1000,
                true);

        sampler.sample();
        sampler.sample();
        sampler.sample();
        assertEquals(1, calls.get());

        pending.complete(new OutboxQueueSample(1, Duration.ZERO, 0));
        sampler.sample();
        assertEquals(2, calls.get(), "a read starts again once the previous one has settled");
    }

    @Test
    @DisplayName("start reads on the timer and stop ends it")
    void timerStartsAndStops() throws Exception {
        CountDownLatch reads = new CountDownLatch(2);
        AtomicInteger calls = new AtomicInteger();
        OutboxQueueSampler sampler = sampler(
                () -> {
                    calls.incrementAndGet();
                    reads.countDown();
                    return Future.succeededFuture(new OutboxQueueSample(1, Duration.ZERO, 0));
                },
                20,
                true);

        sampler.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        assertTrue(reads.await(5, TimeUnit.SECONDS), "the timer must read repeatedly");

        sampler.stop().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        int afterStop = calls.get();
        Thread.sleep(150);
        assertTrue(calls.get() <= afterStop + 1, "no read starts after stop (one in flight may finish)");
    }

    @Test
    @DisplayName("with metrics disabled start schedules nothing")
    void disabledStartsNoTimer() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        OutboxQueueSampler sampler = sampler(
                () -> {
                    calls.incrementAndGet();
                    return Future.succeededFuture(new OutboxQueueSample(1, Duration.ZERO, 0));
                },
                20,
                false);

        sampler.start().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Thread.sleep(150);

        assertEquals(0, calls.get());
        assertEquals(0, registry.getMeters().size());
    }
}
