// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.resilience.exception.CircuitOpenException;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** T003 TP-001 proof for local breaker admission, accounting, and half-open behavior. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class CircuitBreakerIT {

    private Resilience resilience;

    @AfterEach
    void closeRuntime() throws Exception {
        if (resilience != null) {
            await(resilience.close());
        }
    }

    @Test
    @DisplayName("direct and pipeline execution count one logical result and admit one half-open probe")
    void halfOpenProbeNeverRetriesAcrossDirectAndPipelineExecution(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        CircuitBreaker breaker = CircuitBreaker.builder(resilience, "direct-breaker")
                .maxFailures(2)
                .resetTimeout(Duration.ofMillis(100))
                .build();
        AtomicInteger directAttempts = new AtomicInteger();

        assertFailure(breaker.execute(() -> {
            directAttempts.incrementAndGet();
            return Future.failedFuture(new IllegalStateException("first"));
        }));
        assertFailure(breaker.execute(() -> {
            directAttempts.incrementAndGet();
            return Future.failedFuture(new IllegalStateException("second"));
        }));

        CircuitOpenException open = assertInstanceOf(CircuitOpenException.class, awaitFailure(breaker.execute(() -> {
            directAttempts.incrementAndGet();
            return Future.succeededFuture("not-admitted");
        })));
        assertEquals(open.operationKey(), open.stateKey());
        assertEquals(2, directAttempts.get(), "open admission must not invoke the supplier");

        Thread.sleep(150L);
        AtomicInteger probes = new AtomicInteger();
        Future<String> firstProbe = breaker.execute(() -> {
            probes.incrementAndGet();
            return Future.failedFuture(new IllegalStateException("probe"));
        });
        Future<String> secondProbe = breaker.execute(() -> {
            probes.incrementAndGet();
            return Future.succeededFuture("unexpected");
        });
        assertFailure(firstProbe);
        assertFailure(secondProbe);
        assertEquals(1, probes.get(), "half-open must admit one probe and reject contenders");

        CircuitBreaker pipelineBreaker = CircuitBreaker.builder(resilience, "pipeline-state")
                .maxFailures(2)
                .resetTimeout(Duration.ofSeconds(1))
                .build();
        ResiliencePipeline pipeline = resilience
                .pipeline("pipeline-operation")
                .retry(retry -> retry.maxRetries(1).backoff(RetryBackoff.fixed(0)))
                .circuitBreaker(pipelineBreaker)
                .build();
        AtomicInteger attempts = new AtomicInteger();
        assertEquals(
                "recovered",
                await(pipeline.execute(() -> attempts.incrementAndGet() == 1
                        ? Future.failedFuture(new IllegalStateException("transient"))
                        : Future.succeededFuture("recovered"))));
        assertEquals(2, attempts.get(), "retry success must count one successful logical result");
        await(pipelineBreaker.close());
    }

    @Test
    @DisplayName("fatal supplier errors fail publicly, reset the engine, and reach the context handler")
    void fatalSupplierIsDualDeliveredWithoutBreakerAccounting(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx);
        CircuitBreaker breaker = CircuitBreaker.builder(resilience, "fatal-breaker")
                .maxFailures(2)
                .resetTimeout(Duration.ofSeconds(1))
                .build();
        FatalFailure fatal = new FatalFailure();
        AtomicReference<Future<?>> result = new AtomicReference<>();
        AtomicReference<Throwable> observed = new AtomicReference<>();
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch handled = new CountDownLatch(1);

        vertx.runOnContext(ignored -> {
            Vertx.currentContext().exceptionHandler(error -> {
                observed.set(error);
                handled.countDown();
            });
            result.set(breaker.execute(() -> {
                throw fatal;
            }));
            submitted.countDown();
        });

        assertTrue(submitted.await(5, TimeUnit.SECONDS));
        assertSame(fatal, awaitFailure(result.get()));
        assertTrue(handled.await(5, TimeUnit.SECONDS));
        assertSame(fatal, observed.get());

        assertFailure(breaker.execute(() -> Future.failedFuture(new IllegalStateException("ordinary"))));
        assertFailure(breaker.execute(() -> Future.failedFuture(new IllegalStateException("second"))));
        assertInstanceOf(
                CircuitOpenException.class,
                awaitFailure(breaker.execute(() -> Future.failedFuture(new IllegalStateException("opens")))));
    }

    private static void assertFailure(Future<?> future) throws Exception {
        assertTrue(awaitFailure(future) != null, "expected a failed future");
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

    private static final class FatalFailure extends VirtualMachineError {}
}
