// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import dev.vertique.redis.RedisClientRegistry;
import io.vertx.core.Vertx;
import io.vertx.junit5.VertxExtension;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Response;
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
 * TP-007, TP-008: the Vertique-issued {@code PEXPIRE} TTL bound after a committed consumption, and
 * that a forced {@code PEXPIRE} failure never changes the already-produced admission decision
 * (contracts/rate-limit-runtime.md, "Redis integration contract").
 */
@Testcontainers
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class RateLimitRedisTtlMaintenanceIT {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse(RateLimitRedisTestFixture.REDIS_IMAGE)).withExposedPorts(6379);

    private static Vertx vertx;
    private static RedisClientRegistry registry;
    private static RedisAPI rawCommands;

    @BeforeAll
    static void setUp(Vertx hostVertx) {
        vertx = hostVertx;
        registry = RateLimitRedisTestFixture.registry(vertx, REDIS.getHost(), REDIS.getMappedPort(6379));
        rawCommands = RedisAPI.api(registry.client(RateLimitRedisTestFixture.CONNECTION));
    }

    @AfterAll
    static void tearDown() throws Exception {
        RateLimitRedisTestFixture.await(registry.close());
    }

    @Test
    void shouldSetTtlViaPexpireFromComputedWorstCaseTimeToFullBound() throws Exception {
        // Given: a CLUSTERED policy with a known capacity/refill shape and expirationSlackMs, and a
        // raw command client independent of the backend under test.
        long capacity = 5;
        long tokensPerPeriod = 5;
        long periodMs = 60_000;
        long expirationSlackMs = 1_000;
        TokenBucketRateLimit algorithm = RateLimitRedisTestFixture.greedyAlgorithm(capacity, tokensPerPeriod, periodMs);
        Redis redis = registry.client(RateLimitRedisTestFixture.CONNECTION);
        RateLimitBackend backend = RateLimitRedisTestFixture.backend(redis, vertx, 2_000, expirationSlackMs);
        String storageKey = RateLimitRedisTestFixture.storageKey("ttl-" + UUID.randomUUID());

        // When: consume(...) is called once, committing a consumption.
        RateLimitBackendResult result = RateLimitRedisTestFixture.await(
                backend.consume(RateLimitRedisTestFixture.request(storageKey, algorithm, 1)));
        assertTrue(result.consumed(), "the first consume against a fresh full bucket must commit");

        // Then: the physical key's TTL, read via PTTL, is present and consistent with the
        // independently computed worstCaseTimeToFullMs + expirationSlackMs bound.
        String physicalKey = IndependentRedisReference.physicalKey(
                RateLimitRedisTestFixture.NAMESPACE, RateLimitRedisTestFixture.SECRET, storageKey);
        Response ttl = RateLimitRedisTestFixture.await(rawCommands.pttl(physicalKey));
        assertThat(ttl).isNotNull();
        long expectedTtlMs = IndependentRedisReference.worstCaseTimeToFullMs(capacity, tokensPerPeriod, periodMs)
                + expirationSlackMs;
        assertThat(ttl.toLong())
                .as("PTTL must reflect Vertique's own PEXPIRE bound, not a Bucket4j-written TTL")
                .isGreaterThan(0L)
                .isLessThanOrEqualTo(expectedTtlMs)
                .isGreaterThan(expectedTtlMs - 5_000L);
    }

    /** Sensitivity proof: a different capacity changes the observed PTTL bound accordingly. */
    @Test
    void shouldChangeObservedTtlWhenCapacityChanges() throws Exception {
        long smallCapacity = 2;
        long tokensPerPeriod = 5;
        long periodMs = 60_000;
        long expirationSlackMs = 1_000;
        TokenBucketRateLimit algorithm =
                RateLimitRedisTestFixture.greedyAlgorithm(smallCapacity, tokensPerPeriod, periodMs);
        Redis redis = registry.client(RateLimitRedisTestFixture.CONNECTION);
        RateLimitBackend backend = RateLimitRedisTestFixture.backend(redis, vertx, 2_000, expirationSlackMs);
        String storageKey = RateLimitRedisTestFixture.storageKey("ttl-small-" + UUID.randomUUID());

        RateLimitBackendResult result = RateLimitRedisTestFixture.await(
                backend.consume(RateLimitRedisTestFixture.request(storageKey, algorithm, 1)));
        assertTrue(result.consumed());

        String physicalKey = IndependentRedisReference.physicalKey(
                RateLimitRedisTestFixture.NAMESPACE, RateLimitRedisTestFixture.SECRET, storageKey);
        Response ttl = RateLimitRedisTestFixture.await(rawCommands.pttl(physicalKey));
        long expectedTtlMs = IndependentRedisReference.worstCaseTimeToFullMs(smallCapacity, tokensPerPeriod, periodMs)
                + expirationSlackMs;
        assertThat(ttl.toLong()).isLessThan(60_000L + expirationSlackMs);
        assertThat(ttl.toLong()).isLessThanOrEqualTo(expectedTtlMs).isGreaterThan(expectedTtlMs - 5_000L);
    }

    @Test
    void shouldNotChangeDecisionWhenTtlWriteFails() throws Exception {
        // Given: the same policy shape as TP-007, driven through a controllable fake that lets the
        // CAS write succeed but forces the immediately following PEXPIRE call to fail.
        long capacity = 5;
        long tokensPerPeriod = 5;
        long periodMs = 60_000;
        Redis realRedis = registry.client(RateLimitRedisTestFixture.CONNECTION);
        FailingCommandRedis failingPexpire = new FailingCommandRedis(realRedis, Command.PEXPIRE);
        TokenBucketRateLimit algorithm = RateLimitRedisTestFixture.greedyAlgorithm(capacity, tokensPerPeriod, periodMs);
        RateLimitBackend backend = RateLimitRedisTestFixture.backend(failingPexpire, vertx, 2_000, 1_000);
        String storageKey = RateLimitRedisTestFixture.storageKey("ttl-fail-" + UUID.randomUUID());

        // When: consume(...) is called once against this configuration.
        RateLimitBackendResult result = RateLimitRedisTestFixture.await(
                backend.consume(RateLimitRedisTestFixture.request(storageKey, algorithm, 1)));

        // Then: the decision is unaffected by the forced PEXPIRE failure.
        assertTrue(failingPexpire.observedFailedCommand(), "the fake must have forced at least one PEXPIRE failure");
        assertEquals(true, result.consumed());
        assertEquals(4L, result.remaining());
        assertTrue(result.failureCode().isEmpty(), "a TTL-write failure must never surface as a failure code");
    }

    /** Sensitivity proof: without the forced failure, the same scenario still passes identically. */
    @Test
    void shouldStillPermitWhenPexpireSucceeds() throws Exception {
        long capacity = 5;
        long tokensPerPeriod = 5;
        long periodMs = 60_000;
        Redis realRedis = registry.client(RateLimitRedisTestFixture.CONNECTION);
        TokenBucketRateLimit algorithm = RateLimitRedisTestFixture.greedyAlgorithm(capacity, tokensPerPeriod, periodMs);
        RateLimitBackend backend = RateLimitRedisTestFixture.backend(realRedis, vertx, 2_000, 1_000);
        String storageKey = RateLimitRedisTestFixture.storageKey("ttl-ok-" + UUID.randomUUID());

        RateLimitBackendResult result = RateLimitRedisTestFixture.await(
                backend.consume(RateLimitRedisTestFixture.request(storageKey, algorithm, 1)));

        assertEquals(true, result.consumed());
        assertEquals(4L, result.remaining());
        assertTrue(result.failureCode().isEmpty());
    }

    /** Forces every {@code PEXPIRE} command sent through it to fail, forwarding everything else. */
    private static final class FailingCommandRedis implements Redis {
        private final Redis delegate;
        private final Command failing;
        private volatile boolean observedFailedCommand;

        FailingCommandRedis(Redis delegate, Command failing) {
            this.delegate = delegate;
            this.failing = failing;
        }

        boolean observedFailedCommand() {
            return observedFailedCommand;
        }

        @Override
        public io.vertx.core.Future<io.vertx.redis.client.RedisConnection> connect() {
            return delegate.connect();
        }

        @Override
        public io.vertx.core.Future<Void> close() {
            return delegate.close();
        }

        @Override
        public io.vertx.core.Future<List<Response>> batch(List<io.vertx.redis.client.Request> commands) {
            return delegate.batch(commands);
        }

        @Override
        public io.vertx.core.Future<Response> send(io.vertx.redis.client.Request request) {
            if (request.command() == failing) {
                observedFailedCommand = true;
                return io.vertx.core.Future.failedFuture(new RuntimeException("forced PEXPIRE failure for TP-008"));
            }
            return delegate.send(request);
        }
    }
}
