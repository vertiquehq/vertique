// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.resilience.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.concurrent.TimeUnit;

/** Configures a per-attempt timeout for the annotated type or method. */
@Documented
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Timeout {

    /**
     * Returns the timeout duration.
     *
     * @return the timeout duration; must be positive
     */
    long value();

    /**
     * Returns the time unit for {@link #value()}.
     *
     * @return the time unit
     */
    TimeUnit unit() default TimeUnit.MILLISECONDS;
}
