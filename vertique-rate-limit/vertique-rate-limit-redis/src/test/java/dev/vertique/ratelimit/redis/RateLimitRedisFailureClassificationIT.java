// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.ratelimit.RateLimitFailureCode;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import dev.vertique.redis.RedisClientRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
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
 * TP-002: an ambiguous mid-CAS timeout, forced via Redis's own {@code DEBUG SLEEP}, classifies as
 * a failure (never a committed consumption, never {@code CONTENTION_EXHAUSTED}) with no additional
 * logical attempt initiated by Vertique (contracts/rate-limit-runtime.md, "Redis integration
 * contract").
 */
@Testcontainers
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RateLimitRedisFailureClassificationIT {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
                    DockerImageName.parse(RateLimitRedisTestFixture.REDIS_IMAGE))
            .withExposedPorts(6379)
            // DEBUG SLEEP is disallowed by default in Redis 7.2 unless explicitly enabled;
            // this test forces a genuine server-side mid-CAS delay with it (see the "Given"
            // in each scenario below).
            .withCommand("redis-server", "--enable-debug-command", "yes");

    private static Vertx vertx;
    private static RedisClientRegistry registry;
    private static RedisAPI rawCommands;

    @BeforeAll
    static void setUp(Vertx hostVertx) throws Exception {
        vertx = hostVertx;
        registry = RateLimitRedisTestFixture.registry(vertx, REDIS.getHost(), REDIS.getMappedPort(6379));
        rawCommands = RedisAPI.api(registry.client(RateLimitRedisTestFixture.CONNECTION));
        // Warm the connection so the later fire-and-forget DEBUG SLEEP does not race a fresh
        // connection handshake against the backend's own first command.
        RateLimitRedisTestFixture.await(rawCommands.ping(List.of()));
    }

    @AfterAll
    static void tearDown() throws Exception {
        RateLimitRedisTestFixture.await(registry.close());
    }

    @Test
    void shouldClassifyAmbiguousTimeoutAsFailureNotQuotaDenialWithoutExtraRetry() throws Exception {
        // Given: a short operationTimeoutMs, and a request forced to exceed that deadline mid-CAS
        // via Redis's own DEBUG SLEEP, issued immediately before the backend's consume() call.
        RateLimitBackendResult result = runOnceWithForcedServerDelay(2_000L, 300L);

        // Then: the result carries failureCode = TIMEOUT (or UNAVAILABLE), never a committed
        // consumption and never CONTENTION_EXHAUSTED.
        assertFalse(result.consumed());
        assertThat(result.failureCode()).isPresent();
        assertThat(result.failureCode().orElseThrow())
                .isIn(RateLimitFailureCode.TIMEOUT, RateLimitFailureCode.UNAVAILABLE);
    }

    /**
     * Sensitivity proof: a server delay shorter than {@code operationTimeoutMs} must flip the
     * outcome to a normal committed consumption.
     */
    @Test
    void shouldPermitWhenServerDelayIsShorterThanDeadline() throws Exception {
        RateLimitBackendResult result = runOnceWithForcedServerDelay(200L, 3_000L);

        assertTrue(result.consumed());
        assertTrue(result.failureCode().isEmpty());
    }

    private RateLimitBackendResult runOnceWithForcedServerDelay(long serverDelayMs, long operationTimeoutMs)
            throws Exception {
        TokenBucketRateLimit algorithm = RateLimitRedisTestFixture.greedyAlgorithm(10, 10, 60_000);
        Redis redis = registry.client(RateLimitRedisTestFixture.CONNECTION);
        CountingRedis counting = new CountingRedis(redis, Command.EVAL);
        RateLimitBackend backend = RateLimitRedisTestFixture.backend(counting, vertx, operationTimeoutMs, 1_000);
        String storageKey = RateLimitRedisTestFixture.storageKey("delay-" + UUID.randomUUID());

        // Fire-and-forget: DEBUG SLEEP's own response only arrives once the server wakes back up,
        // and issuing it blocks every other command on this single-threaded Redis server meanwhile.
        // A small settling window (far shorter than serverDelayMs, far longer than a loopback
        // round-trip) lets the already-warmed connection deliver it before the backend's own first
        // command is dispatched, so the deliberate block is guaranteed to already be in effect.
        rawCommands.debug(List.of("SLEEP", String.valueOf(serverDelayMs / 1000.0)));
        RateLimitRedisTestFixture.await(RateLimitRedisTestFixture.settle(vertx, 50), 10);

        RateLimitBackendResult result = RateLimitRedisTestFixture.await(
                backend.consume(RateLimitRedisTestFixture.request(storageKey, algorithm, 1)), 10);

        // Then (shared across both scenarios): Vertique never issues more than the one logical EVAL
        // attempt of its own around Bucket4j's internal loop.
        assertThat(counting.count())
                .as("no additional logical attempt initiated by Vertique")
                .isLessThanOrEqualTo(1);
        return result;
    }
}
