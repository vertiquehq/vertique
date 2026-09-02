// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * TP-005: a {@link KeyedRateLimiter} must apply its selector function and dispatch through the
 * same decision path {@link RateLimiter#acquire(RateLimitKey)} exercises, not a separate or
 * unimplemented code path.
 */
class RateLimitersKeyedHandleTest {

    @Test
    void shouldAcquireThroughKeyedHandleUsingSelectorFunction() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            RateLimitPolicy policy = new RateLimitPolicy(
                    "keyed-skeleton",
                    true,
                    RateLimitMode.LOCAL,
                    RateLimitFailureMode.OPEN,
                    "r1",
                    1L,
                    new TokenBucketRateLimit(1L, new GreedyRateLimitRefill(1L, Duration.ofMillis(1_000L))));
            RateLimiters rateLimiters = RateLimitersUnitFixtures.withPolicies(vertx, policy);
            KeyedRateLimiter<String> keyed =
                    rateLimiters.limiter("keyed-skeleton", (String callerId) -> RateLimitKey.of(callerId));

            RateLimitDecision first = await(keyed.acquire("caller-a"));
            RateLimitDecision second = await(keyed.acquire("caller-a"));

            assertThat(first.outcome()).as("first acquire for caller-a").isEqualTo(RateLimitOutcome.PERMITTED);
            assertThat(second.outcome()).as("second acquire for caller-a").isEqualTo(RateLimitOutcome.QUOTA_EXCEEDED);
        } finally {
            vertx.close();
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }
}
