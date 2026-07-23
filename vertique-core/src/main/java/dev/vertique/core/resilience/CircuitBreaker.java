// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.resilience;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Configures a circuit breaker for the annotated type or method.
 *
 * <p>A circuit breaker tracks consecutive failures. When the failure count reaches
 * {@link #maxFailures()}, the circuit transitions to the open state and subsequent calls fail
 * immediately without invoking the underlying operation. After {@link #resetTimeoutMs()}
 * milliseconds, the circuit transitions to half-open and allows a single probe attempt; a success
 * closes the circuit and a failure reopens it.
 *
 * <p>When applied at the type level, the configuration acts as the default for all methods on that
 * type. A method-level {@code @CircuitBreaker} annotation completely replaces the type-level
 * annotation for that method — individual fields are not merged.
 *
 * <p>Consumer modules (e.g. {@code rest-client}) delegate to Vert.x's {@code CircuitBreaker}
 * implementation. This annotation is a pure-Java configuration carrier with no Vert.x dependency.
 *
 * <h2>Timeout interaction</h2>
 *
 * <p>The {@link #timeoutMs()} field is a convenience shortcut for simple cases. When a
 * {@link Timeout} annotation is also present on the same element, {@link Timeout} takes precedence.
 * Use {@code -1} (the default) to leave timeout configuration to {@link Timeout} or the consumer
 * module's own default.
 *
 * @see Retry
 * @see Timeout
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface CircuitBreaker {

    /**
     * Number of consecutive failures before the circuit transitions to open state.
     *
     * @return the failure threshold; defaults to {@code 5}
     */
    int maxFailures() default 5;

    /**
     * Per-attempt timeout in milliseconds; {@code -1} means not set here.
     *
     * <p>When set to {@code -1} (the default), the timeout is not configured by this annotation.
     * Use {@link Timeout} for explicit timeout control, or rely on the consumer module's default.
     * When a {@link Timeout} annotation is also present, it takes precedence over this field.
     *
     * @return the per-attempt timeout in milliseconds, or {@code -1} to disable
     */
    long timeoutMs() default -1;

    /**
     * Time in milliseconds before the circuit transitions from open to half-open, allowing a probe
     * attempt.
     *
     * @return the reset timeout in milliseconds; defaults to {@code 10000}
     */
    long resetTimeoutMs() default 10_000;
}
