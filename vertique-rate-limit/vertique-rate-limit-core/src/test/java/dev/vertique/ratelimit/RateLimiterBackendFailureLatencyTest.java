// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitObserver;
import dev.vertique.ratelimit.spi.event.RateLimitDecisionCompleted;
import dev.vertique.ratelimit.spi.event.RateLimitEvent;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * External deep-review finding 4: the failure-recovery path (a backend that rejects its returned
 * future, or a fenced consume) emitted {@code backendLatencyNanos=0} unconditionally, discarding
 * the elapsed time actually spent waiting on the backend before it failed — unlike the successful
 * path, which already threads real elapsed time through {@code completeDecision}. Observability
 * consumers (metrics, tracing) lose real backend latency data specifically on the failure path,
 * the one case where it matters most for diagnosing a slow-then-failing backend.
 */
class RateLimiterBackendFailureLatencyTest {

    private static final Vertx VERTX = Vertx.vertx();

    @AfterAll
    static void closeVertx() {
        VERTX.close();
    }

    @Test
    void shouldEmitPositiveBackendLatencyOnTheFailurePath() {
        RateLimitPolicy policy = new RateLimitPolicy(
                "failure-latency-quota",
                true,
                RateLimitMode.LOCAL,
                RateLimitFailureMode.OPEN,
                "r1",
                1L,
                new TokenBucketRateLimit(10L, new GreedyRateLimitRefill(10L, Duration.ofSeconds(60))));
        RecordingObserver observer = new RecordingObserver();

        // A backend that fails only after real, measurable work has happened on the calling
        // thread between request receipt and failure -- no wall-clock sleep, just enough
        // synchronous computation that System.nanoTime() is guaranteed to have advanced.
        RateLimitBackend delayedFailingBackend = request -> {
            Promise<dev.vertique.ratelimit.spi.RateLimitBackendResult> promise = Promise.promise();
            long busyWorkAccumulator = 0L;
            for (int i = 0; i < 200_000; i++) {
                busyWorkAccumulator += System.nanoTime();
            }
            promise.fail(new RuntimeException("backend-failed-after-delay: " + busyWorkAccumulator));
            return promise.future();
        };

        RateLimiters rateLimiters =
                RateLimitersUnitFixtures.withBackend(VERTX, delayedFailingBackend, Set.of(observer), policy);

        Future<RateLimitDecision> future = rateLimiters.limiter(policy.name()).acquire(RateLimitKey.of("row-key"));

        assertThat(future.succeeded())
                .as("acquire() still succeeds (decision-first) even though the backend failed")
                .isTrue();
        assertThat(future.result().outcome())
                .as("outcome classifies as a backend failure per this policy's OPEN failureMode")
                .isEqualTo(RateLimitOutcome.BACKEND_FAILURE_OPEN);

        assertThat(observer.received()).hasSize(1);
        RateLimitDecisionCompleted event =
                (RateLimitDecisionCompleted) observer.received().get(0);
        assertThat(event.backendLatencyNanos())
                .as("backendLatencyNanos on the failure-recovery path must reflect real elapsed time spent in "
                        + "the backend before it failed, never a hardcoded 0")
                .isGreaterThan(0L);
    }

    /** Records every {@link RateLimitEvent} it receives; never throws. */
    private static final class RecordingObserver implements RateLimitObserver {
        private final List<RateLimitEvent> received = new ArrayList<>();

        @Override
        public void onEvent(RateLimitEvent event) {
            received.add(event);
        }

        List<RateLimitEvent> received() {
            return received;
        }
    }
}
