// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.spi.ResilienceObserver;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import dev.vertique.resilience.spi.event.RetryScheduled;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proof that the public pipeline builder exposes the retry delay hint maximum and the circuit
 * breaker failure predicate. The scenarios use only the public {@link Resilience} entry point and
 * run on a real Vert.x timer; the lower bound on an observed gap is 10 ms below the hint to absorb
 * timer granularity, and upper bounds are generous so the proof holds under load.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class PublicBuilderOptionsTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(8);
    private static final long TIMER_TOLERANCE_MS = 10L;

    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
    private final ResilienceObserver recorder = events::add;

    private Resilience resilience;

    @AfterEach
    void closeRuntime() throws Exception {
        if (resilience != null) {
            await(resilience.close());
        }
    }

    /**
     * AC-093: the public retry builder honors a Retry-After hint up to {@code retryAfterMaxMs},
     * refuses a larger one, and keeps the plain backoff when no maximum is set.
     */
    @Test
    @DisplayName("AC-093: the public retry builder honors Retry-After up to its maximum")
    void retryBuilderHonorsRetryAfterUpToItsMaximum(Vertx vertx) throws Exception {
        // Given a pipeline with 1 retry, fixed 10 ms backoff and a hint maximum of 5000 ms
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResiliencePipeline pipeline = resilience
                .pipeline("p")
                .retry(r -> r.maxRetries(1).backoff(RetryBackoff.fixed(10)).retryAfterMaxMs(5000))
                .build();

        // When the first attempt fails with a 2 second hint and the second succeeds
        List<Long> startedAtNanos = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        String result = await(pipeline.execute(() -> {
            startedAtNanos.add(System.nanoTime());
            return attempts.getAndIncrement() == 0
                    ? Future.failedFuture(new HintedFailure(Duration.ofSeconds(2)))
                    : Future.succeededFuture("recovered");
        }));

        // Then the second attempt starts at least 2000 ms after the first and the execution succeeds
        assertEquals("recovered", result);
        assertEquals(2, attempts.get());
        assertTrue(
                millisBetween(startedAtNanos.get(0), startedAtNanos.get(1)) >= 2_000L - TIMER_TOLERANCE_MS,
                "the retry must wait for the hint, not the 10 ms backoff");
        // And the observer's RetryScheduled reports the 2000 ms delay
        assertEquals(List.of(2_000L), delays());

        // And the same pipeline with a 10 second hint surfaces the first failure after one attempt
        events.clear();
        HintedFailure tooLong = new HintedFailure(Duration.ofSeconds(10));
        AtomicInteger refusedAttempts = new AtomicInteger();
        long startedAt = System.nanoTime();
        ExecutionException refused = assertThrows(
                ExecutionException.class,
                () -> await(pipeline.execute(() -> {
                    refusedAttempts.incrementAndGet();
                    return Future.failedFuture(tooLong);
                })));
        assertSame(tooLong, refused.getCause());
        assertEquals(1, refusedAttempts.get());
        assertTrue(millisBetween(startedAt, System.nanoTime()) < 5_000L, "the 10 s hint must not be waited out");

        // And a pipeline built without retryAfterMaxMs retries after the 10 ms backoff
        events.clear();
        ResiliencePipeline unhinted = resilience
                .pipeline("p-default")
                .retry(r -> r.maxRetries(1).backoff(RetryBackoff.fixed(10)))
                .build();
        AtomicInteger defaultAttempts = new AtomicInteger();
        String defaultResult = await(unhinted.execute(() -> defaultAttempts.getAndIncrement() == 0
                ? Future.failedFuture(new HintedFailure(Duration.ofSeconds(10)))
                : Future.succeededFuture("ok")));
        assertEquals("ok", defaultResult);
        assertEquals(2, defaultAttempts.get());
        assertEquals(List.of(10L), delays());
    }

    /**
     * AC-094: the public circuit breaker builder counts only the failures its predicate accepts;
     * without a predicate every failure counts.
     */
    @Test
    @DisplayName("AC-094: the public breaker counts only the failures its predicate accepts")
    void breakerCountsOnlyTheFailuresItsPredicateAccepts(Vertx vertx) throws Exception {
        // Given a breaker with 2 max failures that does not count IllegalArgumentException
        resilience = Resilience.create(vertx);
        ResiliencePipeline pipeline = resilience
                .pipeline("b")
                .circuitBreaker(cb -> cb.maxFailures(2)
                        .resetTimeout(Duration.ofSeconds(60))
                        .countsAsFailure(f -> !(f instanceof IllegalArgumentException)))
                .build();

        // When three IllegalArgumentException failures run
        // Then each surfaces unchanged and the circuit stays closed
        for (int i = 0; i < 3; i++) {
            IllegalArgumentException failure = new IllegalArgumentException("ignored " + i);
            assertSame(failure, failureOf(pipeline, failure), "ignored failure " + i);
        }

        // And when two IllegalStateException failures run, each surfaces unchanged
        for (int i = 0; i < 2; i++) {
            IllegalStateException failure = new IllegalStateException("counted " + i);
            assertSame(failure, failureOf(pipeline, failure), "counted failure " + i);
        }

        // Then the next execution fails with CircuitOpenException without calling its supplier
        AtomicInteger calls = new AtomicInteger();
        ExecutionException open = assertThrows(
                ExecutionException.class,
                () -> await(pipeline.execute(() -> {
                    calls.incrementAndGet();
                    return Future.succeededFuture("never");
                })));
        assertInstanceOf(CircuitOpenException.class, open.getCause());
        assertEquals(0, calls.get());

        // And a breaker built without countsAsFailure opens after any two consecutive failures
        ResiliencePipeline plain = resilience
                .pipeline("b-plain")
                .circuitBreaker(cb -> cb.maxFailures(2).resetTimeout(Duration.ofSeconds(60)))
                .build();
        for (int i = 0; i < 2; i++) {
            IllegalArgumentException failure = new IllegalArgumentException("counted " + i);
            assertSame(failure, failureOf(plain, failure), "plain failure " + i);
        }
        AtomicInteger plainCalls = new AtomicInteger();
        ExecutionException plainOpen = assertThrows(
                ExecutionException.class,
                () -> await(plain.execute(() -> {
                    plainCalls.incrementAndGet();
                    return Future.succeededFuture("never");
                })));
        assertInstanceOf(CircuitOpenException.class, plainOpen.getCause());
        assertEquals(0, plainCalls.get());
    }

    private static Throwable failureOf(ResiliencePipeline pipeline, Throwable failure) {
        ExecutionException thrown = assertThrows(
                ExecutionException.class, () -> await(pipeline.execute(() -> Future.<String>failedFuture(failure))));
        return thrown.getCause();
    }

    private List<Long> delays() {
        return events.stream()
                .filter(RetryScheduled.class::isInstance)
                .map(RetryScheduled.class::cast)
                .map(RetryScheduled::delayMs)
                .toList();
    }

    private static long millisBetween(long fromNanos, long toNanos) {
        return TimeUnit.NANOSECONDS.toMillis(toNanos - fromNanos);
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** A failure that exposes a fixed retry delay hint. */
    private static final class HintedFailure extends RuntimeException implements RetryDelayHint {
        private final transient Duration hint;

        HintedFailure(Duration hint) {
            super("hinted failure");
            this.hint = hint;
        }

        @Override
        public Optional<Duration> retryAfter() {
            return Optional.of(hint);
        }
    }
}
