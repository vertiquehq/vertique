// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer;

import dev.vertique.aop.AspectProvider;
import dev.vertique.aop.MethodInterceptor;
import dev.vertique.core.codegen.MethodMetadata;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link AspectProvider} for the {@link Timed @Timed} built-in: builds an around-interceptor that
 * records a Micrometer {@link Timer} for each invocation and passes the outcome through unchanged.
 *
 * <p>The interceptor captures a monotonic {@link System#nanoTime() nanoTime} start before
 * {@link dev.vertique.aop.Invocation#proceed() proceed()}, then records a timing sample when the
 * downstream future settles. The timer name is the
 * annotation's {@link Timed#value()}, or — when empty — the auto-derived
 * {@code vertique.<simpleClassName>.<method>} (lower-cased) per §3b. Every sample carries the
 * always-present {@code outcome} ({@code SUCCESS}/{@code ERROR}) and {@code error.type} (exception
 * simple name, or {@code none}) tags, plus any {@link Timed#extraTags()} pairs.
 *
 * <p>Recording is fire-and-forget and isolated: the duration is a {@link System#nanoTime()} delta
 * and so is never negative — the clock is monotonic, and nothing clamps it. Any
 * {@link Throwable} thrown by the registry (including {@link Error} subclasses) is caught and
 * logged at warn, and the original result or exception is returned to the caller untouched. When {@link MetricsConfig#enabled()} is {@code false} (or a {@code MetricsConfig} is
 * present and disabled) the interceptor records nothing while still passing the call through.
 *
 * @see Timed
 * @see MicrometerModule
 */
@Slf4j
@Singleton
public final class TimedAspect implements AspectProvider<Timed> {

    /** Tag name for the always-present low-cardinality outcome. */
    static final String TAG_OUTCOME = "outcome";

    /** Tag name for the always-present low-cardinality error type. */
    static final String TAG_ERROR_TYPE = "error.type";

    /** Outcome tag value for a successful completion. */
    static final String OUTCOME_SUCCESS = "SUCCESS";

    /** Outcome tag value for a failed completion. */
    static final String OUTCOME_ERROR = "ERROR";

    /** Sentinel used for {@code error.type} on a successful completion. */
    static final String NONE = "none";

    /** Prefix applied to the auto-derived timer name when {@link Timed#value()} is empty. */
    static final String AUTO_NAME_PREFIX = "vertique.";

    private final MeterRegistry registry;
    private final boolean enabled;

    /**
     * Creates the provider with the application-wide meter registry and optional metrics config.
     *
     * @param registry the application-wide meter registry; never {@code null}
     * @param metricsConfig an optional {@link MetricsConfig}; when present and
     *     {@link MetricsConfig#enabled()} is {@code false}, recording is skipped; when empty,
     *     recording is active (default-enabled)
     */
    @Inject
    public TimedAspect(MeterRegistry registry, Optional<MetricsConfig> metricsConfig) {
        this.registry = registry;
        this.enabled = metricsConfig.map(MetricsConfig::enabled).orElse(true);
    }

    /**
     * Builds the timing interceptor for one {@code @Timed} method.
     *
     * <p>This factory runs once per intercepted method (at proxy construction), so all
     * call-invariant work is hoisted here: the timer {@code name} and the pre-parsed
     * {@link Timed#extraTags()} as {@link Tags}. The returned interceptor captures a monotonic
     * start, runs the downstream chain, and on settle records a {@link Timer} sample tagged with
     * the call's outcome, propagating the original outcome (value or failure) unchanged.
     *
     * <p>A misconfigured {@link Timed#extraTags()} with an odd number of elements fails fast here, at
     * proxy construction, with an {@link IllegalArgumentException} naming the metric — rather than
     * silently dropping every timer sample later at record time (Micrometer's {@code Tags.of} rejects
     * an odd-length varargs).
     *
     * @param target the reflection-free metadata of the intercepted method
     * @param annotation the {@code @Timed} instance present on the method
     * @return the timing interceptor
     * @throws IllegalArgumentException if {@link Timed#extraTags()} has an odd number of elements
     */
    @Override
    public MethodInterceptor interceptor(MethodMetadata target, Timed annotation) {
        String name = resolveName(target, annotation);
        String[] extraTagPairs = annotation.extraTags();
        if (extraTagPairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "@Timed.extraTags() must have an even number of elements (key, value, ...): " + name);
        }
        Tags extraTags = Tags.of(extraTagPairs);
        return invocation -> {
            long startNanos = System.nanoTime();
            return invocation.proceed().andThen(ar -> record(name, extraTags, startNanos, ar.cause()));
        };
    }

    /**
     * Resolves the timer name: the annotation's explicit {@link Timed#value()} when non-empty,
     * otherwise the auto-derived {@code vertique.<simpleClassName>.<method>} (lower-cased) per §3b.
     *
     * @param target the metadata of the intercepted method
     * @param annotation the {@code @Timed} instance
     * @return the resolved timer name
     */
    private static String resolveName(MethodMetadata target, Timed annotation) {
        String explicit = annotation.value();
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        String simpleName = target.declaringType().getSimpleName();
        return (AUTO_NAME_PREFIX + simpleName + "." + target.name()).toLowerCase(Locale.ROOT);
    }

    /**
     * Records a single timing sample for a settled invocation. The {@code cause} distinguishes
     * success ({@code null}) from failure (the failure cause). Recording is skipped when metrics are
     * disabled, the duration is derived from a monotonic {@link System#nanoTime()} delta (never
     * negative), and any {@link Throwable} thrown by the registry (including {@link Error} subclasses)
     * is caught and logged so the call's own outcome is never affected.
     *
     * @param name the resolved timer name
     * @param extraTags the pre-parsed {@link Timed#extraTags()} key-value pairs
     * @param startNanos the {@link System#nanoTime()} value captured before the downstream call
     * @param cause the failure cause, or {@code null} on success
     */
    private void record(String name, Tags extraTags, long startNanos, Throwable cause) {
        if (!enabled) {
            return;
        }
        try {
            String outcome = cause == null ? OUTCOME_SUCCESS : OUTCOME_ERROR;
            String errorType = cause == null ? NONE : cause.getClass().getSimpleName();

            Tags tags = Tags.of(TAG_OUTCOME, outcome, TAG_ERROR_TYPE, errorType).and(extraTags);

            Duration duration = Duration.ofNanos(System.nanoTime() - startNanos);

            Timer.builder(name).tags(tags).register(registry).record(duration);
        } catch (Throwable t) {
            log.warn(
                    "TimedAspect failed to record timer '{}': {}",
                    name,
                    t.getClass().getName());
        }
    }
}
