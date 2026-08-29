// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.resilience.exception.BulkheadQueueTimeoutException;
import dev.vertique.resilience.exception.BulkheadRejectedException;
import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.exception.ResilienceClosedException;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** T004 TP-001 proof for bulkhead capacity, queue ordering, races, and lifecycle release. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class BulkheadPipelineTest {

    private Resilience resilience;

    @AfterEach
    void closeRuntime() throws Exception {
        if (resilience != null) {
            await(resilience.close());
        }
    }

    @Test
    @DisplayName("bounded queue is FIFO, rejects when full, and releases one permit per logical execution")
    void enforcesBoundedFifoAdmissionWithoutPermitLeaks(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        Bulkhead bulkhead = Bulkhead.builder(resilience, "fifo")
                .maxConcurrentCalls(1)
                .queue(1, Duration.ofSeconds(1))
                .build();
        Promise<String> firstGate = Promise.promise();
        Promise<String> secondGate = Promise.promise();
        Promise<Void> firstStarted = Promise.promise();
        Promise<Void> secondStarted = Promise.promise();
        AtomicInteger invocations = new AtomicInteger();
        List<String> order = new ArrayList<>();

        Future<String> first = bulkhead.execute(() -> {
            order.add("first");
            invocations.incrementAndGet();
            firstStarted.complete();
            return firstGate.future();
        });
        await(firstStarted.future());

        Future<String> second = bulkhead.execute(() -> {
            order.add("second");
            invocations.incrementAndGet();
            secondStarted.complete();
            return secondGate.future();
        });
        Future<String> rejected = bulkhead.execute(() -> {
            invocations.incrementAndGet();
            return Future.succeededFuture("must-not-run");
        });

        BulkheadRejectedException rejection = assertInstanceOf(BulkheadRejectedException.class, awaitFailure(rejected));
        assertEquals(1, rejection.maxConcurrentCalls());
        assertEquals(1, invocations.get());

        firstGate.complete("first");
        assertEquals("first", await(first));
        await(secondStarted.future());
        assertEquals(List.of("first", "second"), order);

        secondGate.complete("second");
        assertEquals("second", await(second));
        assertEquals(2, invocations.get());

        assertEquals("after-release", await(bulkhead.execute(() -> Future.succeededFuture("after-release"))));
        await(bulkhead.close());
    }

    @Test
    @DisplayName("queue timeout removes the waiting node without invoking its supplier")
    void queueTimeoutDoesNotInvokeOrRetainWaitingSupplier(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        Bulkhead bulkhead = Bulkhead.builder(resilience, "timeout")
                .maxConcurrentCalls(1)
                .queue(1, Duration.ofMillis(25))
                .build();
        Promise<String> gate = Promise.promise();
        Promise<Void> started = Promise.promise();
        AtomicInteger queuedInvocations = new AtomicInteger();

        Future<String> active = bulkhead.execute(() -> {
            started.complete();
            return gate.future();
        });
        await(started.future());
        Future<String> timedOut = bulkhead.execute(() -> {
            queuedInvocations.incrementAndGet();
            return Future.succeededFuture("must-not-run");
        });

        BulkheadQueueTimeoutException timeout =
                assertInstanceOf(BulkheadQueueTimeoutException.class, awaitFailure(timedOut));
        assertEquals(25L, timeout.queueTimeoutMs());
        assertEquals(0, queuedInvocations.get());

        gate.complete("released");
        assertEquals("released", await(active));
        assertEquals("reusable", await(bulkhead.execute(() -> Future.succeededFuture("reusable"))));
    }

    @Test
    @DisplayName("pipeline admission surrounds the breaker and rejected calls do not count as failures")
    void pipelineBulkheadExcludesRejectedCallsFromBreakerAccounting(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        CircuitBreaker breaker = CircuitBreaker.builder(resilience, "pipeline-state")
                .maxFailures(1)
                .build();
        Bulkhead bulkhead = Bulkhead.builder(resilience, "pipeline-capacity")
                .maxConcurrentCalls(1)
                .reject()
                .build();
        ResiliencePipeline pipeline = resilience
                .pipeline("pipeline-operation")
                .circuitBreaker(breaker)
                .bulkhead(bulkhead)
                .build();
        Promise<String> gate = Promise.promise();
        Promise<Void> started = Promise.promise();
        AtomicInteger invocations = new AtomicInteger();

        Future<String> first = pipeline.execute(() -> {
            invocations.incrementAndGet();
            started.complete();
            return gate.future();
        });
        await(started.future());
        Future<String> rejected = pipeline.execute(() -> {
            invocations.incrementAndGet();
            return Future.succeededFuture("rejected");
        });
        assertInstanceOf(BulkheadRejectedException.class, awaitFailure(rejected));
        assertEquals(1, invocations.get());

        gate.complete("first");
        assertEquals("first", await(first));
        assertInstanceOf(IllegalStateException.class, awaitFailure(pipeline.execute(() -> {
            invocations.incrementAndGet();
            return Future.failedFuture(new IllegalStateException("opens"));
        })));
        assertEquals(2, invocations.get());
        assertInstanceOf(CircuitOpenException.class, awaitFailure(pipeline.execute(() -> {
            invocations.incrementAndGet();
            return Future.succeededFuture("not-admitted");
        })));
        assertEquals(2, invocations.get(), "open breaker check must happen before bulkhead admission");
    }

    @Test
    @DisplayName("closing fences active and queued executions and permits become reusable only after completion")
    void closeFencesActiveAndQueuedExecutions(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        Bulkhead bulkhead = Bulkhead.builder(resilience, "close")
                .maxConcurrentCalls(1)
                .queue(1, Duration.ofSeconds(1))
                .build();
        Promise<String> gate = Promise.promise();
        Promise<Void> started = Promise.promise();
        Future<String> active = bulkhead.execute(() -> {
            started.complete();
            return gate.future();
        });
        await(started.future());
        Future<String> queued = bulkhead.execute(() -> Future.succeededFuture("must-not-run"));

        Future<Void> close = bulkhead.close();
        assertInstanceOf(ResilienceClosedException.class, awaitFailure(active));
        assertInstanceOf(ResilienceClosedException.class, awaitFailure(queued));
        await(close);
        gate.complete("late");
    }

    @Test
    @DisplayName("bulkhead builder validates mode, capacity, queue bounds, and duplicate configuration")
    void validatesBulkheadBuilderContract(Vertx vertx) {
        resilience = Resilience.create(vertx);
        assertThrows(IllegalStateException.class, () -> Bulkhead.builder(resilience, "missing-mode")
                .maxConcurrentCalls(1)
                .build());
        assertThrows(IllegalArgumentException.class, () -> Bulkhead.builder(resilience, "bad-capacity")
                .maxConcurrentCalls(0)
                .reject()
                .build());
        assertThrows(IllegalArgumentException.class, () -> Bulkhead.builder(resilience, "bad-queue")
                .maxConcurrentCalls(1)
                .queue(0, Duration.ofMillis(1))
                .build());
        assertThrows(IllegalArgumentException.class, () -> Bulkhead.builder(resilience, "bad-timeout")
                .maxConcurrentCalls(1)
                .queue(1, Duration.ofMillis(60_001))
                .build());
        Bulkhead.Builder builder = Bulkhead.builder(resilience, "duplicate-mode")
                .maxConcurrentCalls(1)
                .reject();
        assertThrows(IllegalStateException.class, () -> builder.queue(1, Duration.ofMillis(1)));
    }

    private static Throwable awaitFailure(Future<?> future) throws Exception {
        try {
            await(future);
        } catch (Exception failure) {
            return failure.getCause() == null ? failure : failure.getCause();
        }
        throw new AssertionError("expected failure");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
