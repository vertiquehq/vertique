// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import dev.vertique.ratelimit.spi.RateLimitBackend;
import io.vertx.core.Vertx;
import jakarta.inject.Singleton;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Single injected runtime entry point: one instance per application graph, no static registry
 * (D013, contracts/rate-limit-runtime.md "Exact API shape").
 *
 * <p>This task's shape is a subset of the exact contract API: only the two {@code limiter(...)}
 * overloads exist here. {@code adapterSupport()} and {@code close()} are a later task's artifacts
 * (contracts/rate-limit-runtime.md, "Exact API shape"), and this task's constructor performs no
 * eager completeness validation — it proves only the known-policy path.
 */
@Singleton
public final class RateLimiters {

    private final Map<String, RateLimitPolicy> policiesByName;
    private final Map<RateLimitMode, RateLimitBackend> backends;
    private final Vertx vertx;
    private final ConcurrentMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    public RateLimiters(Set<RateLimitPolicy> policies, Map<RateLimitMode, RateLimitBackend> backends, Vertx vertx) {
        Objects.requireNonNull(policies, "policies");
        this.policiesByName =
                policies.stream().collect(Collectors.toUnmodifiableMap(RateLimitPolicy::name, Function.identity()));
        this.backends = Map.copyOf(Objects.requireNonNull(backends, "backends"));
        this.vertx = Objects.requireNonNull(vertx, "vertx");
    }

    /**
     * Resolves the untyped handle for {@code policyName}, caching one instance per name.
     *
     * @param policyName the declared policy name
     * @return the resolved handle
     * @throws IllegalArgumentException synchronously, before any {@link io.vertx.core.Future} is
     *     created, when no policy named {@code policyName} is declared
     */
    public RateLimiter limiter(String policyName) {
        RateLimitPolicy policy = requirePolicy(policyName);
        return limiters.computeIfAbsent(policyName, ignored -> newLimiter(policy));
    }

    /**
     * Resolves a handle for {@code policyName} bound to {@code keySelector}. Applies the same
     * synchronous unknown-policy validation as {@link #limiter(String)}.
     *
     * @param policyName the declared policy name
     * @param keySelector derives a {@link RateLimitKey} from the caller's own input type
     * @param <K> the caller-supplied input type
     * @return the resolved keyed handle
     * @throws IllegalArgumentException synchronously, identically to {@link #limiter(String)}
     */
    public <K> KeyedRateLimiter<K> limiter(String policyName, Function<? super K, RateLimitKey> keySelector) {
        Objects.requireNonNull(keySelector, "keySelector");
        return new KeyedRateLimiter<>(limiter(policyName), keySelector);
    }

    private RateLimitPolicy requirePolicy(String policyName) {
        Objects.requireNonNull(policyName, "policyName");
        RateLimitPolicy policy = policiesByName.get(policyName);
        if (policy == null) {
            throw new IllegalArgumentException("Unknown rate-limit policy: " + policyName);
        }
        return policy;
    }

    private RateLimiter newLimiter(RateLimitPolicy policy) {
        RateLimitBackend backend = backends.get(policy.mode());
        if (backend == null) {
            throw new IllegalStateException("No RateLimitBackend bound for mode " + policy.mode());
        }
        return new RateLimiter(policy, backend, vertx);
    }
}
