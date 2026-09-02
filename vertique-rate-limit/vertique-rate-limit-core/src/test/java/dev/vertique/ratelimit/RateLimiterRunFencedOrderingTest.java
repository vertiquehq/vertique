// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.ratelimit.exception.RateLimitUnavailableException;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * M3 (review repair): {@code RateLimiter#runFenced} must register this call's lifecycle fence
 * <strong>before</strong> invoking the guarded action, so a {@link RateLimiters#close()} that
 * becomes observable at any point up to and including registration is guaranteed to pre-empt the
 * action — it must never run once close is observable.
 *
 * <p>Deterministic, single-threaded reproduction of the race window between {@code acquire()}'s
 * decision completing and {@code runFenced}'s registration: the fake backend below calls {@code
 * close()} synchronously from inside {@code consume()}, i.e. strictly after the decision that
 * admits this call has been computed but strictly before control returns to {@code
 * execute(...)}'s {@code compose(runGuarded)} continuation (which is where {@code runFenced} —
 * and, before the fix, the guarded action itself — runs). No threads, no sleeps: everything here
 * executes on one call stack, on the calling thread, so the ordering is exact and repeatable.
 */
class RateLimiterRunFencedOrderingTest {

    @Test
    void shouldNeverInvokeTheActionOnceCloseBecomesObservableBeforeRunFencedRegisters() {
        Vertx vertx = Vertx.vertx();
        try {
            RateLimitPolicy policy = policy("run-fenced-ordering-quota");
            AtomicInteger invocations = new AtomicInteger();
            AtomicReference<RateLimiters> runtimeRef = new AtomicReference<>();

            RateLimitBackend closingDuringConsumeBackend = request -> {
                // Close becomes observable here — strictly between acquire()'s decision and
                // runFenced's registration, since consume() is called from inside acquire(),
                // before execute()'s compose(runGuarded) continuation ever runs.
                runtimeRef.get().close();
                return Future.succeededFuture(
                        new RateLimitBackendResult(true, 9L, Optional.empty(), Optional.empty(), Optional.empty()));
            };
            RateLimiters rateLimiters =
                    RateLimitersUnitFixtures.withBackend(vertx, closingDuringConsumeBackend, policy);
            runtimeRef.set(rateLimiters);

            Future<String> result = rateLimiters.limiter(policy.name()).execute(RateLimitKey.of("row-key"), () -> {
                invocations.incrementAndGet();
                return Future.succeededFuture("should-never-run");
            });

            assertThat(invocations.get())
                    .as("the action must never run once close is observable, even though the decision admitted it")
                    .isEqualTo(0);
            assertThat(result.failed())
                    .as("the guarded future fails once close pre-empts the action")
                    .isTrue();
            assertThat(result.cause())
                    .as("the guarded future's fenced failure type")
                    .isInstanceOf(RateLimitUnavailableException.class);
        } finally {
            vertx.close();
        }
    }

    private static RateLimitPolicy policy(String name) {
        return new RateLimitPolicy(
                name,
                true,
                RateLimitMode.LOCAL,
                RateLimitFailureMode.OPEN,
                "r1",
                1L,
                new TokenBucketRateLimit(10L, new GreedyRateLimitRefill(10L, Duration.ofSeconds(60))));
    }
}
