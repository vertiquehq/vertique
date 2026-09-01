// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitObserver;
import dev.vertique.ratelimit.spi.event.RateLimitEvent;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * M1 (review repair): {@link RateLimiter#acquire(RateLimitKey, long)} must always yield a
 * decision (decision-first) even when the bound backend fails outright — a rejected future, or a
 * synchronous throw before any future is even returned. Both normalize into the same {@code
 * BACKEND_FAILURE_OPEN}/{@code BACKEND_FAILURE_CLOSED} shape {@code classifyOutcome} already
 * produces for an in-band backend failure: {@code failureCode == INTERNAL}, {@code remaining}/
 * {@code retryAfter}/{@code resetAfter} all empty, classified per this policy's {@code
 * failureMode}, and {@link dev.vertique.ratelimit.spi.event.RateLimitDecisionCompleted} is still
 * emitted exactly once.
 */
class RateLimiterBackendFailureNormalizationTest {

    private static final Vertx VERTX = Vertx.vertx();
    private static final String FAILURE_MESSAGE = "backend-is-broken";

    @AfterAll
    static void closeVertx() {
        VERTX.close();
    }

    private static final RateLimitBackend REJECTED_FUTURE_BACKEND =
            request -> Future.failedFuture(new RuntimeException(FAILURE_MESSAGE));

    private static final RateLimitBackend SYNCHRONOUS_THROW_BACKEND = request -> {
        throw new RuntimeException(FAILURE_MESSAGE);
    };

    static Stream<MatrixRow> m1BackendFailureMatrix() {
        return Stream.of(
                new MatrixRow(
                        "rejectedFuture+OPEN",
                        () -> shouldNormalize(
                                REJECTED_FUTURE_BACKEND,
                                RateLimitFailureMode.OPEN,
                                RateLimitOutcome.BACKEND_FAILURE_OPEN)),
                new MatrixRow(
                        "rejectedFuture+CLOSED",
                        () -> shouldNormalize(
                                REJECTED_FUTURE_BACKEND,
                                RateLimitFailureMode.CLOSED,
                                RateLimitOutcome.BACKEND_FAILURE_CLOSED)),
                new MatrixRow(
                        "synchronousThrow+OPEN",
                        () -> shouldNormalize(
                                SYNCHRONOUS_THROW_BACKEND,
                                RateLimitFailureMode.OPEN,
                                RateLimitOutcome.BACKEND_FAILURE_OPEN)),
                new MatrixRow(
                        "synchronousThrow+CLOSED",
                        () -> shouldNormalize(
                                SYNCHRONOUS_THROW_BACKEND,
                                RateLimitFailureMode.CLOSED,
                                RateLimitOutcome.BACKEND_FAILURE_CLOSED)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("m1BackendFailureMatrix")
    @DisplayName("normalizes a failed/throwing backend into a decision-first BACKEND_FAILURE_* outcome")
    void shouldEnforceM1BackendFailureNormalizationMatrix(MatrixRow row) throws Throwable {
        row.proof().execute();
    }

    private static void shouldNormalize(
            RateLimitBackend backend, RateLimitFailureMode failureMode, RateLimitOutcome expectedOutcome) {
        RateLimitPolicy policy = policy("backend-failure-quota", failureMode);
        RecordingObserver observer = new RecordingObserver();
        RateLimiters rateLimiters = RateLimitersUnitFixtures.withBackend(VERTX, backend, Set.of(observer), policy);

        Future<RateLimitDecision> future = rateLimiters.limiter(policy.name()).acquire(RateLimitKey.of("row-key"));

        assertThat(future.succeeded())
                .as(expectedOutcome + ": acquire() future still succeeds (decision-first), never fails")
                .isTrue();
        RateLimitDecision decision = future.result();
        assertThat(decision.outcome()).as(expectedOutcome + ": outcome").isEqualTo(expectedOutcome);
        assertThat(decision.failureCode())
                .as(expectedOutcome + ": failureCode")
                .contains(RateLimitFailureCode.INTERNAL);
        assertThat(decision.remaining()).as(expectedOutcome + ": remaining").isEmpty();
        assertThat(decision.retryAfter()).as(expectedOutcome + ": retryAfter").isEmpty();
        assertThat(decision.resetAfter()).as(expectedOutcome + ": resetAfter").isEmpty();
        assertThat(observer.received())
                .as(expectedOutcome + ": event still emitted exactly once")
                .hasSize(1);
    }

    private static RateLimitPolicy policy(String name, RateLimitFailureMode failureMode) {
        return new RateLimitPolicy(
                name,
                true,
                RateLimitMode.LOCAL,
                failureMode,
                "r1",
                1L,
                new TokenBucketRateLimit(10L, new GreedyRateLimitRefill(10L, Duration.ofSeconds(60))));
    }

    /** One named matrix row: an identifier plus its self-contained decisive proof. */
    private record MatrixRow(String name, Executable proof) {
        @Override
        public String toString() {
            return name;
        }
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
