// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static dev.vertique.cache.redis.RedisCleanupTestFixtures.CADENCE_MILLIS;
import static dev.vertique.cache.redis.RedisCleanupTestFixtures.MAX_BACKOFF_MILLIS;
import static dev.vertique.cache.redis.RedisCleanupTestFixtures.MAX_KEYS_PER_SWEEP;
import static dev.vertique.cache.redis.RedisCleanupTestFixtures.MAX_SWEEP_MILLIS;
import static dev.vertique.cache.redis.RedisCleanupTestFixtures.NODE_A;
import static dev.vertique.cache.redis.RedisCleanupTestFixtures.NODE_B;
import static dev.vertique.cache.redis.RedisCleanupTestFixtures.await;
import static dev.vertique.cache.redis.RedisTestFixtures.REDIS_CONFIG;
import static dev.vertique.cache.redis.RedisTestFixtures.cacheConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.redis.RedisScanPage;
import io.vertx.core.Future;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Verifies bounded, topology-aware, idempotent cleanup of unreachable Redis generations. */
class RedisCleanupJobTest {

    @Test
    @DisplayName("deletes only generation keys proven unreachable by the current marker")
    void deletesOnlyUnreachableGenerationKeys() throws Exception {
        RedisCleanupTestFixtures.FakeTopology topology = new RedisCleanupTestFixtures.FakeTopology()
                .pages(
                        NODE_A,
                        RedisCleanupTestFixtures.page(
                                "0",
                                true,
                                "it:v1:cache:v1:profiles:gOLD:NONE:alice",
                                "it:v1:cache:v1:profiles:gCURRENT:NONE:bob"));
        RedisCleanupTestFixtures.RecordingCommands commands = new RedisCleanupTestFixtures.RecordingCommands();
        commands.values.put(RedisCacheKey.generation(RedisTestFixtures.REGION, REDIS_CONFIG, cacheConfig()), "CURRENT");
        topology.physicallyPresent.addAll(
                List.of("it:v1:cache:v1:profiles:gOLD:NONE:alice", "it:v1:cache:v1:profiles:gCURRENT:NONE:bob"));

        await(RedisCleanupJobTestSupport.job(topology, commands).sweep());

        assertEquals(1, topology.unlinks.size());
        assertEquals(
                List.of("it:v1:cache:v1:profiles:gOLD:NONE:alice"),
                topology.unlinks.get(0).keys());
        assertTrue(topology.physicallyPresent.contains("it:v1:cache:v1:profiles:gCURRENT:NONE:bob"));
    }

    @Test
    @DisplayName("scans every primary and unlinks asynchronously without DEL")
    void scansEveryRedisPrimaryOrNode() throws Exception {
        RedisCleanupTestFixtures.FakeTopology topology = new RedisCleanupTestFixtures.FakeTopology()
                .pages(NODE_A, RedisCleanupTestFixtures.page("a1", true, "it:v1:cache:v1:profiles:gOLD:NONE:a"))
                .pages(NODE_B, RedisCleanupTestFixtures.page("b1", true, "it:v1:cache:v1:profiles:gOLD:NONE:b"));
        RedisCleanupTestFixtures.RecordingCommands commands = new RedisCleanupTestFixtures.RecordingCommands();

        await(RedisCleanupJobTestSupport.job(topology, commands).sweep());

        assertEquals(
                List.of(NODE_A, NODE_B),
                topology.scans.stream()
                        .map(RedisCleanupTestFixtures.FakeTopology.ScanCall::node)
                        .distinct()
                        .toList());
        assertEquals(2, topology.unlinks.size());
        assertFalse(commands.commands.stream().anyMatch(command -> "DEL".equals(command.get(0))));
    }

