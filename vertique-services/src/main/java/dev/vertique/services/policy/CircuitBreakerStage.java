// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.policy;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.circuitbreaker.RetryPolicy;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Policy stage that wraps invocation with a Vert.x circuit breaker.
 *
 * <p>A single stage covers all three resilience concerns configured via {@link CircuitBreaker},
 * {@link Timeout}, and {@link Retry} annotations:
 * <ul>
 *   <li>{@link Timeout} sets the per-attempt timeout via {@link CircuitBreakerOptions#setTimeout}.</li>
 *   <li>{@link Retry} sets the maximum retry count and backoff delay.</li>
 *   <li>{@link CircuitBreaker} enables failure tracking; without it, the circuit is effectively
 *       disabled ({@code maxFailures = Integer.MAX_VALUE}) so the CB is used only for timeout and
 *       retry.</li>
 * </ul>
 *
 * <p>Lifecycle: circuit breaker instances are created per-operation at verticle start and are
 * implicitly cleaned up when the Vert.x instance shuts down. There is no explicit {@code close()}
 * call required.
 */
@Slf4j
public class CircuitBreakerStage implements PolicyStage {

    private final io.vertx.circuitbreaker.CircuitBreaker breaker;

    /**
     * Creates a circuit breaker stage backed by the given Vert.x circuit breaker.
     *
     * @param breaker the Vert.x circuit breaker instance
     */
    public CircuitBreakerStage(io.vertx.circuitbreaker.CircuitBreaker breaker) {
        this.breaker = breaker;
    }

    /**
     * Creates a circuit breaker stage from individual policy parameters.
     *
     * <p>Creates a new Vert.x circuit breaker with the given parameters. The name is used to
     * identify the circuit breaker on the event bus for metrics.
     *
     * @param vertx the Vert.x instance
     * @param name the circuit breaker name (typically the operation address)
     * @param maxFailures number of failures before the circuit opens; use {@link Integer#MAX_VALUE}
     *     to effectively disable circuit tracking
     * @param timeoutMs per-attempt timeout in milliseconds; {@code -1} disables timeout
     * @param resetTimeoutMs time in milliseconds before the circuit transitions to half-open;
     *     {@code -1} disables the reset timer
     * @param maxRetries maximum number of retry attempts; {@code 0} means no retries
     * @param retryPolicy the retry delay policy, or {@code null} to use default (no delay)
     * @return a new circuit breaker stage
     */
    public static CircuitBreakerStage create(
            Vertx vertx,
            String name,
            int maxFailures,
            long timeoutMs,
            long resetTimeoutMs,
            int maxRetries,
            RetryPolicy retryPolicy) {
        CircuitBreakerOptions opts = new CircuitBreakerOptions()
                .setMaxFailures(maxFailures)
                .setTimeout(timeoutMs)
                .setResetTimeout(resetTimeoutMs)
                .setMaxRetries(maxRetries);
        io.vertx.circuitbreaker.CircuitBreaker cb = io.vertx.circuitbreaker.CircuitBreaker.create(name, vertx, opts);
        if (retryPolicy != null) {
            cb.retryPolicy(retryPolicy);
        }
        return new CircuitBreakerStage(cb);
    }

    /**
     * Returns a {@link RetryPolicy} implementing exponential backoff.
     *
     * <p>The delay formula is: {@code min(delayMs × multiplier^retryCount, maxDelayMs)},
     * where {@code retryCount} is the 0-based retry count (0 = first retry).
     *
     * @param delayMs initial delay in milliseconds for the first retry
     * @param multiplier exponential backoff multiplier; {@code 1.0} means fixed delay
     * @param maxDelayMs maximum delay cap in milliseconds
     * @return a {@link RetryPolicy} computing the capped backoff delay
     */
    public static RetryPolicy backoffRetryPolicy(long delayMs, double multiplier, long maxDelayMs) {
        return (failure, retryCount) -> {
            double delay = delayMs * Math.pow(multiplier, retryCount);
            return Math.min((long) delay, maxDelayMs);
        };
    }

    /**
     * Executes the next stage through the circuit breaker.
     *
     * <p>Delegates to {@link io.vertx.circuitbreaker.CircuitBreaker#execute(Supplier)}. If the
     * circuit is open, the future fails immediately. Otherwise, the downstream operation is
     * invoked and its result tracked against the failure threshold.
     *
     * @param meta the operation metadata
     * @param body the incoming request body
     * @param next the next stage in the pipeline
     * @return a future that completes with the operation result or fails if the circuit is open
     */
    @Override
    public Future<Object> execute(ServiceMethodMeta meta, DispatchEnvelope<?> body, Supplier<Future<Object>> next) {
        return breaker.execute(next);
    }
}
