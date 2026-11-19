// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static dev.vertique.resilience.ResilienceTestSupport.await;
import static dev.vertique.resilience.ResilienceTestSupport.awaitCondition;
import static dev.vertique.resilience.ResilienceTestSupport.awaitFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.resilience.adapter.AdapterOperationIdentity;
import dev.vertique.resilience.adapter.CircuitFailureClassifier;
import dev.vertique.resilience.exception.BulkheadRejectedException;
import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.spi.ResilienceObserver;
import dev.vertique.resilience.spi.event.AttemptCompleted;
import dev.vertique.resilience.spi.event.AttemptStarted;
import dev.vertique.resilience.spi.event.CircuitCallRejected;
import dev.vertique.resilience.spi.event.ExecutionCompleted;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import dev.vertique.resilience.spi.event.ResilienceOutcomeCategory;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proof for how a pipeline reports and refuses executions: the outcome category of every completion,
 * the attempt events of an operation that throws, an execution whose cancellation is already
 * cancelled, and the adapter construction path that takes a breaker and a bulkhead.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ResilienceExecutionOutcomeTest {

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
     * Supporting FR-030: an execution is reported with the category of how it ended, whether or not it
     * carries a cancellation, and only an execution that ended with its own cancellation's reason is
     * reported as an application failure whatever the reason's type.
     */
    @Test
    @DisplayName("an execution is reported with the category of how it ended")
    void executionIsReportedWithTheCategoryOfHowItEnded(Vertx vertx) throws Exception {
        // Given a bulkhead-only pipeline of 1 permit that rejects the next call while it is held
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResiliencePipeline pipeline = resilience
                .pipeline("outcome-categories")
                .bulkhead(bulkhead -> bulkhead.maxConcurrentCalls(1).reject())
                .build();

        // When executions without a cancellation succeed and fail
        assertEquals("ok", await(pipeline.execute(() -> Future.succeededFuture("ok"))));
        awaitFailure(pipeline.execute(() -> Future.failedFuture(new IllegalStateException("plain"))));

        // And executions with a cancellation that is never cancelled succeed and fail
        ResilienceCancellation idle = ResilienceCancellation.create();
        assertEquals("ok", await(pipeline.execute(() -> Future.succeededFuture("ok"), idle)));
        awaitFailure(pipeline.execute(() -> Future.failedFuture(new IllegalStateException("plain")), idle));

        // And a call is rejected while the permit is held, once without and once with a cancellation
        Promise<String> gate = Promise.promise();
        AtomicInteger started = new AtomicInteger();
        Future<String> holder = pipeline.execute(() -> {
            started.incrementAndGet();
            return gate.future();
        });
        awaitCondition(() -> started.get() == 1);
        assertInstanceOf(
                BulkheadRejectedException.class,
                awaitFailure(pipeline.execute(() -> Future.succeededFuture("rejected"))));
        assertInstanceOf(
                BulkheadRejectedException.class,
                awaitFailure(pipeline.execute(() -> Future.succeededFuture("rejected"), idle)));
        gate.complete("held");
        assertEquals("held", await(holder));

        // Then each was reported with its own category
        awaitCondition(() -> completedOutcomes().size() == 7);
        assertEquals(
                List.of(
                        ResilienceOutcomeCategory.SUCCESS,
                        ResilienceOutcomeCategory.APPLICATION_FAILURE,
                        ResilienceOutcomeCategory.SUCCESS,
                        ResilienceOutcomeCategory.APPLICATION_FAILURE,
                        ResilienceOutcomeCategory.BULKHEAD_REJECTED,
                        ResilienceOutcomeCategory.BULKHEAD_REJECTED,
                        ResilienceOutcomeCategory.SUCCESS),
                completedOutcomes());

        // When a running execution is cancelled with a reason that is itself a bulkhead rejection
        events.clear();
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        AtomicInteger runningStarted = new AtomicInteger();
        Future<String> running = pipeline.execute(
                () -> {
                    runningStarted.incrementAndGet();
                    return Promise.<String>promise().future();
                },
                cancellation);
        awaitCondition(() -> runningStarted.get() == 1);
        BulkheadRejectedException reason = new BulkheadRejectedException(pipeline.operationKey(), 1);
        cancellation.cancel(reason);

        // Then the execution fails with that reason and is still reported as an application failure
        assertSame(reason, awaitFailure(running));
        awaitCondition(() -> completedOutcomes().size() == 1);
        assertEquals(List.of(ResilienceOutcomeCategory.APPLICATION_FAILURE), completedOutcomes());
    }

    /**
     * An operation that throws instead of returning a future is reported as a failed attempt, whether it
     * throws an exception or an error, and the execution fails with what it threw.
     */
    @Test
    @DisplayName("an operation that throws is reported as a failed attempt")
    void operationThatThrowsIsReportedAsAFailedAttempt(Vertx vertx) throws Exception {
        // Given a bulkhead-only pipeline, whose attempt is run directly
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResiliencePipeline pipeline = resilience
                .pipeline("throwing-attempt")
                .bulkhead(bulkhead -> bulkhead.maxConcurrentCalls(2).reject())
                .build();

        // When the operation throws an exception
        IllegalStateException exception = new IllegalStateException("thrown exception");
        Future<String> first = pipeline.execute(() -> {
            throw exception;
        });

        // Then the execution fails with it and the attempt was reported as failed
        assertSame(exception, awaitFailure(first));
        awaitCondition(() -> attemptCompleted().size() == 1);
        assertEquals(1, attemptStarted().size());
        assertEquals(
                ResilienceOutcomeCategory.APPLICATION_FAILURE,
                attemptCompleted().get(0).outcome());

        // When the operation throws an error
        AssertionError error = new AssertionError("thrown error");
        Future<String> second = pipeline.execute(() -> {
            throw error;
        });

        // Then the execution fails with it and the attempt was reported as failed
        assertSame(error, awaitFailure(second));
        awaitCondition(() -> attemptCompleted().size() == 2);
        assertEquals(2, attemptStarted().size());
        assertEquals(
                ResilienceOutcomeCategory.APPLICATION_FAILURE,
                attemptCompleted().get(1).outcome());

        // And a failing future is reported the same way
        awaitFailure(pipeline.execute(() -> Future.failedFuture(new IllegalStateException("failed future"))));
        awaitCondition(() -> attemptCompleted().size() == 3);
        assertEquals(
                ResilienceOutcomeCategory.APPLICATION_FAILURE,
                attemptCompleted().get(2).outcome());
    }

    /**
     * Supporting FR-030: an execution whose cancellation is already cancelled fails with the reason
     * before the circuit breaker is consulted, so it is not reported as a rejection by an open breaker.
     */
    @Test
    @DisplayName("an already cancelled execution fails with the reason before an open breaker is consulted")
    void alreadyCancelledExecutionFailsWithTheReasonBeforeAnOpenBreakerIsConsulted(Vertx vertx) throws Exception {
        // Given a breaker-only pipeline whose breaker is open after one failure
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResiliencePipeline pipeline = resilience
                .pipeline("pre-cancelled")
                .circuitBreaker(breaker -> breaker.maxFailures(1).resetTimeout(Duration.ofSeconds(30)))
                .build();
        awaitFailure(pipeline.execute(() -> Future.failedFuture(new IllegalStateException("opens"))));
        assertInstanceOf(
                CircuitOpenException.class,
                awaitFailure(
                        pipeline.execute(() -> Future.succeededFuture("rejected"), ResilienceCancellation.create())));
        assertEquals(1, circuitRejections().size(), "an ordinary call is rejected by the open breaker");
        events.clear();

        // When an execution is submitted whose cancellation is already cancelled
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        IllegalStateException reason = new IllegalStateException("stop");
        cancellation.cancel(reason);
        AtomicInteger invocations = new AtomicInteger();
        Future<String> result = pipeline.execute(
                () -> {
                    invocations.incrementAndGet();
                    return Future.succeededFuture("must-not-run");
                },
                cancellation);

        // Then it fails with the reason, not with the open breaker's rejection
        assertNotNull(result);
        assertSame(reason, awaitFailure(result));
        assertEquals(0, invocations.get());
        awaitCondition(() -> completedOutcomes().size() == 1);
        assertEquals(List.of(ResilienceOutcomeCategory.APPLICATION_FAILURE), completedOutcomes());
        assertTrue(circuitRejections().isEmpty(), "the open breaker was not consulted");
        assertTrue(attemptStarted().isEmpty());
    }

    /**
     * Supporting FR-032 and AC-092: a pipeline built through the adapter path from a policy, a breaker and a
     * bulkhead runs with them, and refuses a configuration that enables nothing.
     */
    @Test
    @DisplayName("an adapter pipeline takes a breaker and a bulkhead and refuses an empty configuration")
    void adapterPipelineTakesABreakerAndABulkheadAndRefusesAnEmptyConfiguration(Vertx vertx) throws Exception {
        // Given a runtime, an adapter identity, an empty policy and a policy with only a timeout
        resilience = Resilience.create(vertx, Set.of(recorder));
        AdapterOperationIdentity identity =
                new AdapterOperationIdentity("adapter.test", List.of("scenario", "construction"));
        ResolvedResiliencePolicy empty =
                new ResolvedResiliencePolicy(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        ResolvedResiliencePolicy timeoutOnly = new ResolvedResiliencePolicy(
                Optional.of(TimeoutConfig.ofMillis(5_000L)), Optional.empty(), Optional.empty(), Optional.empty());
        CircuitBreaker breaker =
                CircuitBreaker.builder(resilience, "adapter-breaker").build();
        Bulkhead bulkhead = Bulkhead.builder(resilience, "adapter-bulkhead")
                .maxConcurrentCalls(1)
                .reject()
                .build();
        CircuitFailureClassifier classifier = null;

        // When a pipeline is built with nothing enabled and nothing supplied
        // Then it is refused
        IllegalStateException refused = assertThrows(
                IllegalStateException.class,
                () -> ResiliencePipeline.fromAdapterPolicy(
                        resilience, identity, empty, (CircuitBreaker) null, (Bulkhead) null, classifier, () -> true));
        assertEquals("a pipeline must configure at least one concern", refused.getMessage());

        // When a pipeline is built from a policy with a timeout alone
        ResiliencePipeline withTimeout = ResiliencePipeline.fromAdapterPolicy(
                resilience, identity, timeoutOnly, (CircuitBreaker) null, (Bulkhead) null, classifier, () -> true);

        // Then it runs
        assertNotNull(withTimeout);
        assertEquals("timed", await(withTimeout.execute(() -> Future.succeededFuture("timed"))));

        // When a pipeline is built from an empty policy and a breaker
        ResiliencePipeline withBreaker = ResiliencePipeline.fromAdapterPolicy(
                resilience, identity, empty, breaker, (Bulkhead) null, classifier, () -> true);

        // Then it runs under the breaker
        assertNotNull(withBreaker);
        assertEquals("guarded", await(withBreaker.execute(() -> Future.succeededFuture("guarded"))));

        // When a pipeline is built from an empty policy and a bulkhead
        ResiliencePipeline withBulkhead = ResiliencePipeline.fromAdapterPolicy(
                resilience, identity, empty, (CircuitBreaker) null, bulkhead, classifier, () -> true);

        // Then it runs under the bulkhead
        assertNotNull(withBulkhead);
        assertEquals("limited", await(withBulkhead.execute(() -> Future.succeededFuture("limited"))));
    }

    private List<ResilienceOutcomeCategory> completedOutcomes() {
        return events.stream()
                .filter(ExecutionCompleted.class::isInstance)
                .map(ExecutionCompleted.class::cast)
                .map(ExecutionCompleted::outcome)
                .toList();
    }

    private List<AttemptStarted> attemptStarted() {
        return events.stream()
                .filter(AttemptStarted.class::isInstance)
                .map(AttemptStarted.class::cast)
                .toList();
    }

    private List<AttemptCompleted> attemptCompleted() {
        return events.stream()
                .filter(AttemptCompleted.class::isInstance)
                .map(AttemptCompleted.class::cast)
                .toList();
    }

    private List<CircuitCallRejected> circuitRejections() {
        return events.stream()
                .filter(CircuitCallRejected.class::isInstance)
                .map(CircuitCallRejected.class::cast)
                .toList();
    }
}
