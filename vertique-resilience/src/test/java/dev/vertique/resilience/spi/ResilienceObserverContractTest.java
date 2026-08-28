// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.resilience.Resilience;
import dev.vertique.resilience.ResiliencePipeline;
import dev.vertique.resilience.RetryBackoff;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** T005 proof for synchronous observer ordering and per-observer failure isolation. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 15, unit = TimeUnit.SECONDS)
class ResilienceObserverContractTest {

    private Resilience resilience;

    @AfterEach
    void closeRuntime() throws Exception {
        if (resilience != null) {
            resilience.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void dispatchesEveryTerminalSequenceInIsolation(Vertx vertx) throws Exception {
        List<Class<? extends ResilienceEvent>> firstObserved = new ArrayList<>();
        List<Class<? extends ResilienceEvent>> secondObserved = new ArrayList<>();
        AtomicInteger throwingObserverCalls = new AtomicInteger();
        ResilienceObserver recorder = event -> firstObserved.add(event.getClass());
        ResilienceObserver throwingObserver = event -> {
            throwingObserverCalls.incrementAndGet();
            throw new AssertionError("observer failure");
        };
        ResilienceObserver secondRecorder = event -> secondObserved.add(event.getClass());
        resilience = Resilience.create(vertx, Set.of(recorder, throwingObserver, secondRecorder));

        AtomicInteger attempts = new AtomicInteger();
        ResiliencePipeline pipeline = resilience
                .pipeline("observer-contract")
                .retry(builder -> builder.maxRetries(1)
                        .backoff(RetryBackoff.fixed(0L))
                        .retryOn(Set.of(IllegalStateException.class)))
                .build();

        String result = pipeline.execute(() -> attempts.incrementAndGet() == 1
                        ? Future.failedFuture(new IllegalStateException("expected"))
                        : Future.succeededFuture("ok"))
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals("ok", result);
        assertEquals(2, attempts.get());
        assertEquals(7, throwingObserverCalls.get());
        assertEquals(
                List.of(
                        "ExecutionStarted",
                        "AttemptStarted",
                        "AttemptCompleted",
                        "RetryScheduled",
                        "AttemptStarted",
                        "AttemptCompleted",
                        "ExecutionCompleted"),
                firstObserved.stream().map(Class::getSimpleName).toList());
        assertEquals(
                firstObserved.stream().map(Class::getSimpleName).toList(),
                secondObserved.stream().map(Class::getSimpleName).toList());
    }
}
