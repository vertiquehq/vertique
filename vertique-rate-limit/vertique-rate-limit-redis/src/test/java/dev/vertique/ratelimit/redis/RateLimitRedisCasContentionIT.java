// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.ratelimit.RateLimitFailureCode;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import dev.vertique.redis.RedisClientRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * TP-001: multi-client CAS contention for the same physical key, driven through a deterministic
 * barrier-fake ({@link ControllableRedis}) in front of the shared Redis connection — at most
 * {@code capacity} clients commit, every remaining client's outcome is exactly {@code
 * QUOTA_EXCEEDED} or {@code TIMEOUT} (never {@code CONTENTION_EXHAUSTED}), and a client held past
 * its own deadline still proves its late Bucket4j completion is discarded
 * (contracts/rate-limit-runtime.md, "Redis integration contract").
 */
@Testcontainers
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RateLimitRedisCasContentionIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(RateLimitRedisTestFixture.REDIS_IMAGE)).withExposedPorts(6379);

    private static Vertx vertx;
    private static RedisClientRegistry registry;

    @BeforeAll
    static void setUp(Vertx hostVertx) {
        vertx = hostVertx;
        registry = RateLimitRedisTestFixture.registry(vertx, REDIS.getHost(), REDIS.getMappedPort(6379));
    }

    @AfterAll
    static void tearDown() throws Exception {
        RateLimitRedisTestFixture.await(registry.close());
    }

    @Test
    void shouldNeverRetryPastDeadlineUnderMultiClientCasContention() throws Exception {
        // Generous relative to a Testcontainers-local round-trip so the winner's genuine commit
        // response is never mistaken for a timeout under real (variable) system/CI scheduling load.
        long operationTimeoutMs = 1_500L;
        TokenBucketRateLimit algorithm = RateLimitRedisTestFixture.greedyAlgorithm(1, 1, 60_000);
        Redis realRedis = registry.client(RateLimitRedisTestFixture.CONNECTION);
        ControllableRedis fake = new ControllableRedis(realRedis, Command.EVAL);

        // --- Phase 1: N independent clients contend for the same physical key (capacity 1). ---
        String contendedKey = RateLimitRedisTestFixture.storageKey("contention-" + UUID.randomUUID());
        int clientCount = 4;
        List<RateLimitBackend> clients = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            clients.add(RateLimitRedisTestFixture.backend(fake, vertx, operationTimeoutMs, 1_000));
        }

        // When: all N clients call consume(...), released through the barrier-fake in controlled
        // rounds.
        long dispatchStartedAtNanos = System.nanoTime();
        List<Future<RateLimitBackendResult>> futures = new ArrayList<>();
        for (RateLimitBackend client : clients) {
            futures.add(client.consume(RateLimitRedisTestFixture.request(contendedKey, algorithm, 1)));
        }
        List<ControllableRedis.Held> held = new ArrayList<>();
        for (int i = 0; i < clientCount; i++) {
            held.add(fake.awaitNextArrival(5));
        }
        assertEquals(clientCount, fake.totalArrivals(), "every client's first attempt must have arrived exactly once");

        // Release exactly one held call — the sole winner for a capacity-1 bucket — and leave the
        // rest held past their own deadline (never released, forcing a deterministic timeout, not a
        // free-running race). Which of the four concurrently-dispatched clients' attempt arrived
        // first at the fake (and is therefore the one released) is immaterial — the assertions
        // below examine the aggregate outcome set across all four futures, not an assumed
        // dispatch-order-to-arrival-order correlation.
        held.get(0).release();

        List<RateLimitBackendResult> results = new ArrayList<>();
        for (Future<RateLimitBackendResult> future : futures) {
            results.add(RateLimitRedisTestFixture.await(future, 10));
        }
        long elapsedSinceDispatchNanos = System.nanoTime() - dispatchStartedAtNanos;

        // Then: at most capacity (1) client committed — the released one — with the expected
        // remaining.
        List<RateLimitBackendResult> committed =
                results.stream().filter(RateLimitBackendResult::consumed).toList();
        assertEquals(1, committed.size(), "exactly the released client must have committed: " + results);
        assertEquals(0L, committed.get(0).remaining());

        // Every remaining (un-released) client's outcome is one of exactly {QUOTA_EXCEEDED, TIMEOUT},
        // never CONTENTION_EXHAUSTED or any other failure code.
        List<RateLimitBackendResult> remainder =
                results.stream().filter(result -> !result.consumed()).toList();
        assertEquals(clientCount - 1, remainder.size());
        for (RateLimitBackendResult result : remainder) {
            assertTrue(
                    result.failureCode().isPresent()
                                    && result.failureCode().orElseThrow() == RateLimitFailureCode.TIMEOUT
                            || result.failureCode().isEmpty(),
                    "outcome must be TIMEOUT or QUOTA_EXCEEDED, was " + result);
            result.failureCode().ifPresent(code -> assertEquals(RateLimitFailureCode.TIMEOUT, code));
        }
        assertThat(elapsedSinceDispatchNanos)
                .as("un-released clients must have been bounded by operationTimeoutMs, not run free")
                .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(operationTimeoutMs - 5));
        assertEquals(
                clientCount,
                fake.totalArrivals(),
                "no further round-trip may be initiated by Vertique after each deadline fired");

        // --- Phase 2: a client held past its own deadline still delivers a Bucket4j completion —
        // proven discarded. ---
        String isolatedKey = RateLimitRedisTestFixture.storageKey("late-" + UUID.randomUUID());
        RateLimitBackend lateClient = RateLimitRedisTestFixture.backend(fake, vertx, operationTimeoutMs, 1_000);
        Future<RateLimitBackendResult> lateFuture =
                lateClient.consume(RateLimitRedisTestFixture.request(isolatedKey, algorithm, 1));
        ControllableRedis.Held lateHeld = fake.awaitNextArrival(5);

        RateLimitBackendResult timedOut = RateLimitRedisTestFixture.await(lateFuture, 10);
        assertEquals(RateLimitFailureCode.TIMEOUT, timedOut.failureCode().orElseThrow());
        assertTrue(lateFuture.isComplete());

        // The fake's release now arrives strictly after the deadline already fired; the uncontended
        // candidate would otherwise commit, but the already-returned decision must never change.
        RateLimitRedisTestFixture.await(lateHeld.release(), 10);
        RateLimitRedisTestFixture.await(RateLimitRedisTestFixture.settle(vertx, 300), 10);
        RateLimitBackendResult afterLateRelease = lateFuture.result();
        assertEquals(
                timedOut, afterLateRelease, "a late Bucket4j completion must never overwrite the TIMEOUT decision");
    }
}
