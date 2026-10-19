// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.workflow.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.core.exception.ConfigurationException;
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
 * Verifies how the periodic reconciliation behaves when a pass misbehaves or when it is triggered around shutdown:
 * a pass that throws synchronously, a trigger after shutdown, shutdown before start, and a cadence that does not
 * fit in milliseconds.
 *
 * <p>The timer is a Vert.x double and the pass a fake, so nothing here depends on elapsed time.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DefinitionPollerRobustnessTest {

    private static final long TIMER_ID = 11L;

    @Test
    @DisplayName("a pass that throws an error synchronously fails the trigger and does not wedge the poller")
    void survivesAPassThatThrowsAnError() {
        // Given a poller whose first pass overflows the stack before returning a future
        Vertx vertx = timerDouble();
        AtomicInteger started = new AtomicInteger();
        List<Promise<Void>> pending = new ArrayList<>();
        WorkflowDefinitionPropagationPoller poller = poller(vertx, Optional.empty(), () -> {
            if (started.incrementAndGet() == 1) throw new StackOverflowError();
            Promise<Void> pass = Promise.promise();
            pending.add(pass);
            return pass.future();
        });

        // When it is triggered
        Future<Void> first = poller.pollNow();

        // Then the trigger failed with that error instead of throwing it
        assertThat(first.failed()).isTrue();
        assertThat(first.cause()).isInstanceOf(StackOverflowError.class);

        // And the next trigger starts a new pass instead of joining a pass that never finishes
        Future<Void> second = poller.pollNow();
        assertThat(started.get()).isEqualTo(2);
        assertThat(second.isComplete()).isFalse();
        pending.getFirst().complete();
        assertThat(second.succeeded()).isTrue();
    }

    @Test
    @DisplayName("a trigger after shutdown returns a completed future and starts no pass")
    void doesNotPollAfterShutdown() {
        // Given a started poller that has been shut down
        Vertx vertx = timerDouble();
        AtomicInteger started = new AtomicInteger();
        WorkflowDefinitionPropagationPoller poller = poller(vertx, Optional.empty(), () -> {
            started.incrementAndGet();
            return Promise.<Void>promise().future();
        });
        assertThat(poller.startupStep().start().succeeded()).isTrue();
        assertThat(poller.shutdownStep().stop().succeeded()).isTrue();

        // When a direct trigger arrives afterwards
        Future<Void> late = poller.pollNow();

        // Then it is already complete and no pass ran
        assertThat(late.succeeded()).isTrue();
        assertThat(started.get()).isZero();
    }

    @Test
    @DisplayName("shutdown before start is a no-op that cancels nothing and runs nothing")
    void stopsBeforeStartingAsANoOp() {
        // Given a poller that was never started
        Vertx vertx = timerDouble();
        AtomicInteger started = new AtomicInteger();
        WorkflowDefinitionPropagationPoller poller = poller(vertx, Optional.empty(), () -> {
            started.incrementAndGet();
            return Future.succeededFuture();
        });

        // When it is shut down
        Future<Void> stopped = poller.shutdownStep().stop();

        // Then shutdown completes at once, no timer was cancelled and no pass ran
        assertThat(stopped.succeeded()).isTrue();
        verify(vertx, never()).cancelTimer(anyLong());
        assertThat(started.get()).isZero();
    }

    @Test
    @DisplayName("a cadence that does not fit in milliseconds fails startup with the configuration error")
    void failsStartupWhenTheCadenceOverflows() {
        // Given a staleness bound whose milliseconds overflow
        Vertx vertx = timerDouble();
        WorkflowDefinitionPropagationPoller poller = poller(
                vertx, Optional.of(new WorkflowDefinitionsConfig(Long.MAX_VALUE, 1024)), Future::succeededFuture);

        // When the poller starts
        Future<Void> started = poller.startupStep().start();

        // Then startup fails with the configuration error and no timer is armed
        assertThat(started.failed()).isTrue();
        assertThat(started.cause())
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("workflow.definitions.maxStalenessSeconds");
        verify(vertx, never()).setPeriodic(anyLong(), ArgumentMatchers.<Handler<Long>>any());
    }

    private static WorkflowDefinitionPropagationPoller poller(
            Vertx vertx, Optional<WorkflowDefinitionsConfig> config, WorkflowDefinitionPropagationPoller.Pass pass) {
        return new WorkflowDefinitionPropagationPoller(
                Optional.of(mock(WorkflowDefinitionRegistryStore.class)), vertx, config, pass);
    }

    private static Vertx timerDouble() {
        Vertx vertx = mock(Vertx.class);
        when(vertx.setPeriodic(anyLong(), ArgumentMatchers.<Handler<Long>>any()))
                .thenReturn(TIMER_ID);
        when(vertx.cancelTimer(TIMER_ID)).thenReturn(true);
        return vertx;
    }
}