    @Test
    @DisplayName("deduplicates keys repeated across pages and primaries")
    void deduplicatesKeysAcrossPagesAndNodes() throws Exception {
        String duplicate = "it:v1:cache:v1:profiles:gOLD:NONE:duplicate";
        RedisCleanupTestFixtures.FakeTopology topology = new RedisCleanupTestFixtures.FakeTopology()
                .pages(
                        NODE_A,
                        RedisCleanupTestFixtures.page("a2", false, duplicate),
                        RedisCleanupTestFixtures.page("0", true, duplicate, "it:v1:cache:v1:profiles:gOLD:NONE:a"))
                .pages(NODE_B, RedisCleanupTestFixtures.page("0", true, duplicate));

        await(RedisCleanupJobTestSupport.job(topology, new RedisCleanupTestFixtures.RecordingCommands())
                .sweep());

        List<String> deleted =
                topology.unlinks.stream().flatMap(call -> call.keys().stream()).toList();
        assertEquals(List.of(duplicate, "it:v1:cache:v1:profiles:gOLD:NONE:a"), deleted);
    }

    @Test
    @DisplayName("stops scanning at ten thousand keys")
    void enforcesTenThousandKeyBudget() throws Exception {
        String[] keys = java.util.stream.IntStream.range(0, MAX_KEYS_PER_SWEEP + 1)
                .mapToObj(index -> "it:v1:cache:v1:profiles:gOLD:NONE:key-" + index)
                .toArray(String[]::new);
        RedisCleanupTestFixtures.FakeTopology topology = new RedisCleanupTestFixtures.FakeTopology()
                .pages(NODE_A, new RedisScanPage("next", List.of(keys), false));

        RedisCleanupJob.CleanupResult result =
                await(RedisCleanupJobTestSupport.job(topology, new RedisCleanupTestFixtures.RecordingCommands())
                        .sweep());

        assertEquals(MAX_KEYS_PER_SWEEP, result.scannedKeys());
        assertTrue(result.backlog() > 0);
    }

    @Test
    @DisplayName("stops a slow sweep at five seconds using monotonic time")
    void enforcesFiveSecondSweepBudget() throws Exception {
        RedisCleanupTestFixtures.MutableClock clock = new RedisCleanupTestFixtures.MutableClock(0);
        RedisCleanupTestFixtures.FakeTopology topology = new RedisCleanupTestFixtures.FakeTopology()
                .pages(NODE_A, RedisCleanupTestFixtures.page("next", false, "it:v1:cache:v1:profiles:gOLD:NONE:a"));
        topology.nextScan = Future.succeededFuture(RedisCleanupTestFixtures.page("next", false));
        RedisCleanupJob job = RedisCleanupJobTestSupport.job(
                topology,
                new RedisCleanupTestFixtures.RecordingCommands(),
                new RedisCleanupTestFixtures.RecordingMetrics(),
                () -> 0,
                () -> {
                    clock.advanceMillis(MAX_SWEEP_MILLIS);
                    return clock.getAsLong();
                });

        RedisCleanupJob.CleanupResult result = await(job.sweep());

        assertTrue(result.backlog() > 0);
        assertTrue(topology.scans.size() <= 1);
    }

    @Test
    @DisplayName("applies uniform per-instance jitter below one minute")
    void appliesPerInstanceJitter() {
        AtomicInteger jitter = new AtomicInteger(42_000);
        RedisCleanupJob job = RedisCleanupJobTestSupport.job(
                new RedisCleanupTestFixtures.FakeTopology(),
                new RedisCleanupTestFixtures.RecordingCommands(),
                new RedisCleanupTestFixtures.RecordingMetrics(),
                jitter::get,
                System::nanoTime);

        RedisCleanupJob.Schedule schedule = job.schedule();

        assertEquals(Duration.ofMillis(CADENCE_MILLIS + jitter.get()), schedule.delay());
        assertTrue(schedule.jitterMillis() >= 0);
        assertTrue(schedule.jitterMillis() < 60_000);
    }

    @Test
    @DisplayName("two workers can sweep the same keys without a lock or duplicate command effect")
    void multipleWorkersAreIdempotent() throws Exception {
        RedisCleanupTestFixtures.FakeTopology topology = new RedisCleanupTestFixtures.FakeTopology()
                .pages(NODE_A, RedisCleanupTestFixtures.page("0", true, "it:v1:cache:v1:profiles:gOLD:NONE:a"));
        RedisCleanupTestFixtures.RecordingCommands commands = new RedisCleanupTestFixtures.RecordingCommands();
        RedisCleanupJob first = RedisCleanupJobTestSupport.job(topology, commands);
        RedisCleanupJob second = RedisCleanupJobTestSupport.job(topology, commands);

        await(Future.all(first.sweep(), second.sweep()));

        assertTrue(topology.physicallyPresent.isEmpty());
        assertTrue(topology.unlinks.stream()
                        .flatMap(call -> call.keys().stream())
                        .distinct()
                        .count()
                <= 2);
    }

