// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static dev.vertique.resilience.ResilienceTestSupport.await;
import static dev.vertique.resilience.ResilienceTestSupport.awaitCollected;
import static dev.vertique.resilience.ResilienceTestSupport.awaitCondition;
import static dev.vertique.resilience.ResilienceTestSupport.awaitFailure;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.resilience.exception.BulkheadRejectedException;
import dev.vertique.resilience.spi.ResilienceObserver;
import dev.vertique.resilience.spi.event.BulkheadQueued;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proof for what a cancelled bulkhead execution gives back: its permit, its queue timer, its queue
 * slot and its reference to the operation, and that a cancel after normal completion changes nothing.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BulkheadCancellationTest {

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
     * Supporting FR-030: cancelling an execution whose supplier is running gives its permit back, so
     * the next caller is admitted instead of rejected and a queued caller is admitted at once.
     */
    @Test
    @DisplayName("cancelling a running call releases its permit")
    void cancellingARunningCallReleasesItsPermit(Vertx vertx) throws Exception {
        // Given a reject-mode bulkhead of 1 whose only permit is held by a call that stays pending
        resilience = Resilience.create(vertx);
        ResiliencePipeline rejecting = resilience
                .pipeline("cancel-active-reject")
                .bulkhead(bulkhead -> bulkhead.maxConcurrentCalls(1).reject())
                .build();
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        AtomicInteger started = new AtomicInteger();
        Promise<String> gate = Promise.promise();
        Future<String> holder = rejecting.execute(
                () -> {
                    started.incrementAndGet();
                    return gate.future();
                },
                cancellation);
        awaitCondition(() -> started.get() == 1);
        assertInstanceOf(
                BulkheadRejectedException.class,
                awaitFailure(rejecting.execute(() -> Future.succeededFuture("while-held"))));

        // When the holder is cancelled
        IllegalStateException reason = new IllegalStateException("stop");
        cancellation.cancel(reason);
        assertSame(reason, awaitFailure(holder));

        // Then the next caller is admitted although the cancelled supplier's future is still pending
        assertEquals("admitted", await(rejecting.execute(() -> Future.succeededFuture("admitted"))));

        // Given a queueing bulkhead of 1 with a caller waiting behind a running call
        ResiliencePipeline queueing = resilience
                .pipeline("cancel-active-queue")
                .bulkhead(bulkhead -> bulkhead.maxConcurrentCalls(1).queue(1, Duration.ofSeconds(30)))
                .build();
        ResilienceCancellation runningCancellation = ResilienceCancellation.create();
        AtomicInteger runningStarted = new AtomicInteger();
        Future<String> running = queueing.execute(
                () -> {
                    runningStarted.incrementAndGet();
                    return Promise.<String>promise().future();
                },
                runningCancellation);
        awaitCondition(() -> runningStarted.get() == 1);
        Future<String> waiting = queueing.execute(() -> Future.succeededFuture("from-the-queue"));

        // When the running call is cancelled
        runningCancellation.cancel(reason);
        assertSame(reason, awaitFailure(running));

        // Then the queued caller is admitted and runs
        assertEquals("from-the-queue", await(waiting));
    }

    /**
     * Supporting FR-030: cancelling a queued execution cancels its queue timer, so the timer does not
     * stay armed for the rest of the queue timeout.
     */
    @Test
    @DisplayName("cancelling a queued call cancels its queue timer")
    void cancellingAQueuedCallCancelsItsQueueTimer(Vertx vertx) throws Exception {
        // Given a runtime whose timers are recorded and a queue of 1 behind a running call
        RecordingTimerScheduler timers = new RecordingTimerScheduler(vertx);
        resilience = new Resilience(vertx, timers, () -> 0.5d, Set.of(recorder));
        ResiliencePipeline pipeline = resilience
                .pipeline("cancel-queue-timer")
                .bulkhead(bulkhead -> bulkhead.maxConcurrentCalls(1).queue(1, Duration.ofSeconds(30)))
                .build();
        AtomicInteger started = new AtomicInteger();
        Future<String> running = pipeline.execute(() -> {
            started.incrementAndGet();
            return Promise.<String>promise().future();
        });
        awaitCondition(() -> started.get() == 1);
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        Future<String> queued = pipeline.execute(() -> Future.succeededFuture("never"), cancellation);
        awaitCondition(() -> events.stream().anyMatch(BulkheadQueued.class::isInstance));
        assertEquals(1, timers.started().size(), "the queued call armed one queue timer");
        long queueTimerId = timers.started().get(0);
        assertFalse(timers.cancelled().contains(queueTimerId));

        // When the queued call is cancelled
        IllegalStateException reason = new IllegalStateException("stop");
        cancellation.cancel(reason);

        // Then it fails with the reason and its queue timer was cancelled
        assertSame(reason, awaitFailure(queued));
        assertEquals(List.of(queueTimerId), timers.cancelled());
        assertFalse(running.isComplete());
    }

    /**
     * Supporting FR-030: a cancel that arrives after the call completed normally does nothing: it does
     * not fail, and it does not give a second permit back.
     */
    @Test
    @DisplayName("a cancel after normal completion leaves the permits unchanged")
    void cancelAfterNormalCompletionLeavesThePermitsUnchanged(Vertx vertx) throws Exception {
        // Given a reject-mode bulkhead of 1 and a call that completed normally under a cancellation
        resilience = Resilience.create(vertx);
        ResiliencePipeline pipeline = resilience
                .pipeline("cancel-after-completion")
                .bulkhead(bulkhead -> bulkhead.maxConcurrentCalls(1).reject())
                .build();
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        assertEquals("done", await(pipeline.execute(() -> Future.succeededFuture("done"), cancellation)));

        // When the cancellation is cancelled afterwards
        IllegalStateException reason = new IllegalStateException("late");
        assertDoesNotThrow(() -> cancellation.cancel(reason));

        // Then the bulkhead still holds exactly one permit: one call runs, a second is rejected
        AtomicInteger started = new AtomicInteger();
        Promise<String> gate = Promise.promise();
        Future<String> first = pipeline.execute(() -> {
            started.incrementAndGet();
            return gate.future();
        });
        awaitCondition(() -> started.get() == 1);
        assertInstanceOf(
                BulkheadRejectedException.class,
                awaitFailure(pipeline.execute(() -> Future.succeededFuture("over-admitted"))));
        gate.complete("first");
        assertEquals("first", await(first));
    }

    /**
     * Supporting FR-030: a cancelled running call lets go of its operation, so a late result of an
     * operation future that is still held does not keep the operation reachable.
     */
    @Test
    @DisplayName("a cancelled running call does not keep its operation reachable")
    void cancelledRunningCallDoesNotKeepItsOperationReachable(Vertx vertx) throws Exception {
        // Given a running call whose operation captures a marker, and an operation future that stays held
        resilience = Resilience.create(vertx);
        ResiliencePipeline pipeline = resilience
                .pipeline("cancel-releases-operation")
                .bulkhead(bulkhead -> bulkhead.maxConcurrentCalls(1).reject())
                .build();
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        Promise<String> heldGate = Promise.promise();
        AtomicReference<Future<String>> execution = new AtomicReference<>();
        WeakReference<Object> marker = startCapturing(pipeline, cancellation, heldGate, execution);

        // When the call is cancelled
        IllegalStateException reason = new IllegalStateException("stop");
        cancellation.cancel(reason);
        assertSame(reason, awaitFailure(execution.get()));

        // Then the marker the operation captured can be collected although the future is still held
        assertTrue(awaitCollected(marker), "the cancelled call must release its operation");
        Reference.reachabilityFence(heldGate);
    }

    /**
     * Supporting FR-030: an execution handed to a bulkhead whose cancellation is already cancelled
     * fails with the reason without invoking the operation and without holding a permit.
     */
    @Test
    @DisplayName("an already cancelled cancellation fails the execution without a permit")
    void alreadyCancelledCancellationFailsTheExecutionWithoutAPermit(Vertx vertx) throws Exception {
        // Given a bulkhead of 1 and an observation whose cancellation is already cancelled
        resilience = Resilience.create(vertx);
        Bulkhead bulkhead = Bulkhead.builder(resilience, "bind-cancelled")
                .maxConcurrentCalls(1)
                .reject()
                .build();
        ResilienceCancellation cancellation = ResilienceCancellation.create();
        IllegalStateException reason = new IllegalStateException("stop");
        cancellation.cancel(reason);
        ResilienceExecutionObservation observation =
                new ResilienceExecutionObservation(resilience, bulkhead.stateKey(), Set.of(), cancellation);
        AtomicInteger invoked = new AtomicInteger();

        // When the execution is submitted to the bulkhead
        Future<String> result = bulkhead.execute(
                bulkhead.stateKey(),
                () -> {
                    invoked.incrementAndGet();
                    return Future.succeededFuture("must-not-run");
                },
                () -> true,
                ignored -> () -> {},
                observation);

        // Then it fails with the reason, the operation never ran and the permit is free
        assertSame(reason, awaitFailure(result));
        assertEquals(0, invoked.get());
        assertEquals("next", await(bulkhead.execute(() -> Future.succeededFuture("next"))));
    }

    /** Starts a pending execution in its own frame so that no local keeps the operation reachable. */
    private static WeakReference<Object> startCapturing(
            ResiliencePipeline pipeline,
            ResilienceCancellation cancellation,
            Promise<String> gate,
            AtomicReference<Future<String>> execution)
            throws InterruptedException {
        Object marker = new Object();
        AtomicInteger started = new AtomicInteger();
        execution.set(pipeline.execute(
                () -> {
                    Objects.requireNonNull(marker);
                    started.incrementAndGet();
                    return gate.future();
                },
                cancellation));
        awaitCondition(() -> started.get() == 1);
        return new WeakReference<>(marker);
    }
}
