// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.services.policy;

import dev.vertique.core.resilience.BackoffStrategy;
import dev.vertique.core.resilience.BackoffStrategyResolver;
import dev.vertique.core.resilience.CircuitBreaker;
import dev.vertique.core.resilience.ResilienceAnnotations;
import dev.vertique.core.resilience.Retry;
import dev.vertique.core.resilience.Timeout;
import dev.vertique.services.config.CircuitBreakerOverride;
import dev.vertique.services.config.RetryOverride;
import dev.vertique.services.config.ServiceConfig;
import dev.vertique.services.config.ServiceOperationConfig;
import dev.vertique.services.config.ServicesConfig.ServiceKey;
import dev.vertique.services.config.TimeoutOverride;
import dev.vertique.services.dispatch.DispatchPipeline;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import io.vertx.circuitbreaker.RetryPolicy;
import io.vertx.core.Vertx;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;

/**
 * Builds a {@link DispatchPipeline} from policy annotations and configuration overrides.
 *
 * <p>A single Vert.x {@link io.vertx.circuitbreaker.CircuitBreaker} stage covers all configured
 * resilience policies:
 * <ul>
 *   <li>{@link Timeout} sets the per-attempt timeout applied to each retry attempt individually.</li>
 *   <li>{@link Retry} configures the retry count and exponential backoff delay.</li>
 *   <li>{@link CircuitBreaker} enables failure tracking. Without it, the circuit is effectively
 *       disabled ({@code maxFailures = Integer.MAX_VALUE}) and only timeout and retry apply.</li>
 * </ul>
 *
 * <p>When {@link Retry#retryOn()} or {@link Retry#abortOn()} is configured, the composed
 * retry policy filters exceptions before computing backoff delay. {@code abortOn} has highest
 * priority; {@code retryOn} (when non-empty) restricts retries to matching exception types.
 *
 * <p>Configuration overrides are read from the typed per-operation config at
 * {@code services.contracts.{namespace}.{name}.operations.{operation}.{policy}.{field}}.
 * For example: {@code services → contracts → orders → userService → operations → getUser → timeout → valueMs}.
 *
 * <p>Returns {@code null} from {@link #build(ServiceMethodMeta)} when no policy annotations are
 * configured for the operation, allowing callers to skip pipeline construction entirely.
 */
@Slf4j
public class PolicyChainBuilder {

    private final Vertx vertx;
    private final Map<ServiceKey, ServiceConfig> serviceConfigIndex;

    /**
     * Creates a new policy chain builder.
     *
     * @param vertx the Vert.x instance passed to policy stages that require it
     * @param serviceConfigIndex the {@code (namespace, name) -> ServiceConfig} index supplying typed
     *     per-operation policy overrides
     */
    public PolicyChainBuilder(Vertx vertx, Map<ServiceKey, ServiceConfig> serviceConfigIndex) {
        this.vertx = vertx;
        this.serviceConfigIndex = serviceConfigIndex;
    }

    /**
     * Builds a dispatch pipeline for the given operation metadata.
     *
     * <p>Returns {@code null} if no policy annotations are configured for this operation,
     * allowing {@link dev.vertique.services.ServiceMethodInvoker} to invoke the method
     * directly without pipeline overhead.
     *
     * @param meta the operation metadata with resolved policy annotations
     * @return the pipeline, or {@code null} if no policies apply
     */
    public DispatchPipeline build(ServiceMethodMeta meta) {
        ResilienceAnnotations policies = meta.resilienceAnnotations();
        if (!policies.hasAny()) {
            return null;
        }

        ServiceOperationConfig overrides = getOverrides(meta);
        CircuitBreaker cb = policies.circuitBreaker();
        Retry retry = policies.retry();
        Timeout timeout = policies.timeout();

        CircuitBreakerOverride cbOverride = overrides != null ? overrides.circuitBreaker() : null;
        TimeoutOverride timeoutOverride = overrides != null ? overrides.timeout() : null;
        RetryOverride retryOverride = overrides != null ? overrides.retry() : null;

        // --- maxFailures: Integer.MAX_VALUE when no @CircuitBreaker (circuit never trips) ---
        int maxFailures = cb != null
                ? override(cbOverride != null ? cbOverride.maxFailures() : null, cb.maxFailures())
                : Integer.MAX_VALUE;
        long resetTimeoutMs = cb != null
                ? override(cbOverride != null ? cbOverride.resetTimeoutMs() : null, cb.resetTimeoutMs())
                : -1L;

        // --- timeoutMs: @Timeout takes priority over @CircuitBreaker.timeoutMs ---
        long timeoutMs;
        if (timeout != null) {
            Long valueMs = timeoutOverride != null ? timeoutOverride.valueMs() : null;
            timeoutMs = override(valueMs, timeout.unit().toMillis(timeout.value()));
        } else if (cb != null) {
            timeoutMs = override(cbOverride != null ? cbOverride.timeoutMs() : null, cb.timeoutMs());
        } else {
            timeoutMs = -1L;
        }

        // --- maxRetries and retryPolicy from @Retry with retryOn/abortOn filtering ---
        int maxRetries = 0;
        RetryPolicy vertxRetryPolicy = null;
        if (retry != null) {
            maxRetries = override(retryOverride != null ? retryOverride.maxRetries() : null, retry.maxRetries());

            // Resolve backoff strategy — custom class wins, then config overrides, then annotation inline params
            BackoffStrategy effectiveBackoff;
            if (retry.backoff() != BackoffStrategy.Default.class) {
                effectiveBackoff = BackoffStrategyResolver.resolve(retry, null);
            } else {
                long delayMs = override(retryOverride != null ? retryOverride.delayMs() : null, retry.delayMs());
                double backoffMultiplier = override(
                        retryOverride != null ? retryOverride.backoffMultiplier() : null, retry.backoffMultiplier());
                long maxDelayMs =
                        override(retryOverride != null ? retryOverride.maxDelayMs() : null, retry.maxDelayMs());
                effectiveBackoff = BackoffStrategy.exponential(delayMs, backoffMultiplier, maxDelayMs);
            }

            // Build retryOn/abortOn sets for eligibility filtering (dedup to tolerate duplicate entries)
            Set<Class<? extends Throwable>> abortOn =
                    Arrays.stream(retry.abortOn()).collect(Collectors.toUnmodifiableSet());
            Set<Class<? extends Throwable>> retryOn =
                    Arrays.stream(retry.retryOn()).collect(Collectors.toUnmodifiableSet());

            // Compose Vert.x RetryPolicy: eligibility filtering + delay computation
            vertxRetryPolicy = composeVertxRetryPolicy(abortOn, retryOn, effectiveBackoff);
        }

        return new DispatchPipeline(List.of(CircuitBreakerStage.create(
                vertx, meta.address(), maxFailures, timeoutMs, resetTimeoutMs, maxRetries, vertxRetryPolicy)));
    }

