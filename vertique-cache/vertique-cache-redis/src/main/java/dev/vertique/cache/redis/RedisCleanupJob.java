// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.cache.redis;

import dev.vertique.cache.config.CacheConfig;
import dev.vertique.job.cron.CronExpression;
import dev.vertique.job.cron.CronJobDefinition;
import dev.vertique.job.cron.CronScheduler;
import dev.vertique.job.cron.CronTargetReference;
import dev.vertique.job.cron.ExecutionMode;
import dev.vertique.job.cron.MisfirePolicy;
import dev.vertique.job.cron.OverlapPolicy;
import dev.vertique.redis.RedisPrimaryNode;
import dev.vertique.redis.RedisScanPage;
import dev.vertique.redis.RedisTopologyOperations;
import io.vertx.core.Future;
import io.vertx.redis.client.Response;
import java.lang.reflect.Method;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Bounded, topology-aware physical cleanup for unreachable Redis cache generations. */
final class RedisCleanupJob {
    static final String JOB_ID = "cache-redis-old-generation-cleanup";
    private static final String CRON_EXPRESSION = "0 */15 * * * *";
    private static final String HANDLER_ADDRESS = "vertique/cache/redis/cleanup";
    private static final long CADENCE_MILLIS = 15 * 60_000L;
    private static final long MAX_JITTER_MILLIS = 60_000L;
    private static final long MAX_KEYS_PER_SWEEP = 10_000L;
    private static final long MAX_SWEEP_MILLIS = 5_000L;
    private static final long MAX_BACKOFF_MILLIS = 60 * 60_000L;
    private static final String REGION_PATTERN = "[A-Za-z0-9._~-]+:v[1-9][0-9]*:[A-Za-z0-9._~-]+";

    private final RedisTopologyOperations topology;
    private final RedisCommandClient redis;
    private final CacheRedisConfig redisConfig;
    private final CacheConfig cacheConfig;
    private final Policy policy;
    private final Object metrics;
    private final IntSupplier jitterMillis;
    private final LongSupplier monotonicNanos;
    private final long firstRunJitterMillis;
    private final AtomicBoolean registered = new AtomicBoolean();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();

    /**
     * Creates a cleanup job with explicit seams for topology, marker reads, timing, and metrics.
     *
     * @param topology topology-aware Redis primary operations
     * @param redis command client used to read generation markers
     * @param redisConfig provider namespace and keyspace format configuration
     * @param cacheConfig provider-neutral cache limits and configuration
     * @param policy bounded cleanup policy
     * @param metrics provider-local metrics sink exposing {@code record(...)}
     * @param jitterMillis source for the first-run per-instance jitter
     * @param monotonicNanos monotonic clock used for the sweep time budget
     */
    RedisCleanupJob(
            RedisTopologyOperations topology,
            RedisCommandClient redis,
            CacheRedisConfig redisConfig,
            CacheConfig cacheConfig,
            Policy policy,
            Object metrics,
            IntSupplier jitterMillis,
            LongSupplier monotonicNanos) {
        this.topology = Objects.requireNonNull(topology, "topology");
        this.redis = Objects.requireNonNull(redis, "redis");
        this.redisConfig = Objects.requireNonNull(redisConfig, "redisConfig");
        this.cacheConfig = Objects.requireNonNull(cacheConfig, "cacheConfig");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.jitterMillis = Objects.requireNonNull(jitterMillis, "jitterMillis");
        this.monotonicNanos = Objects.requireNonNull(monotonicNanos, "monotonicNanos");
        this.firstRunJitterMillis = boundedJitter(jitterMillis.getAsInt(), policy.maxJitterMillis());
    }

    /**
     * Bounded cleanup policy.
     *
     * @param maxKeysPerSweep maximum number of keys inspected in one sweep
     * @param maxSweepDuration maximum elapsed monotonic duration for one sweep
     * @param maxJitter maximum first-run jitter
     * @param maxBackoff maximum retry backoff
     */
    record Policy(int maxKeysPerSweep, Duration maxSweepDuration, Duration maxJitter, Duration maxBackoff) {
        Policy {
            if (maxKeysPerSweep < 1) {
                throw new IllegalArgumentException("maxKeysPerSweep must be positive");
            }
            requireNonNegative(maxSweepDuration, "maxSweepDuration");
            if (maxSweepDuration.isZero()) {
                throw new IllegalArgumentException("maxSweepDuration must be positive");
            }
            requireNonNegative(maxJitter, "maxJitter");
            requireNonNegative(maxBackoff, "maxBackoff");
            if (maxBackoff.isZero()) {
                throw new IllegalArgumentException("maxBackoff must be positive");
            }
        }

