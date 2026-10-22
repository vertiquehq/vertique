// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.lifecycle.ApplicationShutdownStep;
import dev.vertique.core.lifecycle.ApplicationStartupStep;
import dev.vertique.core.lifecycle.LifecyclePhase;
import dev.vertique.workflow.registry.store.WorkflowDefinitionRegistryStore;
import dev.vertique.workflow.registry.store.WorkflowDefinitionsConfig;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.ArgumentMatchers;

/**
 * Verifies the lifecycle of the periodic registry reconciliation: at most one pass runs at a time, a trigger that
 * arrives while a pass runs joins it, shutdown stops the timer and completes only after the running pass has
 * finished, and a node without a durable store schedules nothing.
 *
 * <p>The timer is a recording Vert.x double whose ticks the test fires by hand, and the reconciliation pass is a
 * fake whose completion the test controls, so nothing here depends on elapsed time.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DefinitionPollerLifecycleTest {

    private static final long TIMER_ID = 7L;

    @Test
    @DisplayName("two ticks run one pass, and shutdown cancels the timer and completes only after that pass drains")
    void doesNotOverlapAndStopsCleanly() {
        // Given a node with a durable store, a recording timer and a reconciliation pass that stays pending
        RecordingTimer timer = new RecordingTimer();
        PendingPasses passes = new PendingPasses();
        WorkflowDefinitionPropagationPoller poller = new WorkflowDefinitionPropagationPoller(
                Optional.of(mock(WorkflowDefinitionRegistryStore.class)),
                timer.vertx(),
                Optional.empty(),
                passes::start);
        ApplicationStartupStep startup = poller.startupStep();
        ApplicationShutdownStep shutdown = poller.shutdownStep();

        // And the startup step runs after every verticle has deployed and arms the timer without running a pass itself
        assertThat(startup.phase()).isEqualTo(LifecyclePhase.AFTER_START);
        assertThat(startup.start().succeeded()).isTrue();
        assertThat(timer.armed()).isEqualTo(1);
        assertThat(passes.started()).as("arming the timer does not poll").isZero();

        // When two ticks arrive before the first pass has completed
        timer.tick();
        timer.tick();

        // Then exactly one pass is in flight
        assertThat(passes.started()).isEqualTo(1);

        // And a direct trigger while it runs joins that same pass instead of starting another
        Future<Void> joined = poller.pollNow();
        Future<Void> joinedAgain = poller.pollNow();
        assertThat(joined.isComplete()).isFalse();
        assertThat(joinedAgain).isSameAs(joined);
        assertThat(passes.started()).isEqualTo(1);

        // When shutdown is requested while the pass is still pending
        Future<Void> stopping = shutdown.stop();

        // Then the timer is cancelled at once, but shutdown has not completed because work it owns is still running
        verify(timer.vertx(), times(1)).cancelTimer(TIMER_ID);
        assertThat(stopping.isComplete()).isFalse();
        assertThat(joined.isComplete()).isFalse();

        // And a tick that was already queued does not start work after the timer was cancelled
        timer.tick();
        assertThat(passes.started()).isEqualTo(1);

        // When the pending pass completes
        passes.completeAll();

        // Then the pass future and shutdown both complete, and no further pass ran
        assertThat(joined.succeeded()).isTrue();
        assertThat(stopping.succeeded()).isTrue();
        assertThat(passes.started()).isEqualTo(1);
    }

    @Test
    @DisplayName("a node without a durable store schedules no timer, runs no pass, and shuts down at once")
    void schedulesNothingWithoutAStore() {
        // Given a node without a durable store
        RecordingTimer timer = new RecordingTimer();
        PendingPasses passes = new PendingPasses();
        WorkflowDefinitionPropagationPoller poller = new WorkflowDefinitionPropagationPoller(
                Optional.empty(), timer.vertx(), Optional.empty(), passes::start);

        // When it starts and then stops
        Future<Void> started = poller.startupStep().start();
        Future<Void> stopped = poller.shutdownStep().stop();

        // Then both complete, no timer was armed or cancelled, and no pass ran
        assertThat(started.succeeded()).isTrue();
        assertThat(stopped.succeeded()).isTrue();
        assertThat(timer.armed()).isZero();
        verify(timer.vertx(), never()).setPeriodic(anyLong(), ArgumentMatchers.<Handler<Long>>any());
        verify(timer.vertx(), never()).setPeriodic(anyLong(), anyLong(), ArgumentMatchers.<Handler<Long>>any());
        verify(timer.vertx(), never()).cancelTimer(anyLong());
        assertThat(passes.started()).isZero();
    }

    @Test
    @DisplayName("the timer period is the configured staleness bound, thirty seconds when nothing is configured")
    void armsTheTimerAtTheConfiguredCadence() {
        // Given one node with a configured bound of five seconds and one with no configuration
        RecordingTimer configuredTimer = new RecordingTimer();
        RecordingTimer defaultTimer = new RecordingTimer();
        WorkflowDefinitionRegistryStore store = mock(WorkflowDefinitionRegistryStore.class);
        WorkflowDefinitionPropagationPoller configured = new WorkflowDefinitionPropagationPoller(
                Optional.of(store),
                configuredTimer.vertx(),
                Optional.of(new WorkflowDefinitionsConfig(5, 1024)),
                new PendingPasses()::start);
        WorkflowDefinitionPropagationPoller defaulted = new WorkflowDefinitionPropagationPoller(
                Optional.of(store), defaultTimer.vertx(), Optional.empty(), new PendingPasses()::start);

        // When both start
        assertThat(configured.startupStep().start().succeeded()).isTrue();
        assertThat(defaulted.startupStep().start().succeeded()).isTrue();

        // Then each timer repeats at its bound, in milliseconds
        assertThat(configuredTimer.periodMillis()).isEqualTo(5_000L);
        assertThat(defaultTimer.periodMillis()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("a new pass starts only after the running one has finished, whether it succeeded or failed")
    void startsAnotherPassOnlyAfterTheRunningOneFinishes() {
        // Given a started node whose first pass is pending
        RecordingTimer timer = new RecordingTimer();
        PendingPasses passes = new PendingPasses();
        WorkflowDefinitionPropagationPoller poller = new WorkflowDefinitionPropagationPoller(
                Optional.of(mock(WorkflowDefinitionRegistryStore.class)),
                timer.vertx(),
                Optional.empty(),
                passes::start);
        assertThat(poller.startupStep().start().succeeded()).isTrue();
        timer.tick();
        assertThat(passes.started()).isEqualTo(1);

        // When the first pass fails and the next tick arrives
        passes.failAll(new IllegalStateException("the metadata read failed"));
        timer.tick();

        // Then the failure did not wedge the poller: a second pass is now in flight
        assertThat(passes.started()).isEqualTo(2);

        // And while it runs, a further tick still starts nothing
        timer.tick();
        assertThat(passes.started()).isEqualTo(2);

        // When the second pass succeeds and the next tick arrives
        passes.completeAll();
        timer.tick();

        // Then a third pass starts
        assertThat(passes.started()).isEqualTo(3);
        passes.completeAll();
    }

    /** Hands out reconciliation passes that stay pending until the test completes them. */
    private static final class PendingPasses {

        private final AtomicInteger started = new AtomicInteger();
        private final List<Promise<Void>> pending = new ArrayList<>();

        Future<Void> start() {
            started.incrementAndGet();
            Promise<Void> pass = Promise.promise();
            pending.add(pass);
            return pass.future();
        }

        int started() {
            return started.get();
        }

        void completeAll() {
            List<Promise<Void>> running = new ArrayList<>(pending);
            pending.clear();
            running.forEach(Promise::complete);
        }

        void failAll(Throwable failure) {
            List<Promise<Void>> running = new ArrayList<>(pending);
            pending.clear();
            running.forEach(pass -> pass.fail(failure));
        }
    }

    /** A Vert.x double that records the periodic timer and lets the test fire its ticks. */
    private static final class RecordingTimer {

        private final Vertx vertx = mock(Vertx.class);
        private Handler<Long> handler;
        private long periodMillis;
        private int armed;

        RecordingTimer() {
            when(vertx.setPeriodic(anyLong(), ArgumentMatchers.<Handler<Long>>any()))
                    .thenAnswer(invocation -> {
                        arm(invocation.getArgument(0), invocation.getArgument(1));
                        return TIMER_ID;
                    });
            when(vertx.setPeriodic(anyLong(), anyLong(), ArgumentMatchers.<Handler<Long>>any()))
                    .thenAnswer(invocation -> {
                        arm(invocation.getArgument(1), invocation.getArgument(2));
                        return TIMER_ID;
                    });
            when(vertx.cancelTimer(eq(TIMER_ID))).thenReturn(true);
        }

        private void arm(long period, Handler<Long> tickHandler) {
            armed++;
            periodMillis = period;
            handler = tickHandler;
        }

        Vertx vertx() {
            return vertx;
        }

        int armed() {
            return armed;
        }

        long periodMillis() {
            return periodMillis;
        }

        /** Fires one tick, even after the timer was cancelled, as a tick that was already queued would. */
        void tick() {
            assertThat(handler).as("the periodic timer was armed").isNotNull();
            handler.handle(TIMER_ID);
        }
    }
}
