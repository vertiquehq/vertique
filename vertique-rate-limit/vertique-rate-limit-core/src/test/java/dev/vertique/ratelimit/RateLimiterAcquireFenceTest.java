// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * External deep-review finding 3: a direct {@link RateLimiter#acquire(RateLimitKey)} call's
 * backend consumption was never registered with this runtime's shared {@link
 * RateLimiterLifecycle} — only the ones reached through {@code execute(...)}'s {@code runFenced}
 * were. {@code dev.vertique.ratelimit.redis.RedisRateLimitFenceLifecycle}'s own javadoc claims
 * "fencing any in-flight {@code consume(...)}" ahead of the shared Redis client's close, but a
 * direct {@code acquire(...)} call was invisible to {@link RateLimiters#close()} entirely: it just
 * ran to completion, unattended, whatever else was happening.
 *
 * <p>Decision-first semantics at the entry check are unaffected: a runtime that is <em>already</em>
 * closed before {@code acquire(...)} is even called still fails immediately with the closed
 * exception (unchanged, proven by the existing {@code RateLimiterRunFencedOrderingTest} sibling
 * coverage and every other {@code acquire()} test in this module). This test covers the
 * <em>in-flight</em> case: an {@code acquire()} already under way against a backend that has not
 * yet settled.
 */
class RateLimiterAcquireFenceTest {

    private static final Vertx VERTX = Vertx.vertx();

    @AfterAll
    static void closeVertx() {
        VERTX.close();
    }

    @Test
    void shouldFenceAnInFlightDirectAcquireConsumeOnClose() {
        RateLimitPolicy policy = new RateLimitPolicy(
                "acquire-fence-quota",
                true,
                RateLimitMode.LOCAL,
                RateLimitFailureMode.CLOSED,
                "r1",
                1L,
                new TokenBucketRateLimit(10L, new GreedyRateLimitRefill(10L, Duration.ofSeconds(60))));
        Promise<RateLimitBackendResult> heldConsume = Promise.promise();
        RateLimitBackend heldBackend = request -> heldConsume.future();
        RateLimiters rateLimiters = RateLimitersUnitFixtures.withBackend(VERTX, heldBackend, policy);

        Future<RateLimitDecision> acquireFuture =
                rateLimiters.limiter(policy.name()).acquire(RateLimitKey.of("row-key"));

        assertThat(acquireFuture.isComplete())
                .as("the backend consume is held pending, so the direct acquire() call must not have completed yet")
                .isFalse();

        rateLimiters.close();

        assertThat(acquireFuture.isComplete())
                .as("closing the runtime must fence this in-flight direct acquire() consume, exactly like a "
                        + "guarded execute() action is already fenced -- otherwise close() ignores it entirely and "
                        + "the caller waits on a consume that may never settle")
                .isTrue();
        assertThat(acquireFuture.succeeded())
                .as("acquire() still yields a decision (decision-first), never a failed future, for a fenced "
                        + "backend failure")
                .isTrue();
        assertThat(acquireFuture.result().outcome())
                .as("a fenced consume is classified as a backend failure, per this policy's CLOSED failureMode")
                .isEqualTo(RateLimitOutcome.BACKEND_FAILURE_CLOSED);
    }
}
