// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client;

import dev.vertique.resilience.BackoffStrategy;
import dev.vertique.rest.client.meta.ClientMethodMeta;
import dev.vertique.rest.client.meta.ClientMethodMeta.ResilienceConfig;
import dev.vertique.rest.client.meta.ClientMethodMeta.RetryConfig;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Expectation;
import io.vertx.core.http.HttpResponseHead;
import jakarta.annotation.Nullable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;

/**
 * Resolves resilience configuration (timeout, circuit breaker, retry policy, backoff strategy) for
 * REST client method invocations.
 *
 * <p>Circuit breakers are created lazily on first invocation and cached per method key in an
 * internal {@link ConcurrentHashMap}. Resolution order for circuit breakers:
 * <ol>
 *   <li>Method-level {@code @CircuitBreaker} — creates a dedicated breaker; retry policy applied
 *       if {@code @Retry} is also present</li>
 *   <li>{@code @Retry} without {@code @CircuitBreaker} — creates a pass-through breaker
 *       ({@code maxFailures = Integer.MAX_VALUE}) used solely for the retry mechanism</li>
 *   <li>Interface-level circuit breaker — no retry policy applied here</li>
 * </ol>
 */
@Slf4j
final class RestClientResilienceResolver {

    private final String clientName;
    private final long readTimeoutMs;

    @Nullable
    private final Expectation<HttpResponseHead> defaultExpectation;

    @Nullable
    private final io.vertx.circuitbreaker.CircuitBreaker interfaceCircuitBreaker;

    private final RestClientRetryPolicy retryPolicy;
    private final BackoffStrategy backoffStrategy;
    private final io.vertx.core.Vertx vertx;

    /** Cache for method-level circuit breakers keyed by method name. */
    private final ConcurrentHashMap<String, io.vertx.circuitbreaker.CircuitBreaker> methodCircuitBreakers =
            new ConcurrentHashMap<>();

    /**
     * Creates a new resilience resolver.
     *
     * @param clientName the logical REST client name, used for circuit breaker naming and logging
     * @param readTimeoutMs the default request timeout in milliseconds; overridden per method by
     *     {@code @Timeout}
     * @param defaultExpectation the builder-level response expectation; {@code null} if not set
     * @param interfaceCircuitBreaker the interface-level circuit breaker; {@code null} if not
     *     configured
     * @param retryPolicy the builder-level retry policy used when no {@code retryOn} filter is set
     * @param backoffStrategy the builder-level backoff strategy used when the method annotation
     *     uses {@link BackoffStrategy.Default}
     * @param vertx the Vert.x instance used to create method-level circuit breakers on demand
     */
    RestClientResilienceResolver(
            String clientName,
            long readTimeoutMs,
            @Nullable Expectation<HttpResponseHead> defaultExpectation,
            @Nullable io.vertx.circuitbreaker.CircuitBreaker interfaceCircuitBreaker,
            RestClientRetryPolicy retryPolicy,
            BackoffStrategy backoffStrategy,
            io.vertx.core.Vertx vertx) {
        this.clientName = clientName;
        this.readTimeoutMs = readTimeoutMs;
        this.defaultExpectation = defaultExpectation;
        this.interfaceCircuitBreaker = interfaceCircuitBreaker;
        this.retryPolicy = retryPolicy;
        this.backoffStrategy = backoffStrategy;
        this.vertx = vertx;
    }

    // --- Timeout ---

    /**
     * Resolves the effective timeout for a method: method-level {@code @Timeout} wins over the
     * builder-level default.
     *
     * @param meta the method metadata
     * @return the timeout in milliseconds
     */
    long resolveTimeout(ClientMethodMeta meta) {
        ResilienceConfig resilience = meta.resilience();
        if (resilience != null && resilience.timeoutMs() >= 0) {
            return resilience.timeoutMs();
        }
        return readTimeoutMs;
    }

    // --- Expectation ---

    /**
     * Resolves the effective {@link Expectation}: method-level annotation wins over the builder
     * default.
     *
     * @param meta the method metadata
     * @return the expectation to apply, or {@code null} if none configured
     */
    @Nullable
    Expectation<HttpResponseHead> resolveExpectation(ClientMethodMeta meta) {
        if (meta.expectation() != null) {
            return meta.expectation();
        }
        return defaultExpectation;
    }

    // --- Circuit Breaker ---

