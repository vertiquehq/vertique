// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit.dagger;

import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendRequest;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import io.vertx.core.Future;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * LOCAL {@link RateLimitBackend}: consumes against Bucket4j's private local {@link Bucket},
 * built with {@code LocalBucketBuilder.withMillisecondPrecision()}
 * (contracts/rate-limit-runtime.md, "Local engine contract").
 *
 * <p>Package-private by design — reached only through the {@link RateLimitBackend} interface this
 * task's {@link RateLimitCoreModule} binds it under. No Bucket4j type appears past this class's
 * own boundary.
 *
 * <p><strong>Deliberately unbounded.</strong> The bounded {@code maxTrackedKeys} registry,
 * cleanup sweep, and {@code CAPACITY_EXHAUSTED} classification are a later task's artifacts
 * (contracts/rate-limit-runtime.md, "Local engine contract"); this task's registry is an
 * unbounded per-process map, sufficient to prove the LOCAL decision path.
 */
final class LocalBucket4jRateLimitBackend implements RateLimitBackend {

    private final ConcurrentMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    public Future<RateLimitBackendResult> consume(RateLimitBackendRequest request) {
        Bucket bucket = buckets.computeIfAbsent(request.storageKey(), ignored -> newBucket(request));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(request.cost());
        return Future.succeededFuture(toResult(probe));
    }

    private static Bucket newBucket(RateLimitBackendRequest request) {
        Bandwidth bandwidth = Bandwidth.builder()
                .capacity(request.capacity())
                .refillGreedy(request.refillTokens(), Duration.ofMillis(request.refillPeriodMs()))
                .build();
        return Bucket.builder().addLimit(bandwidth).withMillisecondPrecision().build();
    }

    private static RateLimitBackendResult toResult(ConsumptionProbe probe) {
        boolean consumed = probe.isConsumed();
        Optional<Duration> retryAfter =
                consumed ? Optional.empty() : Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
        Optional<Duration> resetAfter = Optional.of(Duration.ofNanos(probe.getNanosToWaitForReset()));
        return new RateLimitBackendResult(
                consumed, probe.getRemainingTokens(), retryAfter, resetAfter, Optional.empty());
    }
}
