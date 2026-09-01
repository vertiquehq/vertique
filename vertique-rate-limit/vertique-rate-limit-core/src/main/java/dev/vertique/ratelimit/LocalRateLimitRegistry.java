// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * One policy's bounded local Bucket4j registry (contracts/rate-limit-runtime.md, "Local engine
 * contract"). Every enabled LOCAL policy owns exactly one instance, sized by its own resolved
 * {@code maxTrackedKeys} budget — never a pool shared across policies (§7.2, R2 amendment). {@link
 * LocalBucket4jRateLimitBackend} owns routing each request to the right per-policy instance and is
 * this class's only production caller.
 *
 * <p><strong>Package-private, framework-internal</strong> — reached only through {@link
 * LocalBucket4jRateLimitBackend}, itself only reachable through {@link
 * LocalRateLimitBackendFactory}'s public static factory; never a supported application extension
 * point. No Bucket4j type appears in any public signature.
 *
 * <p>Eviction never removes active state merely to admit a new key: an entry is safely reclaimable
 * only once the worst-case time for its algorithm to refill from empty to full, plus a retention
 * slack, has elapsed since it was last accessed — at that point fresh (recreated) state is
 * semantically equivalent to what was removed. This implementation uses the configured {@code
 * cleanupIntervalMs} as that retention slack (contracts.md declares no separate LOCAL slack
 * config path), so cleanup work stays demand-driven: a sweep runs inline, bounded to one full scan
 * of this registry, only when admission for a not-yet-tracked key finds the registry at its budget
 * — never on a background timer. Every proof in {@code LocalRateLimitRegistryTest} triggers a sweep
 * this same way (either explicitly, or implicitly through an at-capacity admission).
 */
final class LocalRateLimitRegistry {

    private final TokenBucketRateLimit algorithm;
    private final long maxTrackedKeys;
    private final long safeReclaimThresholdMs;
    private final Clock clock;
    private final ConcurrentMap<String, TrackedBucket> buckets = new ConcurrentHashMap<>();
    private final Object admissionMonitor = new Object();

    /**
     * @param algorithm this policy's resolved token-bucket algorithm; also drives the worst-case
     *     time-to-full used to compute safe reclaim eligibility
     * @param maxTrackedKeys this policy's own resolved registry budget (default or per-policy
     *     override), already bounds-validated by config validation upstream
     * @param cleanupIntervalMs this policy's resolved {@code rateLimit.local.cleanupIntervalMs},
     *     used as the retention slack added to the worst-case time-to-full
     * @param clock time source for last-access tracking and safe-reclaim evaluation; injected so
     *     tests can control elapsed time deterministically, with no wall-clock sleep
     */
    public LocalRateLimitRegistry(
            TokenBucketRateLimit algorithm, long maxTrackedKeys, long cleanupIntervalMs, Clock clock) {
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        this.maxTrackedKeys = maxTrackedKeys;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.safeReclaimThresholdMs = addClamped(worstCaseTimeToFullMs(algorithm), Math.max(0L, cleanupIntervalMs));
    }

    /**
     * Attempts to consume {@code cost} tokens against {@code key}, creating a fresh full bucket on
     * first access. Registry creation is atomic under concurrent first use: two racing callers for
     * the same not-yet-tracked key never create two independent effective buckets.
     *
     * @param key the already-canonical per-policy storage key
     * @param cost tokens to consume
     * @return the normalized result; {@code failureCode() == CAPACITY_EXHAUSTED} when {@code key}
     *     is not yet tracked, the registry is at {@code maxTrackedKeys}, and no tracked entry is
     *     safely reclaimable
     */
    public RateLimitBackendResult consume(String key, long cost) {
        Objects.requireNonNull(key, "key");
        long now = clock.millis();
        TrackedBucket bucket = buckets.get(key);
        if (bucket == null) {
            synchronized (admissionMonitor) {
                bucket = buckets.get(key);
                if (bucket == null) {
                    if (buckets.size() >= maxTrackedKeys) {
                        sweep(now);
                    }
                    if (buckets.size() >= maxTrackedKeys) {
                        return capacityExhausted();
                    }
                    bucket = new TrackedBucket(newBucket(), now);
                    buckets.put(key, bucket);
                }
            }
        }
        bucket.touch(now);
        return toResult(bucket.bucket().tryConsumeAndReturnRemaining(cost));
    }

