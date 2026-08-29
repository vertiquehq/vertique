// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Configures a circuit breaker for the annotated type or method. */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface CircuitBreaker {

    /** @return the failure threshold before the circuit opens */
    int maxFailures() default 5;

    /** @return the per-attempt timeout in milliseconds, or {@code -1} when not configured */
    long timeoutMs() default -1;

    /** @return the reset timeout in milliseconds before a half-open probe */
    long resetTimeoutMs() default 10_000;
}
