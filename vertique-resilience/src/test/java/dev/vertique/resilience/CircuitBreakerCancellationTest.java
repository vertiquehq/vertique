// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static dev.vertique.resilience.ResilienceTestSupport.await;
import static dev.vertique.resilience.ResilienceTestSupport.awaitCondition;
import static dev.vertique.resilience.ResilienceTestSupport.awaitFailure;
import static dev.vertique.resilience.ResilienceTestSupport.block;
import static dev.vertique.resilience.ResilienceTestSupport.flush;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.resilience.exception.CircuitOpenException;
import dev.vertique.resilience.spi.ResilienceObserver;
import dev.vertique.resilience.spi.event.AttemptStarted;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proof for the circuit breaker's own execution paths that the cancellation, classification and
 * dispatch contracts depend on: a cancel before the execution starts, a cancellable execution that is
 * not cancelled, an operation that throws, a fatal error, and the release of the cancellation.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class CircuitBreakerCancellationTest {

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
     * Supporting FR-030: a cancel that arrives after the call was accepted and before the breaker
     * starts it fails the execution with the reason and never invokes the operation.
     */
    @Test
    @DisplayName("a cancel before the breaker starts the execution fails it without an invocation")
    void cancelBeforeTheBreakerStartsTheExecutionFailsItWithoutAnInvocation(Vertx vertx) throws Exception {
        // Given a breaker-only pipeline and an execution context that is busy
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResiliencePipeline pipeline = resilience
                .pipeline("cancel-before-start")
                .circuitBreaker(breaker -> breaker.maxFailures(2).resetTimeout(Duration.ofSeconds(30)))
                .build();
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        AtomicInteger invocations = new AtomicInteger();
        IllegalStateException reason = new IllegalStateException("stop");
        Future<String> result;
        try (ResilienceTestSupport.ContextBlock busy = block(resilience.executionContext())) {
            // When the call is accepted while the context is busy and is cancelled before it can start
            result = pipeline.execute(
                    () -> {
                        invocations.incrementAndGet();
                        return Future.succeededFuture("must-not-run");
                    },
                    cancellation);
            cancellation.cancel(reason);
        }

        // Then the execution fails with the reason and the operation was never invoked
        assertSame(reason, awaitFailure(result));
        assertEquals(0, invocations.get());
        assertTrue(events.stream().noneMatch(AttemptStarted.class::isInstance));
    }

    /**
     * Supporting FR-030: a cancellable execution that is not cancelled delivers its operation's value
     * and its operation's failure, and the failure counts against the breaker.
     */
    @Test
    @DisplayName("a cancellable execution that is not cancelled delivers the operation outcome")
    void cancellableExecutionThatIsNotCancelledDeliversTheOperationOutcome(Vertx vertx) throws Exception {
        // Given a breaker-only pipeline with maxFailures 1 and a cancellation that is never cancelled
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResiliencePipeline pipeline = resilience
                .pipeline("not-cancelled")
                .circuitBreaker(breaker -> breaker.maxFailures(1).resetTimeout(Duration.ofSeconds(30)))
                .build();
        ResilienceCancellation cancellation = ResilienceCancellation.create();

        // When an operation succeeds
        // Then its value is delivered
        assertEquals("value", await(pipeline.execute(() -> Future.succeededFuture("value"), cancellation)));

        // When an operation fails
        IllegalStateException failure = new IllegalStateException("boom");
        // Then its failure is delivered unchanged
        assertSame(failure, awaitFailure(pipeline.execute(() -> Future.failedFuture(failure), cancellation)));

        // And the failure counted: the breaker is open and rejects the next call
        assertInstanceOf(
                CircuitOpenException.class,
                awaitFailure(pipeline.execute(() -> Future.succeededFuture("rejected"), cancellation)));
    }

    /**
     * Supporting FR-030: when the operation completes after the cancel took effect but before the
     * breaker's own cancel listener ran, the execution still fails with the reason, and the late value is
     * not delivered.
     */
    @Test
    @DisplayName("an operation completed during the cancel still fails the execution with the reason")
    void operationCompletedDuringTheCancelStillFailsTheExecutionWithTheReason(Vertx vertx) throws Exception {
        // Given a breaker-only pipeline and a cancellation whose first listener completes the operation
        resilience = Resilience.create(vertx);
        ResiliencePipeline pipeline = resilience
                .pipeline("completed-during-cancel")
                .circuitBreaker(breaker -> breaker.maxFailures(5).resetTimeout(Duration.ofSeconds(30)))
                .build();
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        Promise<String> gate = Promise.promise();
        cancellation.onCancel(reason -> gate.complete("late value"));
        AtomicInteger started = new AtomicInteger();
        Future<String> result = pipeline.execute(
                () -> {
                    started.incrementAndGet();
                    return gate.future();
                },
                cancellation);
        awaitCondition(() -> started.get() == 1);
        // And the breaker's listener is registered behind the first one
        flush(resilience.executionContext());

        // When the execution is cancelled
        IllegalStateException reason = new IllegalStateException("stop");
        cancellation.cancel(reason);

        // Then it fails with the reason, not with the late value
        assertSame(reason, awaitFailure(result));
    }

    /**
     * A breaker used without a pipeline runs an operation that returns a value and
     * counts one that fails.
     */
    @Test
    @DisplayName("a standalone breaker runs its operation and counts its failure")
    void standaloneBreakerRunsItsOperationAndCountsItsFailure(Vertx vertx) throws Exception {
        // Given a standalone breaker with maxFailures 1
        resilience = Resilience.create(vertx);
        CircuitBreaker breaker = CircuitBreaker.builder(resilience, "standalone")
                .maxFailures(1)
                .resetTimeout(Duration.ofSeconds(30))
                .build();

        // When an operation succeeds and the next one fails
        assertEquals("value", await(breaker.execute(() -> Future.succeededFuture("value"))));
        IllegalStateException failure = new IllegalStateException("boom");

        // Then the failure is delivered unchanged and opens the breaker
        assertSame(failure, awaitFailure(breaker.execute(() -> Future.failedFuture(failure))));
        assertInstanceOf(
                CircuitOpenException.class, awaitFailure(breaker.execute(() -> Future.succeededFuture("rejected"))));
    }

    /**
     * An operation that throws instead of returning a future fails the execution with
     * that exception and counts as a failure.
     */
    @Test
    @DisplayName("an operation that throws fails the execution and counts as a failure")
    void operationThatThrowsFailsTheExecutionAndCountsAsAFailure(Vertx vertx) throws Exception {
        // Given a breaker-only pipeline with maxFailures 1
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResiliencePipeline pipeline = resilience
                .pipeline("throwing-operation")
                .circuitBreaker(breaker -> breaker.maxFailures(1).resetTimeout(Duration.ofSeconds(30)))
                .build();
        IllegalStateException thrown = new IllegalStateException("thrown");

        // When the operation throws
        Future<String> result = pipeline.execute(() -> {
            throw thrown;
        });

        // Then the execution fails with that exception and the breaker opened
        assertSame(thrown, awaitFailure(result));
        assertInstanceOf(
                CircuitOpenException.class, awaitFailure(pipeline.execute(() -> Future.succeededFuture("rejected"))));

        // And a cancellable execution of a throwing operation fails the same way
        ResiliencePipeline cancellable = resilience
                .pipeline("throwing-operation-cancellable")
                .circuitBreaker(breaker -> breaker.maxFailures(5).resetTimeout(Duration.ofSeconds(30)))
                .build();
        assertSame(
                thrown,
                awaitFailure(cancellable.execute(
                        () -> {
                            throw thrown;
                        },
                        ResilienceCancellation.create())));

        // And a standalone breaker, which has no pipeline around its operation, fails and counts the same way
        CircuitBreaker standalone = CircuitBreaker.builder(resilience, "throwing-standalone")
                .maxFailures(1)
                .resetTimeout(Duration.ofSeconds(30))
                .build();
        assertSame(thrown, awaitFailure(standalone.execute(() -> {
            throw thrown;
        })));
        assertInstanceOf(
                CircuitOpenException.class, awaitFailure(standalone.execute(() -> Future.succeededFuture("rejected"))));

        // And a standalone breaker whose predicate rejects the exception records it as a success
        CircuitBreaker tolerant = CircuitBreaker.builder(resilience, "throwing-tolerant")
                .maxFailures(1)
                .resetTimeout(Duration.ofSeconds(30))
                .countsAsFailure(failure -> false)
                .build();
        assertSame(thrown, awaitFailure(tolerant.execute(() -> {
            throw thrown;
        })));
        assertEquals("closed", await(tolerant.execute(() -> Future.succeededFuture("closed"))));
    }

    /**
     * A fatal error from the operation fails the execution with that error and is
     * also rethrown on the execution context, so the context's exception handler sees it.
     */
    @Test
    @DisplayName("a fatal error fails the execution and reaches the context exception handler")
    void fatalErrorFailsTheExecutionAndReachesTheContextExceptionHandler(Vertx vertx) throws Exception {
        // Given a breaker and an execution context whose exception handler records what reaches it
        resilience = Resilience.create(vertx);
        CircuitBreaker breaker = CircuitBreaker.builder(resilience, "fatal-unit")
                .maxFailures(2)
                .resetTimeout(Duration.ofSeconds(30))
                .build();
        FatalFailure fatal = new FatalFailure();
        AtomicReference<Future<String>> result = new AtomicReference<>();
        AtomicReference<Throwable> observed = new AtomicReference<>();
        CountDownLatch submitted = new CountDownLatch(1);
        CountDownLatch handled = new CountDownLatch(1);

        // When an operation throws the fatal error
        vertx.runOnContext(ignored -> {
            Context context = Vertx.currentContext();
            context.exceptionHandler(error -> {
                observed.set(error);
                handled.countDown();
            });
            result.set(breaker.execute(() -> {
                throw fatal;
            }));
            submitted.countDown();
        });

        // Then the execution fails with it and the context handler received it
        assertTrue(submitted.await(10, TimeUnit.SECONDS));
        assertSame(fatal, awaitFailure(result.get()));
        assertTrue(handled.await(10, TimeUnit.SECONDS));
        assertSame(fatal, observed.get());
    }

    private static final class FatalFailure extends VirtualMachineError {}
}
