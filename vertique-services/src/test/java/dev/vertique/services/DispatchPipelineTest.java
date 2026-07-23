// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.services.dispatch.DispatchPipeline;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.policy.CircuitBreakerStage;
import dev.vertique.services.policy.PolicyStage;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(VertxExtension.class)
@DisplayName("DispatchPipeline")
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class DispatchPipelineTest {

    // --- Helpers ---

    private ServiceMethodMeta dummyMeta() {
        // Minimal meta — pipeline doesn't inspect meta values in unit tests
        return null;
    }

    private DispatchEnvelope<?> dummyBody() {
        return DispatchEnvelope.empty();
    }

    // --- Existing Pipeline Tests ---

    @Test
    @DisplayName("Three stages execute in outermost-to-innermost order")
    void shouldExecuteStagesInOrder() {
        List<Integer> order = new ArrayList<>();

        PolicyStage stage0 = (meta, body, next) -> {
            order.add(0);
            return next.get();
        };
        PolicyStage stage1 = (meta, body, next) -> {
            order.add(1);
            return next.get();
        };
        PolicyStage stage2 = (meta, body, next) -> {
            order.add(2);
            return next.get();
        };

        DispatchPipeline pipeline = new DispatchPipeline(List.of(stage0, stage1, stage2));
        Supplier<Future<Object>> terminal = () -> Future.succeededFuture("done");

        Future<Object> result = pipeline.execute(dummyMeta(), dummyBody(), terminal);
        assertTrue(result.succeeded());
        assertEquals(List.of(0, 1, 2), order, "Stages must execute in list order (outermost first)");
    }

    @Test
    @DisplayName("Empty pipeline with terminal supplier returns the terminal value")
    void shouldChainTerminalSupplier() {
        DispatchPipeline pipeline = new DispatchPipeline(List.of());
        Supplier<Future<Object>> terminal = () -> Future.succeededFuture("terminal-value");

        Future<Object> result = pipeline.execute(dummyMeta(), dummyBody(), terminal);
        assertTrue(result.succeeded());
        assertEquals("terminal-value", result.result());
    }

    @Test
    @DisplayName("A stage that returns a failed future propagates the failure")
    void shouldPropagateFailureFromStage() {
        RuntimeException failure = new RuntimeException("stage-failure");

        PolicyStage failingStage = (meta, body, next) -> Future.failedFuture(failure);

        DispatchPipeline pipeline = new DispatchPipeline(List.of(failingStage));
        Supplier<Future<Object>> terminal = () -> Future.succeededFuture("never-reached");

        Future<Object> result = pipeline.execute(dummyMeta(), dummyBody(), terminal);
        assertTrue(result.failed());
        assertSame(failure, result.cause());
    }

    @Test
    @DisplayName("hasStages() returns true when pipeline has stages")
    void shouldHaveStagesReturnTrue() {
        PolicyStage stage = (meta, body, next) -> next.get();
        DispatchPipeline pipeline = new DispatchPipeline(List.of(stage));
        assertTrue(pipeline.hasStages());
    }

    @Test
    @DisplayName("hasStages() returns false for an empty pipeline")
    void shouldHaveStagesReturnFalseWhenEmpty() {
        DispatchPipeline pipeline = new DispatchPipeline(List.of());
        assertFalse(pipeline.hasStages());
    }

    // --- CircuitBreakerStage Resilience Tests ---

    /**
     * When configured with maxRetries=2 and a fixed delay of 10ms, a terminal that fails twice
     * then succeeds must eventually succeed after retries.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    @DisplayName("CircuitBreakerStage retries on failure and succeeds on eventual success")
    void shouldRetryOnFailureWithBackoff(Vertx vertx, VertxTestContext ctx) throws Throwable {
        AtomicInteger callCount = new AtomicInteger(0);

        // maxRetries=2, 10ms fixed delay, circuit never trips (MAX_VALUE failures)
        CircuitBreakerStage stage = CircuitBreakerStage.create(
                vertx,
                "test.retry." + System.nanoTime(),
                Integer.MAX_VALUE,
                -1L,
                -1L,
                2,
                CircuitBreakerStage.backoffRetryPolicy(10, 1.0, 1000));

        // Terminal fails on first two calls, succeeds on the third
        Supplier<Future<Object>> terminal = () -> {
            int count = callCount.incrementAndGet();
            if (count < 3) {
                return Future.failedFuture(new RuntimeException("fail " + count));
            }
            return Future.succeededFuture("success");
        };

        DispatchPipeline pipeline = new DispatchPipeline(List.of(stage));
        pipeline.execute(dummyMeta(), dummyBody(), terminal).onComplete(ar -> {
            ctx.verify(() -> {
                assertTrue(ar.succeeded(), "Expected success after retries");
                assertEquals("success", ar.result());
                assertEquals(3, callCount.get(), "Expected exactly 3 calls (initial + 2 retries)");
            });
            ctx.completeNow();
        });

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * When configured with timeoutMs=50, a terminal that takes longer than the timeout must fail
     * with a timeout-related exception.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    @DisplayName("CircuitBreakerStage fails with timeout when operation exceeds per-attempt limit")
    void shouldTimeoutPerAttempt(Vertx vertx, VertxTestContext ctx) throws Throwable {
        // 50ms timeout, no retries, circuit never trips
        CircuitBreakerStage stage = CircuitBreakerStage.create(
                vertx, "test.timeout." + System.nanoTime(), Integer.MAX_VALUE, 50L, -1L, 0, null);

        // Terminal takes 300ms — well beyond the 50ms timeout
        Supplier<Future<Object>> terminal = () -> {
            io.vertx.core.Promise<Object> promise = io.vertx.core.Promise.promise();
            vertx.setTimer(300, id -> promise.complete("too-late"));
            return promise.future();
        };

        DispatchPipeline pipeline = new DispatchPipeline(List.of(stage));
        pipeline.execute(dummyMeta(), dummyBody(), terminal).onComplete(ar -> {
            ctx.verify(() -> assertTrue(ar.failed(), "Expected failure due to timeout"));
            ctx.completeNow();
        });

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * When configured with maxFailures=2 and no retries, the circuit must open after 2 failures
     * and the third call must fail without invoking the terminal.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    @DisplayName("CircuitBreakerStage trips after maxFailures and fails subsequent calls immediately")
    void shouldTripCircuitAfterMaxFailures(Vertx vertx, VertxTestContext ctx) throws Throwable {
        AtomicInteger terminalCallCount = new AtomicInteger(0);

        // maxFailures=2, no retries, resetTimeout=-1 (circuit stays open)
        CircuitBreakerStage stage =
                CircuitBreakerStage.create(vertx, "test.circuit." + System.nanoTime(), 2, -1L, -1L, 0, null);

        Supplier<Future<Object>> alwaysFails = () -> {
            terminalCallCount.incrementAndGet();
            return Future.failedFuture(new RuntimeException("downstream failure"));
        };

        DispatchPipeline pipeline = new DispatchPipeline(List.of(stage));

        // First two failures accumulate toward the threshold
        pipeline.execute(dummyMeta(), dummyBody(), alwaysFails).onComplete(ar1 -> pipeline.execute(
                        dummyMeta(), dummyBody(), alwaysFails)
                .onComplete(ar2 -> {
                    // After 2 failures, circuit should be open; third call should fail fast
                    // Give the circuit breaker a moment to process state change
                    vertx.setTimer(50, id -> {
                        AtomicInteger terminalCountBeforeThird = new AtomicInteger(terminalCallCount.get());
                        pipeline.execute(dummyMeta(), dummyBody(), alwaysFails).onComplete(ar3 -> {
                            ctx.verify(() -> {
                                assertTrue(ar3.failed(), "Third call must fail (circuit open)");
                                // Terminal should NOT have been called for the third attempt
                                // (circuit open = fail fast without calling downstream)
                                assertEquals(
                                        terminalCountBeforeThird.get(),
                                        terminalCallCount.get(),
                                        "Terminal must not be called when circuit is open");
                            });
                            ctx.completeNow();
                        });
                    });
                }));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }

    /**
     * When configured with maxFailures=Integer.MAX_VALUE (no {@link dev.vertique.services.policy.CircuitBreaker}
     * annotation), the circuit must never open regardless of how many times the operation fails.
     *
     * @param vertx the Vert.x instance provided by the extension
     * @param ctx the test context used to signal completion
     * @throws Throwable if the latch times out or an assertion fails
     */
    @Test
    @DisplayName("Circuit never trips with maxFailures=MAX_VALUE (no @CircuitBreaker annotation)")
    void shouldNotTripCircuitWithoutAnnotation(Vertx vertx, VertxTestContext ctx) throws Throwable {
        AtomicInteger terminalCallCount = new AtomicInteger(0);

        // maxFailures=MAX_VALUE means circuit never opens — only timeout+retry concern
        CircuitBreakerStage stage = CircuitBreakerStage.create(
                vertx, "test.nocircuit." + System.nanoTime(), Integer.MAX_VALUE, -1L, -1L, 0, null);

        Supplier<Future<Object>> alwaysFails = () -> {
            terminalCallCount.incrementAndGet();
            return Future.failedFuture(new RuntimeException("downstream failure"));
        };

        DispatchPipeline pipeline = new DispatchPipeline(List.of(stage));

        // Run 5 failures — terminal must be called every time (circuit never opens)
        pipeline.execute(dummyMeta(), dummyBody(), alwaysFails)
                .onComplete(r1 -> pipeline.execute(dummyMeta(), dummyBody(), alwaysFails)
                        .onComplete(r2 -> pipeline.execute(dummyMeta(), dummyBody(), alwaysFails)
                                .onComplete(r3 -> pipeline.execute(dummyMeta(), dummyBody(), alwaysFails)
                                        .onComplete(r4 -> pipeline.execute(dummyMeta(), dummyBody(), alwaysFails)
                                                .onComplete(r5 -> {
                                                    ctx.verify(() -> assertEquals(
                                                            5,
                                                            terminalCallCount.get(),
                                                            "Terminal must be called 5 times — circuit never opens"));
                                                    ctx.completeNow();
                                                })))));

        assertTrue(ctx.awaitCompletion(5, TimeUnit.SECONDS));
        if (ctx.failed()) throw ctx.causeOfFailure();
    }
}