        /** Returns the contract's default cleanup bounds. */
        static Policy defaults() {
            return new Policy(
                    Math.toIntExact(MAX_KEYS_PER_SWEEP),
                    Duration.ofMillis(MAX_SWEEP_MILLIS),
                    Duration.ofMillis(MAX_JITTER_MILLIS),
                    Duration.ofMillis(MAX_BACKOFF_MILLIS));
        }

        private static void requireNonNegative(Duration value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isNegative()) {
                throw new IllegalArgumentException(name + " must not be negative");
            }
        }

        long maxJitterMillis() {
            return maxJitter.toMillis();
        }

        long maxSweepNanos() {
            return maxSweepDuration.toNanos();
        }
    }

    /** The effective delay and first-run jitter selected for a scheduled cleanup. */
    record Schedule(Duration delay, long jitterMillis) {}

    /** Outcome of one bounded cleanup sweep. */
    record CleanupResult(long scannedKeys, long deletedKeys, long backlog, boolean failed) {
        long scanned() {
            return scannedKeys;
        }

        long deleted() {
            return deletedKeys;
        }

        boolean failure() {
            return failed;
        }
    }

    /** Returns the current jittered cadence or the capped retry delay after a failure. */
    Schedule schedule() {
        int failures = consecutiveFailures.get();
        Duration base = failures == 0 ? Duration.ofMillis(CADENCE_MILLIS) : retryDelayAfterFailures(failures);
        return new Schedule(base.plusMillis(firstRunJitterMillis), firstRunJitterMillis);
    }

    /**
     * Registers the cleanup definition once with the existing cron scheduler.
     *
     * @param scheduler scheduler receiving the stable cleanup definition
     */
    void register(CronScheduler scheduler) {
        Objects.requireNonNull(scheduler, "scheduler");
        if (!registered.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.register(new CronJobDefinition(
                    JOB_ID,
                    new CronExpression(CRON_EXPRESSION),
                    new CronTargetReference.EventBusTarget(HANDLER_ADDRESS),
                    HANDLER_ADDRESS,
                    ExecutionMode.EVERY_INSTANCE,
                    ZoneOffset.UTC,
                    1,
                    null,
                    OverlapPolicy.SKIP,
                    false,
                    Map.of(
                            "cadenceMs", CADENCE_MILLIS,
                            "maxJitterMs", policy.maxJitterMillis(),
                            "maxKeysPerSweep", policy.maxKeysPerSweep(),
                            "maxSweepMs", policy.maxSweepDuration.toMillis()),
                    MisfirePolicy.SKIP));
        } catch (RuntimeException failure) {
            registered.set(false);
            throw failure;
        }
    }

    /** Runs one asynchronous bounded cleanup sweep. */
    Future<CleanupResult> sweep() {
        SweepState state = new SweepState();
        long startedAt;
        try {
            startedAt = monotonicNanos.getAsLong();
        } catch (RuntimeException failure) {
            state.failed = true;
            return finish(state);
        }

        Future<List<RedisPrimaryNode>> nodes;
        try {
            nodes = topology.primaryNodes();
        } catch (RuntimeException failure) {
            state.failed = true;
            return finish(state);
        }
        if (nodes == null) {
            state.failed = true;
            return finish(state);
        }

        Future<Void> scan = nodes.compose(value -> scanNodes(value == null ? List.of() : value, 0, state, startedAt));
        return scan
                .map(ignored -> result(state))
                .recover(failure -> {
                    state.failed = true;
                    return Future.succeededFuture(result(state));
                })
                .compose(outcome -> finish(outcome));
    }

    /** Returns the capped exponential delay for a one-based consecutive failure count. */
    Duration retryDelayAfterFailures(int failures) {
        if (failures < 1) {
            return Duration.ZERO;
        }
        long delay = CADENCE_MILLIS;
        long maximum = policy.maxBackoff.toMillis();
        for (int attempt = 1; attempt < failures && delay < maximum; attempt++) {
            delay = delay > maximum / 2 ? maximum : Math.min(maximum, delay * 2);
        }
        return Duration.ofMillis(Math.min(delay, maximum));
    }

    private Future<CleanupResult> finish(SweepState state) {
        return finish(result(state));
    }

    private Future<CleanupResult> finish(CleanupResult outcome) {
        if (outcome.failed()) {
            consecutiveFailures.updateAndGet(value -> Math.min(Integer.MAX_VALUE, value + 1));
        } else {
            consecutiveFailures.set(0);
        }
        recordMetrics(outcome);
        return Future.succeededFuture(outcome);
    }

    private Future<Void> scanNodes(List<RedisPrimaryNode> nodes, int index, SweepState state, long startedAt) {
        if (index >= nodes.size() || timeBudgetReached(startedAt)) {
            if (timeBudgetReached(startedAt) && index < nodes.size()) {
                state.backlog = true;
            }
            return Future.succeededFuture();
        }
        RedisPrimaryNode node = nodes.get(index);
        if (node == null) {
            return scanNodes(nodes, index + 1, state, startedAt);
        }
        return scanNode(node, "0", state, startedAt)
                .compose(ignored -> scanNodes(nodes, index + 1, state, startedAt));
    }

    private Future<Void> scanNode(RedisPrimaryNode node, String cursor, SweepState state, long startedAt) {
        if (timeBudgetReached(startedAt)) {
            state.backlog = true;
            return Future.succeededFuture();
        }
        if (state.scannedKeys >= policy.maxKeysPerSweep()) {
            state.backlog = true;
            return Future.succeededFuture();
        }

        Future<RedisScanPage> pageFuture;
        try {
            pageFuture = topology.scan(node, cursor, Math.toIntExact(policy.maxKeysPerSweep() - state.scannedKeys));
        } catch (RuntimeException failure) {
            return Future.failedFuture(failure);
        }
        if (pageFuture == null) {
            return Future.failedFuture("Redis topology returned a null scan future");
        }
        return pageFuture.compose(page -> {
            if (page == null || page.cursor() == null || page.keys() == null) {
                return Future.failedFuture("Redis topology returned an invalid scan page");
            }
            int remaining = policy.maxKeysPerSweep() - Math.toIntExact(state.scannedKeys);
            int count = Math.min(remaining, page.keys().size());
            if (count < page.keys().size()) {
                state.backlog = true;
            }
            List<String> keys = page.keys().subList(0, count);
            return inspectKeys(node, keys, 0, state, startedAt)
                    .compose(ignored -> unlinkCandidates(node, state, startedAt))
                    .compose(ignored -> {
                        if (timeBudgetReached(startedAt)) {
                            state.backlog = true;
                            return Future.succeededFuture();
                        }
                        if (count < page.keys().size() || page.finished()) {
                            return Future.succeededFuture();
                        }
                        if (page.cursor().equals(cursor)) {
                            state.backlog = true;
                            return Future.succeededFuture();
                        }
                        return scanNode(node, page.cursor(), state, startedAt);
                    });
        });
    }

    private Future<Void> inspectKeys(
            RedisPrimaryNode node, List<String> keys, int index, SweepState state, long startedAt) {
        if (index >= keys.size()) {
            return Future.succeededFuture();
        }
        if (timeBudgetReached(startedAt)) {
            state.backlog = true;
            return Future.succeededFuture();
        }
        String key = keys.get(index);
        state.scannedKeys++;
        ParsedKey parsed = parse(key);
        if (parsed == null || !state.seenKeys.add(key)) {
            return inspectKeys(node, keys, index + 1, state, startedAt);
        }
        return marker(parsed.markerKey(), state).compose(marker -> {
            if (marker.readable() && !Objects.equals(marker.generation(), parsed.generation())) {
                state.candidatesByNode.computeIfAbsent(node, ignored -> new ArrayList<>()).add(key);
            }
            return inspectKeys(node, keys, index + 1, state, startedAt);
        });
    }

    private Future<Void> unlinkCandidates(RedisPrimaryNode node, SweepState state, long startedAt) {
        List<String> candidates = state.candidatesByNode.remove(node);
        if (candidates == null || candidates.isEmpty()) {
            return Future.succeededFuture();
        }
        if (timeBudgetReached(startedAt)) {
            state.backlog = true;
            return Future.succeededFuture();
        }
        Future<Long> unlink;
        try {
            unlink = topology.unlink(node, List.copyOf(candidates));
        } catch (RuntimeException failure) {
            state.failed = true;
            state.backlog = true;
            return Future.succeededFuture();
        }
        if (unlink == null) {
            state.failed = true;
            state.backlog = true;
            return Future.succeededFuture();
        }
        return unlink
                .map(deleted -> state.deletedKeys += deleted == null ? 0 : Math.max(0, deleted))
                .recover(failure -> {
                    state.failed = true;
                    state.backlog = true;
                    return Future.succeededFuture(0L);
                })
                .mapEmpty();
    }

    private Future<Marker> marker(String markerKey, SweepState state) {
        if (state.markers.containsKey(markerKey)) {
            return Future.succeededFuture(state.markers.get(markerKey));
        }
        Future<Response> value;
        try {
            value = redis.get(markerKey);
        } catch (RuntimeException failure) {
            state.failed = true;
            Marker invalid = Marker.invalid();
            state.markers.put(markerKey, invalid);
            return Future.succeededFuture(invalid);
        }
        if (value == null) {
            state.failed = true;
            Marker invalid = Marker.invalid();
            state.markers.put(markerKey, invalid);
            return Future.succeededFuture(invalid);
        }
        return value
                .map(response -> {
                    String generation = response == null ? null : response.toString();
                    if (generation != null && generation.isBlank()) {
                        throw new IllegalStateException("Redis cache generation marker was blank");
                    }
                    return new Marker(generation, true);
                })
                .recover(failure -> {
                    state.failed = true;
                    return Future.succeededFuture(Marker.invalid());
                })
                .onSuccess(result -> state.markers.put(markerKey, result));
    }

    private ParsedKey parse(String key) {
        if (key == null) {
            return null;
        }
        String root = redisConfig.namespace() + ":v" + redisConfig.formatVersion() + ":";
        if (!key.startsWith(root)) {
            return null;
        }
        int generationMarker = key.indexOf(":g", root.length());
        if (generationMarker <= root.length()) {
            return null;
        }
        String regionPrefix = key.substring(root.length(), generationMarker);
        if (!regionPrefix.matches(REGION_PATTERN)) {
            return null;
        }
        int generationStart = generationMarker + 2;
        int generationEnd = key.indexOf(':', generationStart);
        if (generationEnd <= generationStart) {
            return null;
        }
        int identityEnd = key.indexOf(':', generationEnd + 1);
        if (identityEnd <= generationEnd + 1 || identityEnd == key.length() - 1) {
            return null;
        }
        String generation = key.substring(generationStart, generationEnd);
        String markerKey = root + regionPrefix + ":generation";
        return new ParsedKey(key, markerKey, generation);
    }

    private boolean timeBudgetReached(long startedAt) {
        try {
            long now = monotonicNanos.getAsLong();
            return now >= startedAt && now - startedAt >= policy.maxSweepNanos();
        } catch (RuntimeException failure) {
            return true;
        }
    }

    private void recordMetrics(CleanupResult outcome) {
        try {
            Method method;
            try {
                method = metrics.getClass().getMethod(
                        "record", String.class, String.class, long.class, long.class, long.class, boolean.class);
            } catch (NoSuchMethodException ignored) {
                method = metrics.getClass().getDeclaredMethod(
                        "record", String.class, String.class, long.class, long.class, long.class, boolean.class);
                method.trySetAccessible();
            }
            method.invoke(
                    metrics,
                    redisConfig.connection(),
                    redisConfig.namespace(),
                    outcome.scannedKeys(),
                    outcome.deletedKeys(),
                    outcome.backlog(),
                    outcome.failed());
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Observability must not turn best-effort maintenance into an application failure.
        }
    }

    private CleanupResult result(SweepState state) {
        long backlog = state.backlog || state.failed || !state.candidatesByNode.isEmpty() ? 1 : 0;
        return new CleanupResult(state.scannedKeys, state.deletedKeys, backlog, state.failed);
    }

    private static long boundedJitter(int candidate, long maximumExclusive) {
        if (maximumExclusive <= 0) {
            return 0;
        }
        return Math.floorMod(candidate, maximumExclusive);
    }

    private record ParsedKey(String key, String markerKey, String generation) {}

    private record Marker(String generation, boolean readable) {
        static Marker invalid() {
            return new Marker(null, false);
        }
    }

    private static final class SweepState {
        long scannedKeys;
        long deletedKeys;
        boolean backlog;
        boolean failed;
        final Set<String> seenKeys = new HashSet<>();
        final Map<String, Marker> markers = new HashMap<>();
        final Map<RedisPrimaryNode, List<String>> candidatesByNode = new LinkedHashMap<>();
    }
}
