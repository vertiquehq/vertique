// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendRequest;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Constructs a real {@link RateLimiters} directly (no Dagger, no Bucket4j), bound to one LOCAL
 * counting fixture backend. Keeps {@link RateLimiters}/{@link RateLimiter}/{@link
 * KeyedRateLimiter} wiring under real test while the local Bucket4j engine itself stays covered
 * by {@link RateLimitersLocalWalkingSkeletonIT}.
 */
final class RateLimitersUnitFixtures {

    private RateLimitersUnitFixtures() {}

    static RateLimiters withPolicies(Vertx vertx, RateLimitPolicy... policies) {
        Map<RateLimitMode, RateLimitBackend> backends = Map.of(RateLimitMode.LOCAL, new CountingLocalBackend());
        return new RateLimiters(Set.of(policies), backends, null, vertx, Set.of());
    }

    /** Admits while cumulative consumption per storage key stays within the request's capacity. */
    private static final class CountingLocalBackend implements RateLimitBackend {
        private final ConcurrentMap<String, AtomicLong> consumedByKey = new ConcurrentHashMap<>();

        @Override
        public Future<RateLimitBackendResult> consume(RateLimitBackendRequest request) {
            long capacity = request.algorithm().capacity();
            AtomicLong consumed = consumedByKey.computeIfAbsent(request.storageKey(), ignored -> new AtomicLong());
            long updated = consumed.addAndGet(request.cost());
            boolean admitted = updated <= capacity;
            if (!admitted) {
                // Rejected consumption never changes state.
                consumed.addAndGet(-request.cost());
            }
            long remaining = Math.max(0, capacity - consumed.get());
            Optional<Duration> retryAfter = admitted ? Optional.empty() : Optional.of(Duration.ofMillis(1));
            return Future.succeededFuture(
                    new RateLimitBackendResult(admitted, remaining, retryAfter, Optional.empty(), Optional.empty()));
        }
    }
}
