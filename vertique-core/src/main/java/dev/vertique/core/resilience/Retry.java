// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.resilience;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures retry behaviour for the annotated type or method.
 *
 * <p>When a call fails, the framework evaluates whether to retry using the following priority
 * order:
 *
 * <ol>
 *   <li>{@link #abortOn()} — if the failure matches any listed type, retry is skipped
 *       immediately (highest priority)</li>
 *   <li>{@link #retryOn()} — when non-empty, only failures matching a listed type are retried;
 *       the {@link RetryPolicy} is bypassed</li>
 *   <li>{@link RetryPolicy} — fallback when {@code retryOn} is empty; the active policy decides
 *       based on the failure type and retry count</li>
 * </ol>
 *
 * <p>The delay between attempts is computed by a {@link BackoffStrategy}. Resolution order:
 *
 * <ol>
 *   <li>If {@link #backoff()} is not {@link BackoffStrategy.Default}, a new instance of that class
 *       is created via its no-arg constructor and used as the strategy. Inline delay parameters are
 *       ignored.</li>
 *   <li>Otherwise, an {@link BackoffStrategy#exponential exponential} strategy is built from
 *       {@link #delayMs()}, {@link #backoffMultiplier()}, and {@link #maxDelayMs()}.</li>
 * </ol>
 *
 * <p>When applied at the type level, the configuration acts as the default for all methods on that
 * type. A method-level {@code @Retry} annotation completely replaces the type-level annotation for
 * that method — individual fields are not merged.
 *
 * @see BackoffStrategy
 * @see RetryPolicy
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Retry {

    /**
     * Maximum number of retry attempts after the initial failure.
     *
     * @return the retry count; defaults to {@code 3}
     */
    int maxRetries() default 3;

    /**
     * Initial delay in milliseconds before the first retry.
     *
     * <p>Ignored when {@link #backoff()} specifies a custom {@link BackoffStrategy} class.
     *
     * @return the base delay in milliseconds; defaults to {@code 500}
     */
    long delayMs() default 500;

    /**
     * Exponential growth factor applied to {@link #delayMs()} on each successive retry.
     *
     * <p>A value of {@code 1.0} produces a fixed delay equal to {@link #delayMs()}. Ignored when
     * {@link #backoff()} specifies a custom {@link BackoffStrategy} class.
     *
     * @return the backoff multiplier; defaults to {@code 2.0}
     */
    double backoffMultiplier() default 2.0;

    /**
     * Upper bound on the computed delay before jitter is added.
     *
     * <p>Ignored when {@link #backoff()} specifies a custom {@link BackoffStrategy} class.
     *
     * @return the maximum delay in milliseconds; defaults to {@code 30000}
     */
    long maxDelayMs() default 30_000;

    /**
     * Custom {@link BackoffStrategy} class to use instead of the inline delay parameters.
     *
     * <p>The class must have a public no-arg constructor. When set to a class other than
     * {@link BackoffStrategy.Default}, it takes precedence over {@link #delayMs()},
     * {@link #backoffMultiplier()}, and {@link #maxDelayMs()}.
     *
     * @return the custom backoff class, or {@link BackoffStrategy.Default} to use inline
     *     parameters
     */
    Class<? extends BackoffStrategy> backoff() default BackoffStrategy.Default.class;

    /**
     * Exception types that trigger a retry.
     *
     * <p>When non-empty, only failures whose type matches one of the listed types (or a subtype)
     * will be retried. This overrides the active {@link RetryPolicy}. When empty, the
     * {@link RetryPolicy} is consulted instead.
     *
     * @return the exception types eligible for retry; defaults to empty (use {@link RetryPolicy})
     */
    Class<? extends Throwable>[] retryOn() default {};

    /**
     * Exception types that immediately abort retrying, regardless of {@link #retryOn()} or the
     * active {@link RetryPolicy}.
     *
     * <p>This is evaluated with the highest priority — even if the failure type matches
     * {@link #retryOn()}, it will not be retried if it also matches {@code abortOn}.
     *
     * @return the exception types that prevent retry; defaults to empty
     */
    Class<? extends Throwable>[] abortOn() default {};
}
