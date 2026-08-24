// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static dev.vertique.cache.redis.RedisCleanupTestFixtures.MAX_BACKOFF_MILLIS;
import static dev.vertique.cache.redis.RedisCleanupTestFixtures.MAX_JITTER_MILLIS;
import static dev.vertique.cache.redis.RedisCleanupTestFixtures.MAX_KEYS_PER_SWEEP;
import static dev.vertique.cache.redis.RedisCleanupTestFixtures.MAX_SWEEP_MILLIS;
import static dev.vertique.cache.redis.RedisTestFixtures.REDIS_CONFIG;
import static dev.vertique.cache.redis.RedisTestFixtures.cacheConfig;

import dev.vertique.redis.RedisTopologyOperations;
import java.time.Duration;
import java.util.List;
import java.util.function.IntSupplier;

final class RedisCleanupJobTestSupport {
    private RedisCleanupJobTestSupport() {}

    static RedisCleanupJob job() {
        return job(new RedisCleanupTestFixtures.FakeTopology(), new RedisCleanupTestFixtures.RecordingCommands());
    }

    static RedisCleanupJob job(
            RedisTopologyOperations topology, RedisCleanupTestFixtures.RecordingCommands commands) {
        return new RedisCleanupJob(
                topology,
                commands,
                REDIS_CONFIG,
                cacheConfig(),
                new RedisCleanupJob.Policy(
                        MAX_KEYS_PER_SWEEP,
                        Duration.ofMillis(MAX_SWEEP_MILLIS),
                        Duration.ofMillis(MAX_JITTER_MILLIS),
                        Duration.ofMillis(MAX_BACKOFF_MILLIS)),
                new RedisCleanupTestFixtures.RecordingMetrics(),
                () -> 0,
                () -> 0L);
    }

    static RedisCleanupJob job(
            RedisTopologyOperations topology,
            RedisCleanupTestFixtures.RecordingCommands commands,
            RedisCleanupTestFixtures.RecordingMetrics metrics,
            IntSupplier jitterMillis,
            java.util.function.LongSupplier monotonicNanos) {
        return new RedisCleanupJob(
                topology,
                commands,
                REDIS_CONFIG,
                cacheConfig(),
                RedisCleanupJob.Policy.defaults(),
                metrics,
                jitterMillis,
                monotonicNanos);
    }

    static List<String> oldGenerationKeys(String generation) {
        return List.of(
                "it:v1:cache:v1:profiles:g" + generation + ":NONE:alice",
                "it:v1:cache:v1:profiles:g" + generation + ":NONE:bob");
    }
}
