// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.resilience.exception.BulkheadRejectedException;
import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.spi.ResilienceObserver;
import dev.vertique.resilience.spi.event.BulkheadQueued;
import dev.vertique.resilience.spi.event.CircuitState;
import dev.vertique.resilience.spi.event.CircuitStateChanged;
import dev.vertique.resilience.spi.event.ExecutionCompleted;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import dev.vertique.resilience.spi.event.ResilienceOutcomeCategory;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proof for per-execution cancellation: a pending retry delay ends, a queued bulkhead call leaves the
 * queue, an in-flight attempt's late result is ignored, a cancelled execution never closes the
 * breaker, and the first cancel wins.
 *
 * <p>The scenarios run real pipelines on real Vert.x timers, as the existing pipeline tests do. Every
 * wait that observes asynchronous state polls an observable condition up to a generous deadline, and
 * every "nothing more happens" claim is observed across a window far longer than the work involved.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ResilienceCancellationTest {

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

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
     * AC-068: cancelling during a retry delay fails the execution with the given reason, starts no
     * further attempt, and reports one execution outcome.
     */
    @Test
    @DisplayName("cancel during a retry delay fails the execution without another attempt")
    void cancelDuringRetryDelayFailsTheExecutionWithoutAnotherAttempt(Vertx vertx) throws Exception {
        // Given a pipeline with 3 retries and a fixed 5000 ms delay, a supplier that always fails,
        // a recording observer and a cancellation
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResiliencePipeline pipeline = resilience
                .pipeline("cancel-retry-delay")
                .retry(retry -> retry.maxRetries(3).backoff(RetryBackoff.fixed(5_000L)))
                .build();
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        IllegalStateException reason = new IllegalStateException("stop");
        AtomicInteger invocations = new AtomicInteger();

        // When the execution runs and the cancellation is cancelled 200 ms after the first failure
        long startedAt = System.nanoTime();
        Future<String> result = pipeline.execute(
                () -> {
                    if (invocations.incrementAndGet() == 1) {
                        vertx.setTimer(200L, id -> cancellation.cancel(reason));
                    }
                    return Future.failedFuture(new IllegalStateException("boom"));
                },
                cancellation);
        AtomicLong failedAt = new AtomicLong();
        result.onFailure(ignored -> failedAt.set(System.nanoTime()));

        // Then the future fails with that same reason instance within 500 ms of the call
        assertSame(reason, awaitFailure(result));
        awaitCondition(() -> failedAt.get() != 0L);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(failedAt.get() - startedAt);
        assertTrue(elapsedMs < 500L, "the cancelled execution must end within 500 ms, took " + elapsedMs);
        assertTrue(cancellation.isCancelled());

        // And a second cancel with another reason is a no-op
        cancellation.cancel(new IllegalArgumentException("late"));
        assertSame(reason, result.cause());

        // And the supplier was invoked exactly once and not again during the following 6000 ms
        // (longer than the 5000 ms delay the cancelled timer would have waited)
        assertEquals(1, invocations.get());
        pause(vertx, 6_000L);
        assertEquals(1, invocations.get(), "no attempt may start after the cancel");

        // And exactly one ExecutionCompleted with outcome APPLICATION_FAILURE was recorded
        List<ExecutionCompleted> completed = executionCompleted();
        assertEquals(1, completed.size());
        assertEquals(
                ResilienceOutcomeCategory.APPLICATION_FAILURE, completed.get(0).outcome());

        // And execute(supplier) without a cancellation behaves as before
        ResiliencePipeline plain = resilience
                .pipeline("cancel-retry-plain")
                .retry(retry -> retry.maxRetries(1).backoff(RetryBackoff.fixed(10L)))
                .build();
        AtomicInteger plainInvocations = new AtomicInteger();
        assertEquals(
                "recovered",
                await(plain.execute(() -> plainInvocations.incrementAndGet() == 1
                        ? Future.failedFuture(new IllegalStateException("transient"))
                        : Future.succeededFuture("recovered"))));
        assertEquals(2, plainInvocations.get());
    }

    /**
     * AC-069: cancelling a call queued in the bulkhead removes it from the queue, never invokes its
     * supplier, and frees the queue slot for the next caller.
     */
    @Test
    @DisplayName("cancel while queued in the bulkhead releases the queue slot")
    void cancelWhileQueuedInTheBulkheadReleasesTheQueueSlot(Vertx vertx) throws Exception {
        // Given a pipeline with a bulkhead of 1 concurrent call and a queue of 1 with a 10 s wait,
        // a first execution whose supplier stays pending, and a second execution queued behind it
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResiliencePipeline pipeline = resilience
                .pipeline("cancel-queued")
                .bulkhead(bulkhead -> bulkhead.maxConcurrentCalls(1).queue(1, Duration.ofSeconds(10)))
                .build();
        Promise<String> firstGate = Promise.promise();
        AtomicInteger firstInvocations = new AtomicInteger();
        Future<String> first = pipeline.execute(() -> {
            firstInvocations.incrementAndGet();
            return firstGate.future();
        });
        awaitCondition(() -> firstInvocations.get() == 1);

        ResilienceCancellation cancellation = ResilienceCancellation.create();
        AtomicInteger secondInvocations = new AtomicInteger();
        Future<String> second = pipeline.execute(
                () -> {
                    secondInvocations.incrementAndGet();
                    return Future.succeededFuture("second-must-not-run");
                },
                cancellation);
        awaitCondition(() -> queued().size() == 1);
        assertFalse(second.isComplete(), "the second call waits in the queue");
        long secondExecutionId = queued().get(0).executionId();

        // When the second execution is cancelled
        IllegalStateException reason = new IllegalStateException("stop");
        cancellation.cancel(reason);

        // Then its future fails with that reason and its supplier was never invoked
        assertSame(reason, awaitFailure(second));
        assertEquals(0, secondInvocations.get());

        // And a third execution submitted afterwards is queued, not rejected with BulkheadRejectedException
        AtomicInteger thirdInvocations = new AtomicInteger();
        Future<String> third = pipeline.execute(() -> {
            thirdInvocations.incrementAndGet();
            return Future.succeededFuture("third");
        });
        awaitCondition(() -> queued().size() == 2);
        assertFalse(third.isComplete(), "the third call must wait in the freed queue slot");
        assertFalse(
                third.failed() && third.cause() instanceof BulkheadRejectedException,
                "the cancelled call must have released its queue slot");
        assertEquals(0, thirdInvocations.get());

        // And when the first supplier future completes the third supplier runs
        firstGate.complete("first");
        assertEquals("first", await(first));
        assertEquals("third", await(third));
        assertEquals(1, thirdInvocations.get());
        assertEquals(0, secondInvocations.get());

        // And the second execution was observed as ExecutionCompleted with APPLICATION_FAILURE
        awaitCondition(() -> executionCompleted().size() == 3);
        ExecutionCompleted secondCompleted = executionCompleted().stream()
                .filter(event -> event.executionId() == secondExecutionId)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no ExecutionCompleted for the cancelled execution"));
        assertEquals(ResilienceOutcomeCategory.APPLICATION_FAILURE, secondCompleted.outcome());
    }

    /**
     * Supporting FR-030: a cancelled half-open trial is recorded as a failure, so the breaker re-opens
     * and the next call within {@code resetTimeoutMs} is rejected.
     */
    @Test
    @DisplayName("a cancelled half-open trial re-opens the breaker and the next call within resetTimeout is rejected")
    void cancelledHalfOpenTrialReopensTheBreakerAndTheNextCallWithinResetTimeoutIsRejected(Vertx vertx)
            throws Exception {
        // Given a breaker with maxFailures 2 and resetTimeoutMs 1000, opened by two failures
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResiliencePipeline pipeline = resilience
                .pipeline("cancel-half-open")
                .circuitBreaker(breaker -> breaker.maxFailures(2).resetTimeout(Duration.ofMillis(1_000L)))
                .build();
        for (int index = 0; index < 2; index++) {
            assertInstanceOf(
                    IllegalStateException.class,
                    awaitFailure(pipeline.execute(() -> Future.failedFuture(new IllegalStateException("fail")))));
        }
        awaitCondition(() -> transitions(CircuitState.CLOSED, CircuitState.OPEN) == 1);
        // And the breaker has become half-open after resetTimeoutMs
        awaitCondition(() -> transitions(CircuitState.OPEN, CircuitState.HALF_OPEN) == 1);

        // When the half-open trial is cancelled while its attempt is in flight
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        Promise<String> trialGate = Promise.promise();
        AtomicInteger trialInvocations = new AtomicInteger();
        Future<String> trial = pipeline.execute(
                () -> {
                    trialInvocations.incrementAndGet();
                    return trialGate.future();
                },
                cancellation);
        awaitCondition(() -> trialInvocations.get() == 1);
        IllegalStateException reason = new IllegalStateException("stop");
        cancellation.cancel(reason);
        assertSame(reason, awaitFailure(trial));

        // Then the breaker re-opened (HALF_OPEN to OPEN, never to CLOSED)
        awaitCondition(() -> transitions(CircuitState.HALF_OPEN, CircuitState.OPEN) == 1);
        assertEquals(0, transitions(CircuitState.HALF_OPEN, CircuitState.CLOSED));

        // And the next call within resetTimeoutMs is rejected with CircuitOpenException, supplier not invoked
        AtomicInteger rejectedInvocations = new AtomicInteger();
        assertInstanceOf(CircuitOpenException.class, awaitFailure(pipeline.execute(() -> {
            rejectedInvocations.incrementAndGet();
            return Future.succeededFuture("not-admitted");
        })));
        assertEquals(0, rejectedInvocations.get());

        // And after resetTimeoutMs the next call is admitted and, succeeding, closes the breaker
        awaitCondition(() -> transitions(CircuitState.OPEN, CircuitState.HALF_OPEN) == 2);
        assertEquals("admitted", await(pipeline.execute(() -> Future.succeededFuture("admitted"))));
        awaitCondition(() -> transitions(CircuitState.HALF_OPEN, CircuitState.CLOSED) == 1);

        // And the late result of the cancelled trial changes nothing
        trialGate.complete("late");
        pause(vertx, 300L);
        assertSame(reason, trial.cause());
        assertEquals(1, transitions(CircuitState.HALF_OPEN, CircuitState.CLOSED));
    }

    /**
     * Supporting FR-030: a cancelled execution on a closed breaker is recorded as a success, which
     * resets the consecutive-failure count and never opens the breaker.
     */
    @Test
    @DisplayName("a cancelled execution on a closed breaker resets the failure count and never opens the breaker")
    void cancelledExecutionOnAClosedBreakerResetsTheFailureCountAndNeverOpensTheBreaker(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx, Set.of(recorder));

        // Given a closed breaker with maxFailures 2
        ResiliencePipeline cancelOnly = breakerPipeline("cancel-closed-only", 2);

        // When three executions are each cancelled while their attempt is in flight
        for (int index = 0; index < 3; index++) {
            cancelInFlight(vertx, cancelOnly);
        }

        // Then the breaker is still closed: it never opened and the next call is admitted
        assertEquals(0, transitions(CircuitState.CLOSED, CircuitState.OPEN));
        assertEquals("admitted", await(cancelOnly.execute(() -> Future.succeededFuture("admitted"))));

        // Given another closed breaker with maxFailures 2 and one failure counted
        ResiliencePipeline mixed = breakerPipeline("cancel-closed-mixed", 2);
        assertInstanceOf(
                IllegalStateException.class,
                awaitFailure(mixed.execute(() -> Future.failedFuture(new IllegalStateException("first")))));

        // When an execution is cancelled and then a second failure follows
        cancelInFlight(vertx, mixed);
        assertInstanceOf(
                IllegalStateException.class,
                awaitFailure(mixed.execute(() -> Future.failedFuture(new IllegalStateException("second")))));

        // Then the breaker is still closed, because the cancellation reset the count of 1 to 0
        assertEquals(0, transitions(CircuitState.CLOSED, CircuitState.OPEN));
        AtomicInteger admitted = new AtomicInteger();
        assertEquals("ok", await(mixed.execute(() -> {
            admitted.incrementAndGet();
            return Future.succeededFuture("ok");
        })));
        assertEquals(1, admitted.get());
    }

    /**
     * Supporting FR-030: cancelling while the attempt is in flight fails the execution once and ignores
     * the attempt's late result, including a late failure that would otherwise be retried.
     */
    @Test
    @DisplayName("cancel while the attempt is in flight ignores its late result and fails once")
    void cancelWhileTheAttemptIsInFlightIgnoresItsLateResultAndFailsOnce(Vertx vertx) throws Exception {
        // Given a pipeline with 2 retries and a 10 ms fixed delay
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResiliencePipeline pipeline = resilience
                .pipeline("cancel-in-flight")
                .retry(retry -> retry.maxRetries(2).backoff(RetryBackoff.fixed(10L)))
                .build();

        // When an execution is cancelled while its attempt is pending and the attempt then succeeds late
        ResilienceCancellation successCancellation = ResilienceCancellation.create();
        Promise<String> successGate = Promise.promise();
        AtomicInteger successInvocations = new AtomicInteger();
        Future<String> afterSuccess = pipeline.execute(
                () -> {
                    successInvocations.incrementAndGet();
                    return successGate.future();
                },
                successCancellation);
        awaitCondition(() -> successInvocations.get() == 1);
        IllegalStateException successReason = new IllegalStateException("stop-success");
        successCancellation.cancel(successReason);
        assertSame(successReason, awaitFailure(afterSuccess));
        successGate.complete("late-success");
        pause(vertx, 300L);

        // Then the future still holds the reason, not the late value
        assertSame(successReason, afterSuccess.cause());
        assertEquals(1, successInvocations.get());

        // When another execution is cancelled while pending and the attempt then fails late
        ResilienceCancellation failureCancellation = ResilienceCancellation.create();
        Promise<String> failureGate = Promise.promise();
        AtomicInteger failureInvocations = new AtomicInteger();
        Future<String> afterFailure = pipeline.execute(
                () -> {
                    failureInvocations.incrementAndGet();
                    return failureGate.future();
                },
                failureCancellation);
        awaitCondition(() -> failureInvocations.get() == 1);
        IllegalStateException failureReason = new IllegalStateException("stop-failure");
        failureCancellation.cancel(failureReason);
        assertSame(failureReason, awaitFailure(afterFailure));
        failureGate.fail(new IllegalStateException("late-failure"));
        pause(vertx, 500L);

        // Then no retry started and the future still holds the reason
        assertEquals(1, failureInvocations.get(), "a late failure must not trigger a retry");
        assertSame(failureReason, afterFailure.cause());

        // And each execution reported exactly one ExecutionCompleted with APPLICATION_FAILURE
        List<ExecutionCompleted> completed = executionCompleted();
        assertEquals(2, completed.size());
        assertEquals(
                List.of(ResilienceOutcomeCategory.APPLICATION_FAILURE, ResilienceOutcomeCategory.APPLICATION_FAILURE),
                completed.stream().map(ExecutionCompleted::outcome).toList());

        // Given a breaker-only pipeline with maxFailures 2: a cancel from another thread queues its
        // recording on the execution context, and the attempt's own result then arrives from a
        // non-context thread before that queued task runs
        int lateMark = events.size();
        ResiliencePipeline breakerOnly = breakerPipeline("cancel-late-result", 2);
        // When two executions are each cancelled and then their attempt fails late (before the queued
        // cancel task runs), and a third is cancelled and then succeeds late
        cancelThenCompleteBeforeContextRuns(vertx, breakerOnly, gate -> gate.fail(new IllegalStateException("late")));
        cancelThenCompleteBeforeContextRuns(vertx, breakerOnly, gate -> gate.fail(new IllegalStateException("late")));
        cancelThenCompleteBeforeContextRuns(vertx, breakerOnly, gate -> gate.complete("late-value"));

        // Then each execution reported exactly one ExecutionCompleted with APPLICATION_FAILURE
        awaitCondition(() -> executionCompletedSince(lateMark).size() == 3);
        assertEquals(
                List.of(
                        ResilienceOutcomeCategory.APPLICATION_FAILURE,
                        ResilienceOutcomeCategory.APPLICATION_FAILURE,
                        ResilienceOutcomeCategory.APPLICATION_FAILURE),
                executionCompletedSince(lateMark).stream()
                        .map(ExecutionCompleted::outcome)
                        .toList());
        // And the breaker did not count the late failures: it never opened and the next call is admitted
        assertEquals(
                0,
                events.stream()
                        .skip(lateMark)
                        .filter(CircuitStateChanged.class::isInstance)
                        .count());
        assertEquals("admitted", await(breakerOnly.execute(() -> Future.succeededFuture("admitted"))));
    }

    /**
     * Starts an execution on a known context with a pending attempt, blocks that context, cancels from
     * the test thread (the recording task queues behind the blocker), completes the attempt from the
     * test thread, then releases the context. The execution must fail with exactly the reason.
     */
    private void cancelThenCompleteBeforeContextRuns(
            Vertx vertx, ResiliencePipeline pipeline, java.util.function.Consumer<Promise<String>> completer)
            throws Exception {
        io.vertx.core.Context context = vertx.getOrCreateContext();
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        Promise<String> gate = Promise.promise();
        AtomicInteger started = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<Future<String>> execution =
                new java.util.concurrent.atomic.AtomicReference<>();
        context.runOnContext(ignored -> execution.set(pipeline.execute(
                () -> {
                    started.incrementAndGet();
                    return gate.future();
                },
                cancellation)));
        awaitCondition(() -> started.get() == 1 && execution.get() != null);

        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        context.runOnContext(ignored -> {
            blocked.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blocked.await(10, TimeUnit.SECONDS));
        try {
            IllegalStateException reason = new IllegalStateException("stop");
            cancellation.cancel(reason);
            completer.accept(gate);
            release.countDown();
            assertSame(reason, awaitFailure(execution.get()));
        } finally {
            release.countDown();
        }
    }

    private List<ExecutionCompleted> executionCompletedSince(int fromIndex) {
        return events.stream()
                .skip(fromIndex)
                .filter(ExecutionCompleted.class::isInstance)
                .map(ExecutionCompleted.class::cast)
                .toList();
    }

    /**
     * Supporting FR-030: the first cancel wins across threads, later cancels are no-ops, and null
     * arguments throw {@link NullPointerException} synchronously.
     */
    @Test
    @DisplayName("first cancel wins across threads and null arguments throw NullPointerException")
    void firstCancelWinsAcrossThreadsAndNullArgumentsThrowNullPointerException(Vertx vertx) throws Exception {
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResiliencePipeline pipeline = resilience
                .pipeline("cancel-race")
                .retry(retry -> retry.maxRetries(1).backoff(RetryBackoff.fixed(10L)))
                .build();

        // Given a new cancellation: never null, distinct per call, not cancelled
        ResilienceCancellation fresh = ResilienceCancellation.create();
        assertNotNull(fresh);
        assertFalse(fresh.isCancelled());
        assertNotSame(fresh, ResilienceCancellation.create());

        // Then cancel(null) throws NullPointerException and does not cancel
        assertThrows(NullPointerException.class, () -> fresh.cancel(null));
        assertFalse(fresh.isCancelled());

        // And the null arguments of execute throw synchronously, before any supplier is invoked
        AtomicInteger invoked = new AtomicInteger();
        Supplier<Future<String>> operation = () -> {
            invoked.incrementAndGet();
            return Future.succeededFuture("never");
        };
        assertThrows(NullPointerException.class, () -> pipeline.execute(null, fresh));
        assertThrows(NullPointerException.class, () -> pipeline.execute(operation, null));
        assertEquals(0, invoked.get());

        // Given an execution whose attempt is pending
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        Promise<String> gate = Promise.promise();
        AtomicInteger invocations = new AtomicInteger();
        Future<String> result = pipeline.execute(
                () -> {
                    invocations.incrementAndGet();
                    return gate.future();
                },
                cancellation);
        awaitCondition(() -> invocations.get() == 1);

        // When 8 threads race to cancel it, each with its own reason
        int threads = 8;
        List<IllegalStateException> reasons = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            reasons.add(new IllegalStateException("reason-" + index));
        }
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        try {
            for (IllegalStateException reason : reasons) {
                executor.execute(() -> {
                    ready.countDown();
                    try {
                        go.await();
                        cancellation.cancel(reason);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            go.countDown();
            assertTrue(done.await(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }

        // Then the execution failed once with exactly one of the reasons, and that choice is final
        Throwable winner = awaitFailure(result);
        assertTrue(cancellation.isCancelled());
        assertTrue(reasons.stream().anyMatch(reason -> reason == winner), "the failure must be one of the reasons");
        cancellation.cancel(new IllegalArgumentException("later"));
        assertSame(winner, result.cause());
        // ExecutionCompleted is emitted by a completion listener that may run after the caller sees the failure
        awaitCondition(() -> !executionCompleted().isEmpty());
        assertEquals(1, executionCompleted().size());
        assertEquals(
                ResilienceOutcomeCategory.APPLICATION_FAILURE,
                executionCompleted().get(0).outcome());
        assertEquals(1, invocations.get());

        // And with sequential cancels the first reason is the one reported
        ResilienceCancellation sequential = ResilienceCancellation.create();
        Promise<String> sequentialGate = Promise.promise();
        AtomicInteger sequentialInvocations = new AtomicInteger();
        Future<String> sequentialResult = pipeline.execute(
                () -> {
                    sequentialInvocations.incrementAndGet();
                    return sequentialGate.future();
                },
                sequential);
        awaitCondition(() -> sequentialInvocations.get() == 1);
        IllegalStateException firstReason = new IllegalStateException("first");
        sequential.cancel(firstReason);
        sequential.cancel(new IllegalStateException("second"));
        assertSame(firstReason, awaitFailure(sequentialResult));
    }

    private ResiliencePipeline breakerPipeline(String name, int maxFailures) {
        return resilience
                .pipeline(name)
                .circuitBreaker(breaker -> breaker.maxFailures(maxFailures).resetTimeout(Duration.ofSeconds(30)))
                .build();
    }

    /** Runs one execution whose attempt stays pending, cancels it, and awaits the failure with the reason. */
    private void cancelInFlight(Vertx vertx, ResiliencePipeline pipeline) throws Exception {
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        AtomicInteger started = new AtomicInteger();
        Future<String> execution = pipeline.execute(
                () -> {
                    started.incrementAndGet();
                    return Promise.<String>promise().future();
                },
                cancellation);
        awaitCondition(() -> started.get() == 1);
        IllegalStateException reason = new IllegalStateException("stop");
        cancellation.cancel(reason);
        assertSame(reason, awaitFailure(execution));
    }

    private List<ExecutionCompleted> executionCompleted() {
        return events.stream()
                .filter(ExecutionCompleted.class::isInstance)
                .map(ExecutionCompleted.class::cast)
                .toList();
    }

    private List<BulkheadQueued> queued() {
        return events.stream()
                .filter(BulkheadQueued.class::isInstance)
                .map(BulkheadQueued.class::cast)
                .toList();
    }

    private long transitions(CircuitState from, CircuitState to) {
        return events.stream()
                .filter(CircuitStateChanged.class::isInstance)
                .map(CircuitStateChanged.class::cast)
                .filter(event -> event.oldState() == from && event.newState() == to)
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
        } catch (java.util.concurrent.ExecutionException failure) {
            return failure.getCause();
        }
        throw new AssertionError("expected failure");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }
}
