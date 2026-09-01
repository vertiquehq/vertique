// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.redis;

import dev.vertique.ratelimit.GreedyRateLimitRefill;
import dev.vertique.ratelimit.TokenBucketRateLimit;
import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendRequest;
import dev.vertique.redis.RedisClientRegistry;
import dev.vertique.redis.RedisConnectionConfig;
import dev.vertique.redis.RedisConnectionsConfig;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.redis.client.Redis;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Shared Testcontainers/Dagger/policy wiring for this module's integration tests, hiding
 * container/client/policy plumbing behind an intent-revealing surface (mirroring {@code
 * vertique-cache-redis}'s {@code RedisTestFixtures} idiom).
 */
final class RateLimitRedisTestFixture {

    /** Same pinned image {@code vertique-cache-redis} uses. */
    static final String REDIS_IMAGE =
            "redis:7.2.4-alpine@sha256:c8bb255c3559b3e458766db810aa7b3c7af1235b204cfdb304e79ff388fe1a5a";

    static final String CONNECTION = "primary";
    static final String NAMESPACE = "rl-it";
    static final String POLICY_NAME = "redis-it-policy";
    static final String POLICY_REVISION = "r1";

    /** At least 32 bytes (UTF-8), per contracts/rate-limit-runtime.md's "Storage identity" bound. */
    static final String SECRET = "rl-redis-it-secret-at-least-32-bytes!!";

    private RateLimitRedisTestFixture() {}

    static RedisConnectionConfig connectionConfig(String host, int port) {
        return new RedisConnectionConfig(
                CONNECTION, List.of("redis://" + host + ":" + port), null, null, false, 2_000, 8, 100);
    }

    static RedisClientRegistry registry(Vertx vertx, String host, int port) {
        return new RedisClientRegistry(vertx, new RedisConnectionsConfig(List.of(connectionConfig(host, port))));
    }

    static TokenBucketRateLimit greedyAlgorithm(long capacity, long tokens, long periodMs) {
        return new TokenBucketRateLimit(capacity, new GreedyRateLimitRefill(tokens, Duration.ofMillis(periodMs)));
    }

    /** Builds an opaque, distinct storage key for direct backend-level tests (not the real canonical encoding). */
    static String storageKey(String discriminator) {
        return POLICY_NAME + ':' + POLICY_REVISION + ':' + discriminator;
    }

    static RateLimitBackendRequest request(String storageKey, TokenBucketRateLimit algorithm, long cost) {
        return new RateLimitBackendRequest(storageKey, algorithm, cost);
    }

    /** Builds the CLUSTERED backend directly (bypassing Dagger), the same construction production uses. */
    static RateLimitBackend backend(Redis redis, Vertx vertx, long operationTimeoutMs, long expirationSlackMs) {
        return Bucket4jRedisRateLimitBackend.redis(
                redis, vertx, NAMESPACE, SECRET, operationTimeoutMs, expirationSlackMs);
    }

    static RateLimitBackend backend(
            Redis redis, Vertx vertx, String namespace, long operationTimeoutMs, long expirationSlackMs) {
        return Bucket4jRedisRateLimitBackend.redis(
                redis, vertx, namespace, SECRET, operationTimeoutMs, expirationSlackMs);
    }

    /** Full application configuration for the real-Dagger-graph tests (TP-003, TP-004, TP-006). */
    static JsonObject configuration(
            String host,
            int port,
            long capacity,
            long tokens,
            long periodMs,
            String failureMode,
            long operationTimeoutMs,
            long expirationSlackMs,
            String namespace) {
        return new JsonObject()
                .put(
                        "rateLimit",
                        new JsonObject()
                                .put("enabled", true)
                                .put("keyDerivation", new JsonObject().put("secret", SECRET))
                                .put(
                                        "policies",
                                        new JsonObject()
                                                .put(
                                                        POLICY_NAME,
                                                        new JsonObject()
                                                                .put("enabled", true)
                                                                .put("mode", "CLUSTERED")
                                                                .put("failureMode", failureMode)
                                                                .put("revision", POLICY_REVISION)
                                                                .put("defaultCost", 1)
                                                                .put(
                                                                        "algorithm",
                                                                        new JsonObject()
                                                                                .put("type", "TOKEN_BUCKET")
                                                                                .put("capacity", capacity)
                                                                                .put(
                                                                                        "refill",
                                                                                        new JsonObject()
                                                                                                .put("type", "GREEDY")
                                                                                                .put("tokens", tokens)
                                                                                                .put(
                                                                                                        "periodMs",
                                                                                                        periodMs)))))
                                .put(
                                        "redis",
                                        new JsonObject()
                                                .put("connection", CONNECTION)
                                                .put("namespace", namespace)
                                                .put("operationTimeoutMs", operationTimeoutMs)
                                                .put("expirationSlackMs", expirationSlackMs)))
                .put(
                        "redis",
                        new JsonObject()
                                .put(
                                        "connections",
                                        new JsonObject()
                                                .put(
                                                        CONNECTION,
                                                        new JsonObject()
                                                                .put(
                                                                        "endpoints",
                                                                        List.of("redis://" + host + ":" + port))
                                                                .put("connectTimeoutMs", 2_000)
                                                                .put("maxPoolSize", 8)
                                                                .put("maxPoolWaiting", 100))));
    }

    /**
     * A bounded, non-blocking Vert.x-timer settling window: used only to give an already-forwarded
     * asynchronous side effect (e.g. a deliberately late-released held call) a deterministic chance
     * to finish propagating before a negative ("nothing further changed") assertion, never to
     * coordinate a positive assertion's correctness — this never blocks a thread, unlike a raw
     * {@code Thread.sleep}.
     */
    static Future<Void> settle(Vertx vertx, long ms) {
        return Future.future(promise -> vertx.setTimer(ms, ignored -> promise.complete()));
    }

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    static <T> T await(Future<T> future, long timeoutSeconds) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(timeoutSeconds, TimeUnit.SECONDS);
    }
}