    /**
     * Composes a Vert.x {@link RetryPolicy} that evaluates exception eligibility and computes
     * backoff delay.
     *
     * <p>Evaluation priority:
     * <ol>
     *   <li>{@code abortOn} — highest priority; matching exceptions abort immediately</li>
     *   <li>{@code retryOn} — when non-empty, only matching types are retried</li>
     *   <li>When {@code retryOn} is empty, all failures are retried (default behavior)</li>
     *   <li>Backoff delay computed by the effective {@link BackoffStrategy}</li>
     * </ol>
     *
     * @param abortOn exception types that abort immediately
     * @param retryOn exception types that trigger retry (empty = retry all)
     * @param backoff the backoff strategy for delay computation
     * @return the composed Vert.x retry policy
     */
    private RetryPolicy composeVertxRetryPolicy(
            Set<Class<? extends Throwable>> abortOn, Set<Class<? extends Throwable>> retryOn, BackoffStrategy backoff) {
        return (error, retryCount) -> {
            // 1. abortOn — highest priority, always stops
            if (abortOn.stream().anyMatch(t -> t.isInstance(error))) {
                return -1L;
            }
            // 2. retryOn — if specified, only those types are retried
            if (!retryOn.isEmpty() && retryOn.stream().noneMatch(t -> t.isInstance(error))) {
                return -1L;
            }
            // 3. Compute delay
            return backoff.delay(retryCount);
        };
    }

    /**
     * Extracts the typed per-operation config overrides from the services index.
     *
     * <p>Config path: {@code services.contracts.{namespace}.{name}.operations.{operation}}. A service
     * absent from the index or an operation with no override block resolves to {@code null} — the
     * annotation values then apply.
     *
     * @param meta the operation metadata providing namespace, name, and operation segments
     * @return the typed per-operation override, or {@code null} if not present
     */
    private ServiceOperationConfig getOverrides(ServiceMethodMeta meta) {
        ServiceConfig serviceConfig = serviceConfigIndex.get(new ServiceKey(meta.namespace(), meta.name()));
        if (serviceConfig == null) {
            return null;
        }
        return serviceConfig.operations().stream()
                .filter(op -> op.operation().equals(meta.operation()))
                .findFirst()
                .orElse(null);
    }

    /**
     * Returns the override value when present, otherwise the annotation default.
     *
     * @param override the config override value, or {@code null} when not overridden
     * @param annotationValue the annotation-supplied default
     * @return the override when non-{@code null}, otherwise {@code annotationValue}
     */
    private static int override(Integer override, int annotationValue) {
        return override != null ? override : annotationValue;
    }

    /**
     * Returns the override value when present, otherwise the annotation default.
     *
     * @param override the config override value, or {@code null} when not overridden
     * @param annotationValue the annotation-supplied default
     * @return the override when non-{@code null}, otherwise {@code annotationValue}
     */
    private static long override(Long override, long annotationValue) {
        return override != null ? override : annotationValue;
    }

    /**
     * Returns the override value when present, otherwise the annotation default.
     *
     * @param override the config override value, or {@code null} when not overridden
     * @param annotationValue the annotation-supplied default
     * @return the override when non-{@code null}, otherwise {@code annotationValue}
     */
    private static double override(Double override, double annotationValue) {
        return override != null ? override : annotationValue;
    }
}
