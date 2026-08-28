// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.annotation;

import dev.vertique.resilience.BackoffStrategy;
import dev.vertique.resilience.RetryPolicy;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Configures retry behaviour for the annotated type or method. */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Retry {

    /** @return the maximum number of retry attempts after the initial failure */
    int maxRetries() default 3;

    /** @return the initial delay in milliseconds before the first retry */
    long delayMs() default 500;

    /** @return the exponential growth factor for the retry delay */
    double backoffMultiplier() default 2.0;

    /** @return the maximum delay in milliseconds before jitter is added */
    long maxDelayMs() default 30_000;

    /**
     * Returns the custom backoff class, or the sentinel for inline parameters.
     * Custom strategies are instantiated reflectively by consumers and must expose
     * a public no-argument constructor; consumers report construction failures as
     * an {@link IllegalStateException}.
     *
     * @return the custom backoff class
     */
    Class<? extends BackoffStrategy> backoff() default BackoffStrategy.Default.class;

    /**
     * Returns exception types eligible for retry. An empty value delegates to {@link RetryPolicy}.
     *
     * @return exception types eligible for retry
     */
    Class<? extends Throwable>[] retryOn() default {};

    /**
     * Returns exception types that abort retrying before other filters are evaluated.
     *
     * @return exception types that prevent retry
     */
    Class<? extends Throwable>[] abortOn() default {};
}
