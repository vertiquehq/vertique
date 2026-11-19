// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience;

import static dev.vertique.resilience.ResilienceTestSupport.await;
import static dev.vertique.resilience.ResilienceTestSupport.awaitCollected;
import static dev.vertique.resilience.ResilienceTestSupport.awaitCondition;
import static dev.vertique.resilience.ResilienceTestSupport.awaitFailure;
import static dev.vertique.resilience.ResilienceTestSupport.block;
import static dev.vertique.resilience.ResilienceTestSupport.flush;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.resilience.spi.ResilienceObserver;
import dev.vertique.resilience.spi.event.AttemptStarted;
import dev.vertique.resilience.spi.event.ResilienceEvent;
import dev.vertique.resilience.spi.event.RetryScheduled;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Proof for the retry and timeout execution's cancellation edges: a cancel before the first attempt, a
 * late failure after a cancel, the release of the cancellation once the execution settles, and how
 * deep into a failure's cause chain a retry delay hint is searched.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class PolicyExecutionCancellationTest {

    /** The deepest cause-chain position (0 is the failure itself) at which a hint is still honored. */
    private static final int DEEPEST_HONORED_POSITION = 31;

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
     * Supporting FR-030: a cancel that arrives after the call was accepted and before the retry
     * execution starts fails it with the reason, without an attempt.
     */
    @Test
    @DisplayName("a cancel before the retry execution starts fails it without an attempt")
    void cancelBeforeTheRetryExecutionStartsFailsItWithoutAnAttempt(Vertx vertx) throws Exception {
        // Given a retry-only pipeline and an execution context that is busy
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResiliencePipeline pipeline = retryPipeline("cancel-before-start", 2, 10L, 0L);
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

        // Then the execution fails with the reason, with no invocation and no attempt reported
        assertSame(reason, awaitFailure(result));
        assertEquals(0, invocations.get());
        assertTrue(events.stream().noneMatch(AttemptStarted.class::isInstance));
    }

    /**
     * Supporting FR-030: an attempt that fails after the execution was cancelled is not retried and no
     * retry is reported for it.
     */
    @Test
    @DisplayName("a failure that arrives after the cancel schedules no retry")
    void failureThatArrivesAfterTheCancelSchedulesNoRetry(Vertx vertx) throws Exception {
        // Given a pipeline with 2 retries and an attempt that is pending
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResiliencePipeline pipeline = retryPipeline("late-failure", 2, 10L, 0L);
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

        // When the execution is cancelled and the attempt then fails
        IllegalStateException reason = new IllegalStateException("stop");
        cancellation.cancel(reason);
        assertSame(reason, awaitFailure(result));
        gate.fail(new IllegalStateException("late"));
        flush(resilience.executionContext());

        // Then no retry was reported or started, and the future still holds the reason
        assertTrue(events.stream().noneMatch(RetryScheduled.class::isInstance), "no retry for a cancelled execution");
        assertEquals(1, invocations.get());
        assertSame(reason, result.cause());
    }

    /**
     * Supporting FR-032: a retry delay hint is found in the first 32 links of the cause chain, counting
     * the failure itself, and not beyond.
     */
    @Test
    @DisplayName("a retry delay hint is searched for in the first 32 links of the cause chain")
    void retryDelayHintIsSearchedForInTheFirst32LinksOfTheCauseChain(Vertx vertx) throws Exception {
        // Given a retry of 1 that honors hints up to 5000 ms with a sampled backoff of 10 ms
        resilience = Resilience.create(vertx, Set.of(recorder));
        ResiliencePipeline pipeline = retryPipeline("hint-depth", 1, 10L, 5_000L);

        // When the failure carries a 40 ms hint at the 32nd link (position 31)
        assertEquals(
                "recovered",
                await(pipeline.execute(
                        failOnceWith(chainWithHintAt(DEEPEST_HONORED_POSITION, Duration.ofMillis(40L))))));

        // Then the hint is found and the retry is delayed by it
        assertEquals(List.of(40L), scheduledDelays());

        // When the failure carries the same hint at the 33rd link (position 32)
        events.clear();
        assertEquals(
                "recovered",
                await(pipeline.execute(
                        failOnceWith(chainWithHintAt(DEEPEST_HONORED_POSITION + 1, Duration.ofMillis(40L))))));

        // Then the hint is not found and the retry is delayed by the sampled backoff alone
        assertEquals(List.of(10L), scheduledDelays());
    }

    /**
     * Supporting FR-030: once a retry execution settles with a value, the cancellation no longer holds
     * the execution, so it does not keep the operation alive.
     */
    @Test
    @DisplayName("a retry execution that succeeded is released by the cancellation")
    void retryExecutionThatSucceededIsReleasedByTheCancellation(Vertx vertx) throws Exception {
        // Given a retry execution under a held cancellation whose operation captures a marker
        resilience = Resilience.create(vertx);
        ResiliencePipeline pipeline = retryPipeline("release-on-success", 1, 1L, 0L);
        ResilienceCancellation cancellation = ResilienceCancellation.create();

        // When it succeeds
        WeakReference<Object> marker = runCapturing(pipeline, cancellation, true);

        // Then the held cancellation does not keep the operation reachable
        assertTrue(awaitCollected(marker), "the settled execution must unregister from the cancellation");
        Reference.reachabilityFence(cancellation);
    }

    /**
     * Supporting FR-030: once a retry execution settles with a failure, the cancellation no longer holds
     * the execution, so it does not keep the operation alive.
     */
    @Test
    @DisplayName("a retry execution that failed is released by the cancellation")
    void retryExecutionThatFailedIsReleasedByTheCancellation(Vertx vertx) throws Exception {
        // Given a retry execution under a held cancellation whose operation captures a marker
        resilience = Resilience.create(vertx);
        ResiliencePipeline pipeline = retryPipeline("release-on-failure", 0, 1L, 0L);
        ResilienceCancellation cancellation = ResilienceCancellation.create();

        // When it fails
        WeakReference<Object> marker = runCapturing(pipeline, cancellation, false);

        // Then the held cancellation does not keep the operation reachable
        assertTrue(awaitCollected(marker), "the settled execution must unregister from the cancellation");
        Reference.reachabilityFence(cancellation);
    }

    private ResiliencePipeline retryPipeline(String name, int maxRetries, long delayMs, long retryAfterMaxMs) {
        return resilience
                .pipeline(name)
                .retry(retry -> retry.maxRetries(maxRetries)
                        .backoff(RetryBackoff.fixed(delayMs))
                        .retryAfterMaxMs(retryAfterMaxMs))
                .build();
    }

    private List<Long> scheduledDelays() {
        return events.stream()
                .filter(RetryScheduled.class::isInstance)
                .map(RetryScheduled.class::cast)
                .map(RetryScheduled::delayMs)
                .toList();
    }

    private static Supplier<Future<String>> failOnceWith(Throwable failure) {
        AtomicInteger attempts = new AtomicInteger();
        return () ->
                attempts.getAndIncrement() == 0 ? Future.failedFuture(failure) : Future.succeededFuture("recovered");
    }

    /** Builds a cause chain whose link at {@code position} (0 is the outermost) carries the hint. */
    private static Throwable chainWithHintAt(int position, Duration hint) {
        Throwable current = new HintedFailure(hint);
        for (int link = 0; link < position; link++) {
            current = new IllegalStateException("wrapper", current);
        }
        return current;
    }

    /** Runs one execution in its own frame so that no local keeps the operation reachable. */
    private static WeakReference<Object> runCapturing(
            ResiliencePipeline pipeline, ResilienceCancellation cancellation, boolean succeed) throws Exception {
        Object marker = new Object();
        Future<String> result = pipeline.execute(
                () -> {
                    Objects.requireNonNull(marker);
                    return succeed
                            ? Future.succeededFuture("done")
                            : Future.failedFuture(new IllegalStateException("failed"));
                },
                cancellation);
        if (succeed) {
            assertEquals("done", await(result));
        } else {
            assertEquals("failed", awaitFailure(result).getMessage());
        }
        return new WeakReference<>(marker);
    }

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
