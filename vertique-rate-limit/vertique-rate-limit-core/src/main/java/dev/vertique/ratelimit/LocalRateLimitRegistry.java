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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
 *
 * <p><strong>Sweep throttling (T021 W2).</strong> An at-capacity admission's own inline sweep is
 * itself rate-limited to at most once per {@code cleanupIntervalMs} (tracked as {@link
 * #lastSweepAtMs}, guarded by the same {@link #admissionMonitor} the sweep already runs under): a
 * second at-capacity admission landing within that interval of the previous sweep skips the scan
 * entirely and reports {@code CAPACITY_EXHAUSTED} immediately, amortizing the {@code
 * O(maxTrackedKeys)} scan cost to {@code O(1)} on the admission hot path under sustained pressure.
 * This applies only to the demand-driven sweep this class triggers for itself; {@link #sweep()}'s
 * unconditional, caller-invoked form is untouched and never throttled.
 *
 * <p><strong>Saturation observability (T021 W3).</strong> Key-space saturation ({@code
 * CAPACITY_EXHAUSTED}) previously had no log signal anywhere in the LOCAL backend/registry — under
 * {@code failureMode: OPEN} it silently disabled a limiter with nothing but the
 * {@code vertique.ratelimit.failures{code="capacity-exhausted"}} counter to notice by. The first
 * {@code CAPACITY_EXHAUSTED} admission of a saturation episode now logs one {@code WARN} (policy
 * name and configured budget only — no key material); the flag guarding it (guarded by {@link
 * #admissionMonitor}, alongside {@link #lastSweepAtMs}) clears the moment a not-yet-tracked-key
 * admission next succeeds, so a later, distinct episode warns again.
 */
final class LocalRateLimitRegistry {

    private static final Logger log = LoggerFactory.getLogger(LocalRateLimitRegistry.class);

    private final String policyName;
    private final TokenBucketRateLimit algorithm;
    private final long maxTrackedKeys;
    private final long safeReclaimThresholdMs;
    private final long cleanupIntervalMs;
    private final Clock clock;
    private final ConcurrentMap<String, TrackedBucket> buckets = new ConcurrentHashMap<>();
    private final Object admissionMonitor = new Object();

    /** Guarded by {@link #admissionMonitor}; starts far enough in the past that the first at-capacity sweep is always due. */
    private long lastSweepAtMs = Long.MIN_VALUE / 2;

    /** Guarded by {@link #admissionMonitor}: true once this saturation episode's one WARN has fired. */
    private boolean saturationWarned;

    /**
     * @param policyName this registry's owning policy's name, for the saturation {@code WARN}
     *     only — never used as a storage key or for any admission decision
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
            String policyName,
            TokenBucketRateLimit algorithm,
            long maxTrackedKeys,
            long cleanupIntervalMs,
            Clock clock) {
        this.policyName = Objects.requireNonNull(policyName, "policyName");
        this.algorithm = Objects.requireNonNull(algorithm, "algorithm");
        this.maxTrackedKeys = maxTrackedKeys;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.cleanupIntervalMs = Math.max(0L, cleanupIntervalMs);
        this.safeReclaimThresholdMs = addClamped(worstCaseTimeToFullMs(algorithm), this.cleanupIntervalMs);
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
                        sweepIfDue(now);
                    }
                    if (buckets.size() >= maxTrackedKeys) {
                        warnOnceForThisSaturationEpisode();
                        return capacityExhausted();
                    }
                    saturationWarned = false;
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

    /**
     * The at-capacity admission path's own throttled trigger: runs {@link #sweep(long)} only when
     * at least {@link #cleanupIntervalMs} has elapsed since {@link #lastSweepAtMs}, then records
     * {@code now} as the new {@link #lastSweepAtMs} — always called from inside {@link
     * #admissionMonitor}, so no separate synchronization is needed for either field. A skipped sweep
     * leaves the registry unchanged; the caller's own at-capacity re-check then reports {@code
     * CAPACITY_EXHAUSTED} immediately, exactly as if the scan had run and found nothing reclaimable.
     */
    private void sweepIfDue(long now) {
        if (now - lastSweepAtMs < cleanupIntervalMs) {
            return;
        }
        sweep(now);
        lastSweepAtMs = now;
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
     * Logs one {@code WARN} for this saturation episode's first {@code CAPACITY_EXHAUSTED}
     * admission only — always called from inside {@link #admissionMonitor}. Policy name and
     * configured budget only; never key material. {@link #saturationWarned} is cleared the moment a
     * not-yet-tracked-key admission next succeeds (see {@link #consume(String, long)}), so a later,
     * distinct episode warns again.
     */
    private void warnOnceForThisSaturationEpisode() {
        if (saturationWarned) {
            return;
        }
        saturationWarned = true;
        log.warn(
                "rate-limit LOCAL registry for policy '{}' is saturated at its configured budget of {} tracked"
                        + " keys -- new keys are being rejected as CAPACITY_EXHAUSTED",
                policyName,
                maxTrackedKeys);
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
