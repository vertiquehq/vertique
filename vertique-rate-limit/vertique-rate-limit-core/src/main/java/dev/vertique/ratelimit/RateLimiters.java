// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.ratelimit;

import dev.vertique.ratelimit.spi.RateLimitBackend;
import dev.vertique.ratelimit.spi.RateLimitObserver;
import io.vertx.core.Vertx;
import jakarta.inject.Singleton;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Single injected runtime entry point: one instance per application graph, no static registry
 * (D013, contracts/rate-limit-runtime.md "Exact API shape").
 *
 * <p>This task's shape is a subset of the exact contract API: only the two {@code limiter(...)}
 * overloads exist here. {@code adapterSupport()} and {@code close()} are a later task's artifacts
 * (contracts/rate-limit-runtime.md, "Exact API shape").
 *
 * <p>The constructor eagerly walks every declared policy against the bound backend map and fails
 * fast, before any handle is requested, on: a duplicate policy name, an enabled policy whose mode
 * has no bound backend, a missing {@code keyDerivation.secret} when any enabled policy is {@code
 * CLUSTERED}, and a resolved secret shorter than 32 bytes (UTF-8) under the same condition
 * (contracts/rate-limit-runtime.md, "Startup validation"; D013 — the same eager-construction
 * precedent {@code ResilienceModule} follows). T004 wires the {@code @IntoSet
 * ApplicationShutdownStep} that forces this constructor to run unconditionally at bootstrap; this
 * validation is independently provable by direct construction, as this task's tests do.
 */
@Singleton
public final class RateLimiters {

    private static final int MIN_SECRET_BYTES = 32;
    private static final int MAX_POLICIES = 10_000;

    private final Map<String, RateLimitPolicy> policiesByName;
    private final Map<RateLimitMode, RateLimitBackend> backends;
    private final Vertx vertx;
    private final Set<RateLimitObserver> observers;
    private final ConcurrentMap<String, RateLimiter> limiters = new ConcurrentHashMap<>();

    /**
     * @param policies every declared policy, already resolved to one flat set (see
     *     {@link RateLimitPolicy#mergeConfigOverProgrammatic})
     * @param backends the bound backend provider map
     * @param keyDerivationSecret the resolved {@code rateLimit.keyDerivation.secret}, or {@code
     *     null}/blank when not configured
     * @param vertx application Vert.x instance
     * @param observers every bound {@link RateLimitObserver}, dispatched synchronously and
     *     per-observer exception-isolated at every completed decision
     * @throws IllegalStateException per the eager startup-validation matrix documented on this
     *     class
     */
    public RateLimiters(
            Set<RateLimitPolicy> policies,
            Map<RateLimitMode, RateLimitBackend> backends,
            String keyDerivationSecret,
            Vertx vertx,
            Set<RateLimitObserver> observers) {
        Objects.requireNonNull(policies, "policies");
        this.policiesByName = indexByName(policies);
        this.backends = Map.copyOf(Objects.requireNonNull(backends, "backends"));
        this.vertx = Objects.requireNonNull(vertx, "vertx");
        this.observers =
                Collections.unmodifiableSet(new LinkedHashSet<>(Objects.requireNonNull(observers, "observers")));
        validateBackendCoverage(this.policiesByName.values(), this.backends);
        validateClusteredSecret(this.policiesByName.values(), keyDerivationSecret);
    }

    private static Map<String, RateLimitPolicy> indexByName(Set<RateLimitPolicy> policies) {
        if (policies.size() > MAX_POLICIES) {
            throw new IllegalStateException(
                    "At most " + MAX_POLICIES + " rate-limit policies are permitted, got " + policies.size());
        }
        Map<String, RateLimitPolicy> byName = new LinkedHashMap<>();
        for (RateLimitPolicy policy : policies) {
            RateLimitPolicy previous = byName.putIfAbsent(policy.name(), policy);
            if (previous != null) {
                throw new IllegalStateException("Duplicate rate-limit policy name: " + policy.name());
            }
        }
        return Map.copyOf(byName);
    }

    private static void validateBackendCoverage(
            Collection<RateLimitPolicy> policies, Map<RateLimitMode, RateLimitBackend> backends) {
        for (RateLimitPolicy policy : policies) {
            if (policy.enabled() && !backends.containsKey(policy.mode())) {
                throw new IllegalStateException("No RateLimitBackend bound for mode " + policy.mode()
                        + " required by enabled rate-limit policy " + policy.name());
            }
        }
    }

    private static void validateClusteredSecret(Collection<RateLimitPolicy> policies, String keyDerivationSecret) {
        boolean anyEnabledClustered =
                policies.stream().anyMatch(policy -> policy.enabled() && policy.mode() == RateLimitMode.CLUSTERED);
        if (!anyEnabledClustered) {
            return;
        }
        if (keyDerivationSecret == null || keyDerivationSecret.isEmpty()) {
            throw new IllegalStateException(
                    "rateLimit.keyDerivation.secret is required when any enabled policy is CLUSTERED");
        }
        int secretBytes = keyDerivationSecret.getBytes(StandardCharsets.UTF_8).length;
        if (secretBytes < MIN_SECRET_BYTES) {
            throw new IllegalStateException("rateLimit.keyDerivation.secret must be at least " + MIN_SECRET_BYTES
                    + " bytes (UTF-8) when any enabled policy is CLUSTERED");
        }
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
        return new RateLimiter(policy, backend, vertx, observers);
    }
}