    /**
     * Resolves the circuit breaker to use for a method invocation.
     *
     * <p>Circuit breakers are cached per method key in the internal map.
     *
     * @param meta the method metadata
     * @return the circuit breaker to use, or {@code null} if neither CB nor retry is configured
     */
    @Nullable
    io.vertx.circuitbreaker.CircuitBreaker resolveCircuitBreaker(ClientMethodMeta meta) {
        ResilienceConfig resilience = meta.resilience();

        // Method-level @CircuitBreaker — may also have @Retry
        if (resilience != null && resilience.circuitBreaker() != null) {
            String key = clientName + "::" + meta.methodMetadata().name();
            return methodCircuitBreakers.computeIfAbsent(key, k -> {
                log.debug(
                        "Creating method-level circuit breaker '{}' for {}.{}()",
                        k,
                        clientName,
                        meta.methodMetadata().name());
                CircuitBreakerOptions opts = new CircuitBreakerOptions(resilience.circuitBreaker());
                RetryConfig retryConfig = resilience.retry();
                if (retryConfig != null) {
                    opts.setMaxRetries(retryConfig.maxRetries());
                }
                io.vertx.circuitbreaker.CircuitBreaker cb =
                        io.vertx.circuitbreaker.CircuitBreaker.create(k, vertx, opts);
                if (retryConfig != null) {
                    cb.retryPolicy(composeVertxRetryPolicy(meta));
                }
                return cb;
            });
        }

        // @Retry without @CircuitBreaker — create a pass-through breaker for retry mechanics only
        if (resilience != null && resilience.retry() != null) {
            String key = clientName + "::" + meta.methodMetadata().name() + "::retry";
            return methodCircuitBreakers.computeIfAbsent(key, k -> {
                log.debug(
                        "Creating retry-only circuit breaker '{}' for {}.{}()",
                        k,
                        clientName,
                        meta.methodMetadata().name());
                RetryConfig retryConfig = resilience.retry();
                CircuitBreakerOptions opts = new CircuitBreakerOptions()
                        .setMaxFailures(Integer.MAX_VALUE) // circuit never opens
                        .setMaxRetries(retryConfig.maxRetries());
                io.vertx.circuitbreaker.CircuitBreaker cb =
                        io.vertx.circuitbreaker.CircuitBreaker.create(k, vertx, opts);
                cb.retryPolicy(composeVertxRetryPolicy(meta));
                return cb;
            });
        }

        // Fall back to interface-level circuit breaker (no retry applied)
        return interfaceCircuitBreaker;
    }

    // --- Backoff and Retry ---

    /**
     * Resolves the effective {@link BackoffStrategy} for a method. If the method's
     * {@link RetryConfig} specifies a custom backoff class (not the sentinel
     * {@link BackoffStrategy.Default}), an instance is created via reflection. Falls back to the
     * builder-level backoff strategy on failure or when no custom class is set.
     *
     * @param retryConfig the retry configuration for the method; {@code null} means use builder
     *     default
     * @return the resolved backoff strategy, never {@code null}
     */
    private BackoffStrategy resolveBackoffStrategy(@Nullable RetryConfig retryConfig) {
        if (retryConfig != null && retryConfig.backoffClass() != BackoffStrategy.Default.class) {
            try {
                return retryConfig.backoffClass().getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                log.warn(
                        "Failed to instantiate BackoffStrategy {}, using builder default",
                        retryConfig.backoffClass().getName(),
                        e);
            }
        }
        return this.backoffStrategy;
    }

    /**
     * Builds a Vert.x {@link io.vertx.circuitbreaker.RetryPolicy} from the method's resilience
     * metadata combined with the builder-level retry policy and backoff strategy.
     *
     * <p>The policy evaluates three filters in priority order:
     * <ol>
     *   <li>{@link RetryConfig#abortOn()} — highest priority; always stops retries</li>
     *   <li>{@link RetryConfig#retryOn()} — when non-empty, only matching types are retried</li>
     *   <li>{@link RestClientRetryPolicy#shouldRetry} — fallback when {@code retryOn} is empty</li>
     * </ol>
     *
     * @param meta the method metadata containing the resilience configuration
     * @return the composed Vert.x retry policy
     */
    private io.vertx.circuitbreaker.RetryPolicy composeVertxRetryPolicy(ClientMethodMeta meta) {
        RetryConfig retryConfig = meta.resilience() != null ? meta.resilience().retry() : null;
        Set<Class<? extends Throwable>> abortOn = retryConfig != null ? retryConfig.abortOn() : Set.of();
        Set<Class<? extends Throwable>> retryOn = retryConfig != null ? retryConfig.retryOn() : Set.of();
        BackoffStrategy effectiveBackoff = resolveBackoffStrategy(retryConfig);

        return (error, retryCount) -> {
            // 1. abortOn — highest priority, always stops
            if (abortOn.stream().anyMatch(t -> t.isInstance(error))) {
                return -1L;
            }
            // 2. retryOn — if specified, only those types are retried
            if (!retryOn.isEmpty() && retryOn.stream().noneMatch(t -> t.isInstance(error))) {
                return -1L;
            }
            // 3. RestClientRetryPolicy — fallback when no retryOn filter is set
            if (retryOn.isEmpty() && !retryPolicy.shouldRetry(error, retryCount)) {
                return -1L;
            }
            // 4. Compute delay
            return effectiveBackoff.delay(retryCount);
        };
    }
}
