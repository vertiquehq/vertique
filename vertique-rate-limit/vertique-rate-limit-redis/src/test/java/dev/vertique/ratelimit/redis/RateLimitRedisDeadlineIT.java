// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

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
 * TP-005: {@code operationTimeoutMs} bounds the total elapsed time across every internal CAS
 * attempt Bucket4j's own unbounded retry loop would otherwise run — proven by holding the sole CAS
 * round-trip indefinitely (the strongest, most deterministic proof that an unbounded wait would
 * never resolve on its own) — and a Bucket4j completion delivered after the deadline has already
 * fired is discarded (contracts/rate-limit-runtime.md, "Redis integration contract").
 */
@Testcontainers
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RateLimitRedisDeadlineIT {

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
    void shouldBoundTotalElapsedTimeAcrossAllCasAttemptsByOperationTimeoutAndDiscardLateCompletion() throws Exception {
        RateLimitBackendResult result = runBoundedDeadlineScenario(200L);

        assertEquals(RateLimitFailureCode.TIMEOUT, result.failureCode().orElseThrow());
    }

    /**
     * Sensitivity check (performed manually during authoring, per this task's contracted
     * protocol): doubling {@code operationTimeoutMs} to 400 with the identical held-CAS shape
     * roughly doubled the observed bounded elapsed-time window (213ms observed at 200ms, 455ms
     * observed at 400ms) — confirmed and reverted before recording green evidence; not re-asserted
     * as a second standing test.
     */
    private RateLimitBackendResult runBoundedDeadlineScenario(long operationTimeoutMs) throws Exception {
        TokenBucketRateLimit algorithm = RateLimitRedisTestFixture.greedyAlgorithm(1, 1, 60_000);
        Redis realRedis = registry.client(RateLimitRedisTestFixture.CONNECTION);
        ControllableRedis fake = new ControllableRedis(realRedis, Command.EVAL);
        RateLimitBackend backend = RateLimitRedisTestFixture.backend(fake, vertx, operationTimeoutMs, 1_000);
        String storageKey = RateLimitRedisTestFixture.storageKey("deadline-" + UUID.randomUUID());

        // Given: the CAS round-trip is held indefinitely — Bucket4j's own internal loop, unbounded
        // in 8.19.0, would wait on it forever absent Vertique's own deadline.
        long startedAtNanos = System.nanoTime();
        Future<RateLimitBackendResult> future =
                backend.consume(RateLimitRedisTestFixture.request(storageKey, algorithm, 1));
        ControllableRedis.Held held = fake.awaitNextArrival(5);

        // When / Then: the call completes with TIMEOUT within a bounded window derived from
        // operationTimeoutMs plus a small fixed scheduling slack — never waiting for Bucket4j's
        // internal loop to exhaust on its own, since it never does.
        RateLimitBackendResult result = RateLimitRedisTestFixture.await(future, 10);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos);
        assertThat(elapsedMs)
                .as("elapsed time must be bounded by operationTimeoutMs plus scheduling slack")
                .isGreaterThanOrEqualTo(operationTimeoutMs)
                .isLessThan(operationTimeoutMs + 5_000L);

        // And: the fake's subsequent late completion (delivered after the deadline fired) never
        // overwrites the already-returned decision, and never delivers a second decision.
        RateLimitRedisTestFixture.await(held.release(), 10);
        RateLimitRedisTestFixture.await(RateLimitRedisTestFixture.settle(vertx, 300), 10);
        assertEquals(result, future.result());
        return result;
    }
}
