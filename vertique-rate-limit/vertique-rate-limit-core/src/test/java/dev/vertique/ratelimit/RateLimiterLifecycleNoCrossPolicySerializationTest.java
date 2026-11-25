// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

/**
 * A single {@link RateLimiter}'s {@code acquire}/{@code execute} does not hold the shared
 * {@link RateLimiterLifecycle} monitor across {@code backend.consume(...)}'s synchronous call
 * ({@code LocalBucket4jRateLimitBackend} is fully synchronous), so one policy's slow or blocked
 * admission never serializes another policy's admission behind it, regardless of key or bucket.
 *
 * <p>This test proves it: while policy A's backend consume is blocked mid-call (on a
 * real, separate thread — this genuinely is a cross-thread scheduling property, not something a
 * single-threaded ordering trick can substitute for), policy B's {@code acquire} on another thread
 * must complete without ever waiting on policy A's hold. Determinism comes from {@link
 * CountDownLatch}es marking real state transitions (backend entry, thread completion), not from
 * elapsed time; the bounded {@code join} timeouts below are a deadlock safety net only, never the
 * basis of the assertion itself — the real assertion is the boolean flag a completed thread sets.
 */
class RateLimiterLifecycleNoCrossPolicySerializationTest {

    private static final Vertx VERTX = Vertx.vertx();
    private static final Duration JOIN_TIMEOUT = Duration.ofSeconds(10);

    @AfterAll
    static void closeVertx() {
        VERTX.close();
    }

    @Test
    void policyBsAcquireMustNotBlockBehindPolicyAsInFlightBackendConsume() throws InterruptedException {
        RateLimitPolicy policyA = policy("noserialization-policy-a");
        RateLimitPolicy policyB = policy("noserialization-policy-b");

        CountDownLatch policyAConsumeStarted = new CountDownLatch(1);
        CountDownLatch releasePolicyAConsume = new CountDownLatch(1);
        RateLimitBackend backend = request -> {
            if (request.storageKey().startsWith(policyA.name() + ":")) {
                policyAConsumeStarted.countDown();
                awaitUninterruptibly(releasePolicyAConsume);
            }
            return Future.succeededFuture(permitted());
        };
        RateLimiters rateLimiters = RateLimitersUnitFixtures.withBackend(VERTX, backend, policyA, policyB);

        Thread policyAThread =
                new Thread(() -> rateLimiters.limiter(policyA.name()).acquire(RateLimitKey.of("a-key")));
        policyAThread.setDaemon(true);
        policyAThread.start();
        assertThat(policyAConsumeStarted.await(JOIN_TIMEOUT.toSeconds(), TimeUnit.SECONDS))
                .as("policy A's backend consume must have actually started before this test proceeds")
                .isTrue();

        boolean[] policyBCompleted = {false};
        Thread policyBThread = new Thread(() -> {
            rateLimiters.limiter(policyB.name()).acquire(RateLimitKey.of("b-key"));
            policyBCompleted[0] = true;
        });
        policyBThread.setDaemon(true);
        policyBThread.start();
        policyBThread.join(JOIN_TIMEOUT.toMillis());

        assertThat(policyBCompleted[0])
                .as("policy B's acquire must complete while policy A's backend consume is still blocked -- a "
                        + "single shared RateLimiterLifecycle monitor must never serialize admission across "
                        + "policies; a design that held the lock across the invocation would leave this thread "
                        + "blocked trying to enter registerAndInvoke's critical section")
                .isTrue();

        releasePolicyAConsume.countDown();
        policyAThread.join(JOIN_TIMEOUT.toMillis());
        assertThat(policyAThread.isAlive())
                .as("policy A's thread must have finished once released")
                .isFalse();

        rateLimiters.close();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private static RateLimitBackendResult permitted() {
        return new RateLimitBackendResult(true, 9L, Optional.empty(), Optional.empty(), Optional.empty());
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
