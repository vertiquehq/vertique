// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitBackendRequest;
import dev.vertique.ratelimit.spi.RateLimitBackendResult;
import io.vertx.core.Future;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.ToLongFunction;

/**
 * LOCAL {@link RateLimitBackend}: routes each request to the requesting policy's own bounded
 * {@link LocalRateLimitRegistry} (contracts/rate-limit-runtime.md, "Local engine contract" — the
 * registry, and its {@code maxTrackedKeys} budget, is per policy, never one shared pool).
 *
 * <p>The policy name is recovered from {@code request.storageKey()}'s leading {@code ':'}-delimited
 * segment — safe because policy-name syntax ({@code [A-Za-z0-9._~-]{1,128}}) never contains a colon,
 * so this segment is always exactly the storage key's owning policy name (see {@code
 * RateLimitStorageIdentity#canonicalInput}). One registry is created per policy name on first use,
 * sized by {@code maxTrackedKeysResolver}, and reused for every later request under that policy.
 *
 * <p>Package-private by design — reached only through {@link LocalRateLimitBackendFactory}'s public
 * static factory, which {@code dev.vertique.ratelimit.dagger.RateLimitCoreModule} calls. No Bucket4j
 * type appears past this class's own boundary, and this class itself never touches a Bucket4j type
 * directly — that stays inside {@link LocalRateLimitRegistry}.
 */
final class LocalBucket4jRateLimitBackend implements RateLimitBackend {

    private final ConcurrentMap<String, LocalRateLimitRegistry> registriesByPolicy = new ConcurrentHashMap<>();
    private final ToLongFunction<String> maxTrackedKeysResolver;
    private final long cleanupIntervalMs;
    private final Clock clock;

    LocalBucket4jRateLimitBackend(ToLongFunction<String> maxTrackedKeysResolver, long cleanupIntervalMs) {
        this(maxTrackedKeysResolver, cleanupIntervalMs, Clock.systemUTC());
    }

    LocalBucket4jRateLimitBackend(ToLongFunction<String> maxTrackedKeysResolver, long cleanupIntervalMs, Clock clock) {
        this.maxTrackedKeysResolver = Objects.requireNonNull(maxTrackedKeysResolver, "maxTrackedKeysResolver");
        this.cleanupIntervalMs = cleanupIntervalMs;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Future<RateLimitBackendResult> consume(RateLimitBackendRequest request) {
        String policyName = policyNameOf(request.storageKey());
        LocalRateLimitRegistry registry = registriesByPolicy.get(policyName);
        if (registry == null) {
            registry = registriesByPolicy.computeIfAbsent(policyName, name -> newRegistry(name, request.algorithm()));
        }
        return Future.succeededFuture(registry.consume(request.storageKey(), request.cost()));
    }

    private LocalRateLimitRegistry newRegistry(String policyName, TokenBucketRateLimit algorithm) {
        long maxTrackedKeys = maxTrackedKeysResolver.applyAsLong(policyName);
        return new LocalRateLimitRegistry(policyName, algorithm, maxTrackedKeys, cleanupIntervalMs, clock);
    }

    private static String policyNameOf(String storageKey) {
        int separator = storageKey.indexOf(':');
        return separator < 0 ? storageKey : storageKey.substring(0, separator);
    }
}
