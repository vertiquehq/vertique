// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static dev.vertique.cache.redis.RedisCleanupTestFixtures.CADENCE_MILLIS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.EventBusClient;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.eventbus.EventBus;
import io.vertx.core.eventbus.Message;
import io.vertx.core.eventbus.MessageConsumer;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifies that a cron tick reaching the cleanup handler honours the documented first-run jitter and retry backoff. */
class RedisCleanupHandlerSchedulingTest {

    private static final long FIRST_RUN_JITTER_MILLIS = 42_000;

    private final RedisCleanupTestFixtures.FakeTopology topology = new RedisCleanupTestFixtures.FakeTopology();
    private final RedisCleanupTestFixtures.MutableClock clock = new RedisCleanupTestFixtures.MutableClock(0);

    @Test
    @DisplayName("does not start the first sweep of an instance before its first-run jitter has elapsed")
    void firstSweepWaitsForTheInstanceJitter() {
        Handler<Message<Object>> handler = handlerFor(FIRST_RUN_JITTER_MILLIS);

        tick(handler);

        assertTrue(
                topology.scans.isEmpty(),
                () -> "first sweep scanned " + topology.scans.size() + " time(s) at once despite a "
                        + FIRST_RUN_JITTER_MILLIS + " ms first-run jitter");
    }

    @Test
    @DisplayName("skips the cron tick that falls inside the retry backoff after repeated failures")
    void tickInsideRetryBackoffDoesNotSweep() {
        Handler<Message<Object>> handler = handlerFor(0);

        topology.nextScan = Future.failedFuture("first scan failure");
        tick(handler);
        clock.advanceMillis(CADENCE_MILLIS);
        topology.nextScan = Future.failedFuture("second scan failure");
        tick(handler);
        int scansAfterTwoFailures = topology.scans.size();
        clock.advanceMillis(CADENCE_MILLIS);

        tick(handler);

        assertEquals(
                scansAfterTwoFailures,
                topology.scans.size(),
                "a tick 15 minutes after the second consecutive failure swept again; the retry delay is 30 minutes");
    }

    @Test
    @DisplayName("sweeps again on the tick that ends the retry delay after each failure")
    void tickAtTheEndOfTheRetryDelaySweeps() {
        Handler<Message<Object>> handler = handlerFor(0);

        topology.nextScan = Future.failedFuture("first scan failure");
        tick(handler);
        int scansAfterFirstFailure = topology.scans.size();
        clock.advanceMillis(CADENCE_MILLIS);
        topology.nextScan = Future.failedFuture("second scan failure");
        tick(handler);
        int scansAfterSecondFailure = topology.scans.size();
        clock.advanceMillis(CADENCE_MILLIS);
        tick(handler);
        assertEquals(scansAfterSecondFailure, topology.scans.size(), "the tick inside the 30 minute delay is skipped");
        clock.advanceMillis(CADENCE_MILLIS);
        tick(handler);

        assertTrue(scansAfterSecondFailure > scansAfterFirstFailure, "the tick after the 15 minute delay sweeps");
        assertTrue(topology.scans.size() > scansAfterSecondFailure, "the tick after the 30 minute delay sweeps");
    }

    private Handler<Message<Object>> handlerFor(long jitterMillis) {
        RedisCleanupJob job = RedisCleanupJobTestSupport.job(
                topology,
                new RedisCleanupTestFixtures.RecordingCommands(),
                Set.of(),
                () -> Math.toIntExact(jitterMillis),
                clock);
        Vertx vertx = mock(Vertx.class);
        EventBus eventBus = mock(EventBus.class);
        when(vertx.eventBus()).thenReturn(eventBus);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Handler<Message<Object>>> captured = ArgumentCaptor.forClass(Handler.class);
        doReturn(mock(MessageConsumer.class))
                .when(eventBus)
                .consumer(eq(RedisCleanupJob.HANDLER_ADDRESS), captured.capture());
        new RedisCleanupHandler(job, mock(EventBusClient.class)).register(vertx);
        return captured.getValue();
    }

    @SuppressWarnings("unchecked")
    private static void tick(Handler<Message<Object>> handler) {
        Message<Object> message = mock(Message.class);
        when(message.body()).thenReturn(DispatchEnvelope.of("cron tick"));
        handler.handle(message);
    }
}
