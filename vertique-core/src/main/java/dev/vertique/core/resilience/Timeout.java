// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.resilience;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/**
 * Configures a per-attempt timeout for the annotated type or method.
 *
 * <p>Each attempt — including retries — gets its own independent timeout. The timeout clock resets
 * on every retry; it does not represent an overall budget for all attempts combined.
 *
 * <p>When both {@code @Timeout} and {@link CircuitBreaker#timeoutMs()} are present on the same
 * element, {@code @Timeout} takes precedence. This annotation is the preferred way to configure
 * timeouts when fine-grained control or a non-millisecond time unit is needed.
 *
 * <p>When applied at the type level, the value acts as the default for all methods on that type. A
 * method-level {@code @Timeout} annotation completely replaces the type-level annotation for that
 * method — there is no merging.
 *
 * <p>There is no default value for {@link #value()} — an explicit timeout must always be provided.
 * This prevents silent misconfiguration where a missing annotation silently disables timeout
 * enforcement.
 *
 * @see CircuitBreaker
 * @see Retry
 */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Timeout {

    /**
     * The timeout duration. The time unit is specified by {@link #unit()}.
     *
     * <p>No default is provided — the value must be explicitly specified to prevent silent
     * misconfiguration.
     *
     * @return the timeout duration; must be positive
     */
    long value();

    /**
     * The time unit for {@link #value()}.
     *
     * @return the time unit; defaults to {@link TimeUnit#MILLISECONDS}
     */
    TimeUnit unit() default TimeUnit.MILLISECONDS;
}
