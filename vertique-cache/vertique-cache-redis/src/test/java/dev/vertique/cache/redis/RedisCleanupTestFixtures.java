// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.cache.spi.CacheCleanupObservation;
import dev.vertique.cache.spi.CacheObservation;
import dev.vertique.cache.spi.CacheObserver;
import dev.vertique.redis.RedisPrimaryNode;
import dev.vertique.redis.RedisScanPage;
import dev.vertique.redis.RedisTopologyOperations;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.redis.client.Response;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

final class RedisCleanupTestFixtures {
    static final String JOB_ID = "cache-redis-old-generation-cleanup";
    static final int MAX_KEYS_PER_SWEEP = 10_000;
    static final long MAX_SWEEP_MILLIS = 5_000;
    static final long CADENCE_MILLIS = 15 * 60_000L;
    static final long MAX_JITTER_MILLIS = 60_000;
    static final long MAX_BACKOFF_MILLIS = 60 * 60_000L;
    static final RedisPrimaryNode NODE_A = new RedisPrimaryNode("primary-a");
    static final RedisPrimaryNode NODE_B = new RedisPrimaryNode("primary-b");

    private RedisCleanupTestFixtures() {}

    static RedisScanPage page(String cursor, boolean finished, String... keys) {
        return new RedisScanPage(cursor, List.of(keys), finished);
    }

    static final class FakeTopology implements RedisTopologyOperations {
        final List<RedisPrimaryNode> nodes = new ArrayList<>(List.of(NODE_A, NODE_B));
        final Map<RedisPrimaryNode, Queue<RedisScanPage>> pages = new HashMap<>();
        final List<ScanCall> scans = new ArrayList<>();
        final List<UnlinkCall> unlinks = new ArrayList<>();
        final Set<String> physicallyPresent = new LinkedHashSet<>();
        Future<RedisScanPage> nextScan = null;
        Future<Long> nextUnlink = null;

        FakeTopology pages(RedisPrimaryNode node, RedisScanPage... nodePages) {
            pages.put(node, new ArrayDeque<>(List.of(nodePages)));
            for (RedisScanPage page : nodePages) {
                physicallyPresent.addAll(page.keys());
            }
            return this;
        }

        @Override
        public Future<List<RedisPrimaryNode>> primaryNodes() {
            return Future.succeededFuture(List.copyOf(nodes));
        }

        @Override
        public Future<RedisScanPage> scan(RedisPrimaryNode node, String cursor, int count) {
            scans.add(new ScanCall(node, cursor, count));
            if (nextScan != null) {
                Future<RedisScanPage> result = nextScan;
                nextScan = null;
                return result;
            }
            Queue<RedisScanPage> nodePages = pages.getOrDefault(node, new ArrayDeque<>());
            RedisScanPage page = nodePages.poll();
            return Future.succeededFuture(page != null ? page : page("0", true));
        }

        @Override
        public Future<Long> unlink(RedisPrimaryNode node, List<String> keys) {
            unlinks.add(new UnlinkCall(node, List.copyOf(keys)));
            if (nextUnlink != null) {
                Future<Long> result = nextUnlink;
                nextUnlink = null;
                return result;
            }
            long deleted = keys.stream().filter(physicallyPresent::remove).count();
            return Future.succeededFuture(deleted);
        }

        record ScanCall(RedisPrimaryNode node, String cursor, int count) {}

        record UnlinkCall(RedisPrimaryNode node, List<String> keys) {}
    }

    static final class RecordingCommands implements RedisCommandClient {
        final Map<String, String> values = new HashMap<>();
        final List<List<String>> commands = new ArrayList<>();

        @Override
        public Future<Response> get(String key) {
            return Future.succeededFuture(response(values.get(key)));
        }

        @Override
        public Future<Response> set(List<String> command) {
            commands.add(List.copyOf(command));
            values.put(command.get(0), command.get(1));
            return Future.succeededFuture(response("OK"));
        }

        @Override
        public Future<Response> del(List<String> command) {
            commands.add(List.copyOf(command));
            command.forEach(values::remove);
            return Future.succeededFuture(response("1"));
        }
    }

    static final class RecordingObserver implements CacheObserver {
        final List<CacheCleanupObservation> records = new ArrayList<>();

        @Override
        public void onOperation(CacheObservation observation) {}

        @Override
        public void onCleanup(CacheCleanupObservation observation) {
            records.add(observation);
        }
    }

    static final class MutableClock implements LongSupplier {
        private final AtomicLong nanos;

        MutableClock(long initialMillis) {
            nanos = new AtomicLong(initialMillis * 1_000_000L);
        }

        void advanceMillis(long millis) {
            nanos.addAndGet(millis * 1_000_000L);
        }

        @Override
        public long getAsLong() {
            return nanos.get();
        }

        Instant instant() {
            return Instant.ofEpochMilli(nanos.get() / 1_000_000L);
        }
    }

    static Promise<RedisScanPage> pendingScan() {
        return Promise.promise();
    }

    static Promise<Long> pendingUnlink() {
        return Promise.promise();
    }

    static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static Response response(String value) {
        if (value == null) {
            return null;
        }
        Response response = mock(Response.class);
        when(response.toString()).thenReturn(value);
        return response;
    }
}