    /**
     * Removes every tracked entry that has been safely reclaimable (worst-case time-to-full plus
     * retention slack elapsed since its last access) as of now. Bounded to one scan of this
     * registry; never touches or removes an active entry.
     */
    public void sweep() {
        sweep(clock.millis());
    }

    private void sweep(long now) {
        buckets.entrySet().removeIf(entry -> entry.getValue().isSafelyReclaimable(now, safeReclaimThresholdMs));
    }

    private Bucket newBucket() {
        return Bucket.builder()
                .addLimit(toBandwidth(algorithm))
                .withMillisecondPrecision()
                .build();
    }

    private static Bandwidth toBandwidth(TokenBucketRateLimit algorithm) {
        var refillStage = Bandwidth.builder().capacity(algorithm.capacity());
        return switch (algorithm.refill()) {
            case GreedyRateLimitRefill greedy ->
                refillStage.refillGreedy(greedy.tokens(), greedy.period()).build();
            case IntervalRateLimitRefill interval ->
                refillStage
                        .refillIntervally(interval.tokens(), interval.period())
                        .build();
        };
    }

    private static RateLimitBackendResult toResult(ConsumptionProbe probe) {
        boolean consumed = probe.isConsumed();
        Optional<Duration> retryAfter =
                consumed ? Optional.empty() : Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        Optional<Duration> resetAfter = Optional.of(Duration.ofNanos(probe.getNanosToWaitForReset()));
        return new RateLimitBackendResult(
                consumed, probe.getRemainingTokens(), retryAfter, resetAfter, Optional.empty());
    }

    private static RateLimitBackendResult capacityExhausted() {
        return new RateLimitBackendResult(
                false, 0L, Optional.empty(), Optional.empty(), Optional.of(RateLimitFailureCode.CAPACITY_EXHAUSTED));
    }

    /**
     * Worst-case time, in milliseconds, for this algorithm to refill a bucket from empty back to
     * full — greedy: {@code ceil(capacity * periodMs / tokens)}; interval: {@code ceil(capacity /
     * tokens)} whole periods. Clamped rather than overflowed for pathological configurations.
     */
    private static long worstCaseTimeToFullMs(TokenBucketRateLimit algorithm) {
        long capacity = algorithm.capacity();
        return switch (algorithm.refill()) {
            case GreedyRateLimitRefill greedy ->
                ceilDiv(
                        BigInteger.valueOf(capacity)
                                .multiply(BigInteger.valueOf(greedy.period().toMillis())),
                        BigInteger.valueOf(greedy.tokens()));
            case IntervalRateLimitRefill interval -> {
                long periods = ceilDiv(BigInteger.valueOf(capacity), BigInteger.valueOf(interval.tokens()));
                yield clampToLong(BigInteger.valueOf(periods)
                        .multiply(BigInteger.valueOf(interval.period().toMillis())));
            }
        };
    }

    private static long ceilDiv(BigInteger numerator, BigInteger denominator) {
        BigInteger[] divRem = numerator.divideAndRemainder(denominator);
        BigInteger quotient = divRem[1].signum() == 0 ? divRem[0] : divRem[0].add(BigInteger.ONE);
        return clampToLong(quotient);
    }

    private static long clampToLong(BigInteger value) {
        BigInteger max = BigInteger.valueOf(Long.MAX_VALUE / 2);
        return value.compareTo(max) > 0 ? max.longValueExact() : value.longValueExact();
    }

    private static long addClamped(long a, long b) {
        try {
            return Math.addExact(a, b);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE / 2;
        }
    }

    /** One tracked Bucket4j bucket plus its last-access timestamp for safe-reclaim eligibility. */
    private static final class TrackedBucket {
        private final Bucket bucket;
        private volatile long lastAccessAtMs;

        TrackedBucket(Bucket bucket, long createdAtMs) {
            this.bucket = bucket;
            this.lastAccessAtMs = createdAtMs;
        }

        Bucket bucket() {
            return bucket;
        }

        void touch(long nowMs) {
            lastAccessAtMs = nowMs;
        }

        boolean isSafelyReclaimable(long nowMs, long safeReclaimThresholdMs) {
            return nowMs - lastAccessAtMs >= safeReclaimThresholdMs;
        }
    }
}
