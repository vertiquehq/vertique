// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.exception.ResilienceTimeoutException;
import dev.vertique.resilience.spi.ResilienceObserver;
import dev.vertique.resilience.spi.event.AttemptCompleted;
import dev.vertique.resilience.spi.event.CircuitState;
import dev.vertique.resilience.spi.event.CircuitStateChanged;
import dev.vertique.resilience.spi.event.ExecutionCompleted;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import dev.vertique.resilience.spi.event.ResilienceOutcomeCategory;
import dev.vertique.resilience.spi.event.TimeoutTriggered;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * AC-088: an attempt whose supplier fails with a {@link ResilienceTimeoutException} carrying the
 * pipeline's own operation key is recorded as that pipeline's timeout, on every attempt path, with
 * the value taken from the exception.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class SupplierReportedTimeoutTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);
    private static final long REPORTED_TIMEOUT_MS = 250L;

    private final List<ResilienceEvent> events = new CopyOnWriteArrayList<>();
    private final ResilienceObserver recorder = events::add;

    private Resilience resilience;

    @AfterEach
    void closeRuntime() throws Exception {
        if (resilience != null) {
            await(resilience.close());
        }
    }

    @Test
    @DisplayName(
            "an own-key timeout is recorded as the pipeline's timeout on every attempt path and another key is not")
    void ownKeyTimeoutIsRecordedAsThePipelinesTimeoutOnEveryAttemptPathAndAnotherKeyIsNot(Vertx vertx)
            throws Exception {
        resilience = Resilience.create(vertx, Set.of(recorder));

        // (a) retry maxRetries 1, no timeout concern: both attempts are supplier-reported timeouts
        ResiliencePipeline a = resilience
                .pipeline("reported-a")
                .retry(retry -> retry.maxRetries(1).backoff(RetryBackoff.fixed(10L)))
                .build();
        ResilienceTimeoutException aFailure = ownKeyTimeout(a);
        AtomicInteger aInvocations = new AtomicInteger();
        // When the pipeline runs once
        Throwable aCause = awaitFailure(a.execute(failingAfter(vertx, 50L, aFailure, aInvocations)));
        // Then each of its 2 attempts records TimeoutTriggered(250) followed by AttemptCompleted(TIMEOUT)
        assertSame(aFailure, aCause, "the failure is passed through unchanged");
        assertEquals(2, aInvocations.get());
        awaitCondition(() -> countOf(a, ExecutionCompleted.class) == 1);
        assertEquals(
                List.of(
                        "TimeoutTriggered#1:250",
                        "AttemptCompleted#1:TIMEOUT",
                        "TimeoutTriggered#2:250",
                        "AttemptCompleted#2:TIMEOUT",
                        "ExecutionCompleted:TIMEOUT"),
                outcomes(a));

        // (b) circuit breaker maxFailures 5, no retry or timeout concern
        int bMark = events.size();
        ResiliencePipeline b = resilience
                .pipeline("reported-b")
                .circuitBreaker(breaker -> breaker.maxFailures(5).resetTimeout(Duration.ofSeconds(30)))
                .build();
        AtomicInteger bInvocations = new AtomicInteger();
        // When it runs once
        Throwable bCause = awaitFailure(b.execute(failingAfter(vertx, 50L, ownKeyTimeout(b), bInvocations)));
        // Then exactly one TimeoutTriggered(250) and AttemptCompleted(TIMEOUT) are recorded
        assertInstanceOf(ResilienceTimeoutException.class, bCause);
        awaitCondition(() -> countOf(b, ExecutionCompleted.class) == 1);
        assertEquals(
                List.of("TimeoutTriggered#1:250", "AttemptCompleted#1:TIMEOUT", "ExecutionCompleted:TIMEOUT"),
                outcomes(b));
        // And the breaker counted the failure: 4 more open it at 5 and the next call is rejected
        for (int index = 0; index < 4; index++) {
            awaitFailure(b.execute(failingAfter(vertx, 1L, ownKeyTimeout(b), bInvocations)));
        }
        awaitCondition(() -> transitionsTo(CircuitState.OPEN, bMark) == 1);
        assertInstanceOf(
                CircuitOpenException.class,
                awaitFailure(b.execute(failingAfter(vertx, 1L, ownKeyTimeout(b), bInvocations))));
        assertEquals(5, bInvocations.get());

        // (c) timeout concern 1000 ms, no retry: the exception's 250 wins, the own timer adds nothing
        ResiliencePipeline c = resilience
                .pipeline("reported-c")
                .timeout(timeout -> timeout.duration(Duration.ofMillis(1_000L)))
                .build();
        long cStartedAt = System.nanoTime();
        // When it runs once
        Throwable cCause = awaitFailure(c.execute(failingAfter(vertx, 50L, ownKeyTimeout(c), new AtomicInteger())));
        // Then exactly one TimeoutTriggered with 250, not 1000
        assertInstanceOf(ResilienceTimeoutException.class, cCause);
        awaitCondition(() -> countOf(c, ExecutionCompleted.class) == 1);
        // And observing until 1500 ms after the start shows no second event from the pipeline's own timer
        long remainingMs = 1_500L - TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cStartedAt);
        if (remainingMs > 0L) {
            pause(vertx, remainingMs);
        }
        assertEquals(
                List.of("TimeoutTriggered#1:250", "AttemptCompleted#1:TIMEOUT", "ExecutionCompleted:TIMEOUT"),
                outcomes(c));

        // (d) retry maxRetries 1 with retryOn [ResilienceTimeoutException]: the failure is retried
        ResiliencePipeline d = resilience
                .pipeline("reported-d")
                .retry(retry -> retry.maxRetries(1)
                        .backoff(RetryBackoff.fixed(10L))
                        .retryOn(Set.<Class<? extends Throwable>>of(ResilienceTimeoutException.class)))
                .build();
        AtomicInteger dInvocations = new AtomicInteger();
        // When it runs once
        awaitFailure(d.execute(failingAfter(vertx, 50L, ownKeyTimeout(d), dInvocations)));
        // Then its supplier was invoked twice, with a TimeoutTriggered per attempt
        assertEquals(2, dInvocations.get());
        awaitCondition(() -> countOf(d, ExecutionCompleted.class) == 1);
        assertEquals(
                List.of(
                        "TimeoutTriggered#1:250",
                        "AttemptCompleted#1:TIMEOUT",
                        "TimeoutTriggered#2:250",
                        "AttemptCompleted#2:TIMEOUT",
                        "ExecutionCompleted:TIMEOUT"),
                outcomes(d));

        // (e) as (a), but the supplier throws synchronously instead of returning a failed future
        ResiliencePipeline e = resilience
                .pipeline("reported-e")
                .retry(retry -> retry.maxRetries(1).backoff(RetryBackoff.fixed(10L)))
                .build();
        ResilienceTimeoutException eFailure = ownKeyTimeout(e);
        AtomicInteger eInvocations = new AtomicInteger();
        // When it runs once
        Throwable eCause = awaitFailure(e.execute(() -> {
            eInvocations.incrementAndGet();
            throw eFailure;
        }));
        // Then it records the same events as (a)
        assertSame(eFailure, eCause);
        assertEquals(2, eInvocations.get());
        awaitCondition(() -> countOf(e, ExecutionCompleted.class) == 1);
        assertEquals(outcomes(a), outcomes(e));

        // (f) retry 1, breaker maxFailures 1 and resetTimeoutMs 200, no timeout concern, opened by one
        // IllegalStateException failure and run as the half-open trial
        ResiliencePipeline f = resilience
                .pipeline("reported-f")
                .retry(retry -> retry.maxRetries(1).backoff(RetryBackoff.fixed(10L)))
                .circuitBreaker(breaker -> breaker.maxFailures(1).resetTimeout(Duration.ofMillis(200L)))
                .build();
        int fMark = events.size();
        assertInstanceOf(
                IllegalStateException.class,
                awaitFailure(f.execute(() -> Future.failedFuture(new IllegalStateException("opens the breaker")))));
        awaitCondition(() -> transitionsTo(CircuitState.OPEN, fMark) == 1);
        awaitCondition(() -> transitionsTo(CircuitState.HALF_OPEN, fMark) == 1);
        int beforeTrial = events.size();
        AtomicInteger fInvocations = new AtomicInteger();
        // When the half-open trial runs
        awaitFailure(f.execute(failingAfter(vertx, 50L, ownKeyTimeout(f), fInvocations)));
        // Then it runs one attempt and records one TimeoutTriggered(250) and AttemptCompleted(TIMEOUT)
        assertEquals(1, fInvocations.get());
        awaitCondition(() -> countOf(f, ExecutionCompleted.class) == 2);
        assertEquals(
                List.of("TimeoutTriggered#1:250", "AttemptCompleted#1:TIMEOUT", "ExecutionCompleted:TIMEOUT"),
                outcomes(f, beforeTrial));

        // Another key: an exception built for a pipeline of another operation name records
        // AttemptCompleted(TIMEOUT) and no TimeoutTriggered, on the retry path and the breaker-only path
        ResiliencePipeline foreign = resilience
                .pipeline("reported-foreign")
                .retry(retry -> retry.maxRetries(1).backoff(RetryBackoff.fixed(10L)))
                .build();
        ResiliencePipeline otherRetry = resilience
                .pipeline("reported-other-retry")
                .retry(retry -> retry.maxRetries(1).backoff(RetryBackoff.fixed(10L)))
                .build();
        ResiliencePipeline otherBreaker = resilience
                .pipeline("reported-other-breaker")
                .circuitBreaker(breaker -> breaker.maxFailures(5).resetTimeout(Duration.ofSeconds(30)))
                .build();
        assertNotEquals(foreign.operationKey(), otherRetry.operationKey());
        awaitFailure(otherRetry.execute(failingAfter(vertx, 10L, ownKeyTimeout(foreign), new AtomicInteger())));
        awaitFailure(otherBreaker.execute(failingAfter(vertx, 10L, ownKeyTimeout(foreign), new AtomicInteger())));
        awaitCondition(() -> countOf(otherRetry, ExecutionCompleted.class) == 1);
        awaitCondition(() -> countOf(otherBreaker, ExecutionCompleted.class) == 1);
        assertEquals(
                List.of("AttemptCompleted#1:TIMEOUT", "AttemptCompleted#2:TIMEOUT", "ExecutionCompleted:TIMEOUT"),
                outcomes(otherRetry));
        assertEquals(List.of("AttemptCompleted#1:TIMEOUT", "ExecutionCompleted:TIMEOUT"), outcomes(otherBreaker));

        // Same-key nesting: an outer breaker pipeline (maxFailures 5) whose supplier runs an inner pipeline
        // of the same operation name with a 100 ms timeout concern and a hanging supplier
        ResiliencePipeline outer = resilience
                .pipeline("reported-nested")
                .circuitBreaker(breaker -> breaker.maxFailures(5).resetTimeout(Duration.ofSeconds(30)))
                .build();
        ResiliencePipeline inner = resilience
                .pipeline("reported-nested")
                .timeout(timeout -> timeout.duration(Duration.ofMillis(100L)))
                .build();
        assertEquals(outer.operationKey(), inner.operationKey());
        // When the outer pipeline runs once
        Throwable nestedCause = awaitFailure(outer.execute(
                () -> inner.execute(() -> Promise.<String>promise().future())));
        // Then one TimeoutTriggered with 100 is recorded on each pipeline: two events with distinct execution ids
        ResilienceTimeoutException nested = assertInstanceOf(ResilienceTimeoutException.class, nestedCause);
        assertEquals(100L, nested.timeoutMs());
        awaitCondition(() -> countOf(outer, ExecutionCompleted.class) == 2);
        List<TimeoutTriggered> triggered = of(outer, TimeoutTriggered.class, 0);
        assertEquals(2, triggered.size());
        assertEquals(
                List.of(100L, 100L),
                triggered.stream().map(TimeoutTriggered::timeoutMs).toList());
        assertEquals(
                2,
                triggered.stream().map(TimeoutTriggered::executionId).distinct().count(),
                "each pipeline reports its own timeout once");
        List<AttemptCompleted> attempts = of(outer, AttemptCompleted.class, 0);
        assertEquals(
                List.of(ResilienceOutcomeCategory.TIMEOUT, ResilienceOutcomeCategory.TIMEOUT),
                attempts.stream().map(AttemptCompleted::outcome).toList());
    }

    private static ResilienceTimeoutException ownKeyTimeout(ResiliencePipeline pipeline) {
        return new ResilienceTimeoutException(pipeline.operationKey(), REPORTED_TIMEOUT_MS);
    }

    /** A supplier that counts invocations and fails its future after the delay with the given failure. */
    private static Supplier<Future<String>> failingAfter(
            Vertx vertx, long delayMs, Throwable failure, AtomicInteger invocations) {
        return () -> {
            invocations.incrementAndGet();
            Promise<String> promise = Promise.promise();
            vertx.setTimer(delayMs, id -> promise.fail(failure));
            return promise.future();
        };
    }

    private <E extends ResilienceEvent> List<E> of(ResiliencePipeline pipeline, Class<E> type, int fromIndex) {
        return events.stream()
                .skip(fromIndex)
                .filter(type::isInstance)
                .map(type::cast)
                .filter(event -> keyOf(event).equals(pipeline.operationKey()))
                .toList();
    }

    private long countOf(ResiliencePipeline pipeline, Class<? extends ResilienceEvent> type) {
        return of(pipeline, type, 0).size();
    }

    private List<String> outcomes(ResiliencePipeline pipeline) {
        return outcomes(pipeline, 0);
    }

    /** The pipeline's timeout, attempt and execution events after the index, in emission order. */
    private List<String> outcomes(ResiliencePipeline pipeline, int fromIndex) {
        Function<ResilienceEvent, String> describe = event -> switch (event) {
            case TimeoutTriggered t -> "TimeoutTriggered#" + t.attemptOrdinal() + ":" + t.timeoutMs();
            case AttemptCompleted c -> "AttemptCompleted#" + c.attemptOrdinal() + ":" + c.outcome();
            case ExecutionCompleted x -> "ExecutionCompleted:" + x.outcome();
            default -> null;
        };
        return events.stream()
                .skip(fromIndex)
                .filter(event -> keyOf(event).equals(pipeline.operationKey()))
                .map(describe)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private static String keyOf(ResilienceEvent event) {
        return switch (event) {
            case TimeoutTriggered t -> t.operationKey();
            case AttemptCompleted c -> c.operationKey();
            case ExecutionCompleted x -> x.operationKey();
            default -> "";
        };
    }

    private long transitionsTo(CircuitState newState, int fromIndex) {
        return events.stream()
                .skip(fromIndex)
                .filter(CircuitStateChanged.class::isInstance)
                .map(CircuitStateChanged.class::cast)
                .filter(event -> event.newState() == newState)
                .count();
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within " + AWAIT_TIMEOUT);
            }
            Thread.sleep(5L);
        }
    }

    private static void pause(Vertx vertx, long delayMs) throws Exception {
        Promise<Void> elapsed = Promise.promise();
        vertx.setTimer(delayMs, id -> elapsed.complete());
        await(elapsed.future());
    }

    private static Throwable awaitFailure(Future<?> future) throws Exception {
        try {
            await(future);
        } catch (ExecutionException failure) {
            return failure.getCause();
        }
        throw new AssertionError("expected failure");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }
}
