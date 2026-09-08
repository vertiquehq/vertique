// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import dev.vertique.aop.Aspect;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Built-in {@link Aspect} that records a Micrometer {@link io.micrometer.core.instrument.Timer Timer}
 * around an annotated method, observing its outcome without altering it.
 *
 * <p>Applied to any bean method, the compile-time AOP processor generates a proxy that wraps the
 * call in the {@link TimedAspect} interceptor. The interceptor captures a timing sample on both
 * normal and failing completion (synchronous return or {@link io.vertx.core.Future Future} settle)
 * and records it with two always-present low-cardinality tags:
 *
 * <ul>
 *   <li>{@code outcome} — {@code SUCCESS} on a normal return or successful {@code Future},
 *       {@code ERROR} on a thrown exception or failed {@code Future};</li>
 *   <li>{@code error.type} — the simple class name of the failure cause, or {@code none} on
 *       success.</li>
 * </ul>
 *
 * <p>Any {@link #extraTags()} pairs are added on top of those. The recorded duration is a
 * {@link System#nanoTime()} delta, so it is never negative — the clock is monotonic, and nothing
 * clamps it. When {@link MetricsConfig#enabled()} is {@code false} the interceptor is a no-op
 * (no timer is recorded), and the original return value or exception always passes through
 * unchanged — the aspect observes but never modifies the call's outcome.
 *
 * @see TimedAspect
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Aspect(ordering = 1000)
public @interface Timed {

    /**
     * Returns the metric (timer) name; an empty value auto-derives
     * {@code vertique.<simpleClassName>.<method>} (lower-dotted) per §3b.
     *
     * @return the metric name, or {@code ""} to auto-derive
     */
    String value() default "";

    /**
     * Returns the even-length {@code key,value} pairs added as extra timer tags.
     *
     * @return the extra-tag pairs; empty by default
     */
    String[] extraTags() default {};
}