    @Test
    @DisplayName("records a failed sweep for the next run without coupling to the request future")
    void failureRetriesOnNextRun() throws Exception {
        RedisCleanupTestFixtures.FakeTopology topology = new RedisCleanupTestFixtures.FakeTopology();
        topology.nextScan = Future.failedFuture("temporary scan failure");
        RedisCleanupJob job =
                RedisCleanupJobTestSupport.job(topology, new RedisCleanupTestFixtures.RecordingCommands());

        RedisCleanupJob.CleanupResult first = await(job.sweep());
        RedisCleanupJob.CleanupResult second = await(job.sweep());

        assertTrue(first.failed());
        assertNotNull(second);
        assertTrue(topology.scans.size() >= 2);
    }

    @Test
    @DisplayName("uses capped exponential retry backoff with a one-hour ceiling")
    void repeatedFailuresBackOffAndCapAtOneHour() {
        RedisCleanupJob job = RedisCleanupJobTestSupport.job();

        assertEquals(Duration.ofMinutes(15), job.retryDelayAfterFailures(1));
        assertEquals(Duration.ofMinutes(30), job.retryDelayAfterFailures(2));
        assertEquals(Duration.ofHours(1), job.retryDelayAfterFailures(3));
        assertEquals(Duration.ofMillis(MAX_BACKOFF_MILLIS), job.retryDelayAfterFailures(99));
    }

    @Test
    @DisplayName("reports scanned, deleted, backlog, and failure outcomes with bounded labels")
    void recordsScannedDeletedBacklogAndFailureMetrics() throws Exception {
        RedisCleanupTestFixtures.RecordingMetrics metrics = new RedisCleanupTestFixtures.RecordingMetrics();
        RedisCleanupTestFixtures.FakeTopology topology = new RedisCleanupTestFixtures.FakeTopology()
                .pages(NODE_A, RedisCleanupTestFixtures.page("0", true, "it:v1:cache:v1:profiles:gOLD:NONE:a"));

        await(RedisCleanupJobTestSupport.job(
                        topology, new RedisCleanupTestFixtures.RecordingCommands(), metrics, () -> 0, System::nanoTime)
                .sweep());

        assertEquals(1, metrics.records.size());
        RedisCleanupTestFixtures.RecordingMetrics.Metric metric = metrics.records.get(0);
        assertEquals(1, metric.scanned());
        assertEquals(1, metric.deleted());
        assertEquals(0, metric.backlog());
        assertFalse(metric.failed());
        assertEquals("primary", metric.profile());
        assertEquals("it", metric.namespace());
    }

    @Test
    @DisplayName("cleanup does not delay a business request or readiness future")
    void cleanupIsOutsideBusinessFutureAndReadiness() throws Exception {
        RedisCleanupTestFixtures.FakeTopology topology = new RedisCleanupTestFixtures.FakeTopology();
        var pending = RedisCleanupTestFixtures.pendingScan();
        topology.nextScan = pending.future();
        RedisCleanupJob job =
                RedisCleanupJobTestSupport.job(topology, new RedisCleanupTestFixtures.RecordingCommands());

        Future<RedisCleanupJob.CleanupResult> cleanup = job.sweep();
        Future<String> business = Future.succeededFuture("business-result");
        Future<String> readiness = Future.succeededFuture("ready");

        assertEquals("business-result", await(business));
        assertEquals("ready", await(readiness));
        assertFalse(cleanup.isComplete());
        pending.complete(RedisCleanupTestFixtures.page("0", true));
        assertInstanceOf(RedisCleanupJob.CleanupResult.class, await(cleanup));
    }
}
