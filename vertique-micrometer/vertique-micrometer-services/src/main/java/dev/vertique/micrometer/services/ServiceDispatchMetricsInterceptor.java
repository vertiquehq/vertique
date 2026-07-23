// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.services;

import dev.vertique.core.eventbus.Result;
import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.services.interceptor.ServiceDispatchContext;
import dev.vertique.services.interceptor.ServiceInterceptor;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Records a per-dispatch timer ({@value #METER_NAME}) for every terminal service outcome.
 *
 * <p>Contributed to the {@link ServiceInterceptor} multibinding by {@link MicrometerServicesModule}.
 * Implements {@link ServiceInterceptor#onTerminalComplete} — the post-recovery terminal outcome
 * hook — which means recovered failures are observed as {@code SUCCESS} by contract (the recovery
 * decision is already reflected in the {@code result} argument). Unlike
 * {@link ServiceInterceptor#onComplete} (which fires before recovery), this hook always sees the
 * final outcome that the caller receives.
 *
 * <p>Meter tags:
 * <ul>
 *   <li>{@code target} — the stable dot-delimited target id (e.g.
 *       {@code integration.user-service.get-user}), or {@value #UNKNOWN} when
 *       {@link ServiceDispatchContext#stableTargetId()} is {@code null}</li>
 *   <li>{@code outcome} — {@code SUCCESS} for {@link Result#isSuccess()},
 *       {@code ERROR} for {@link Result#isFailure()}</li>
 *   <li>{@code oneway} — {@code true} for fire-and-forget dispatches, {@code false} otherwise</li>
 *   <li>{@code error.type} — simple class name of the terminal failure cause, or
 *       {@value #NONE} on success</li>
 * </ul>
 *
 * <p>When {@code metricsConfig} is present and {@link MetricsConfig#enabled()} returns
 * {@code false}, this interceptor does nothing. When the optional is empty (no binding present),
 * recording is active (default-enabled).
 *
 * <p>Throwing meter registries are isolated: any exception thrown during meter recording is
 * caught and swallowed so that a misbehaving registry never affects dispatch processing.
 *
 * <p>Negative durations (clock skew, test doubles) are clamped to zero before recording.
 *
 * @see MicrometerServicesModule
 */
@Slf4j
@Singleton
public final class ServiceDispatchMetricsInterceptor implements ServiceInterceptor {

    /** Micrometer meter name for the per-dispatch service timer. */
    public static final String METER_NAME = "vertique.service.dispatch";

    /** Tag name for the stable target id. */
    static final String TAG_TARGET = "target";

    /** Tag name for the low-cardinality outcome. */
    static final String TAG_OUTCOME = "outcome";

    /** Tag name for the fire-and-forget flag. */
    static final String TAG_ONEWAY = "oneway";

    /** Tag name for the low-cardinality error type. */
    static final String TAG_ERROR_TYPE = "error.type";

    /** Sentinel used for {@code target} when {@link ServiceDispatchContext#stableTargetId()} is {@code null}. */
    static final String UNKNOWN = "UNKNOWN";

    /** Sentinel used for {@code error.type} when the outcome is a success. */
    static final String NONE = "none";

    /** Outcome tag value for a successful terminal result. */
    static final String OUTCOME_SUCCESS = "SUCCESS";

    /** Outcome tag value for a failed terminal result. */
    static final String OUTCOME_ERROR = "ERROR";

    private final MeterRegistry registry;
    private final boolean enabled;

    /**
     * Creates the interceptor with the given meter registry and optional metrics configuration.
     *
     * @param registry      the application-wide meter registry; never {@code null}
     * @param metricsConfig an optional {@link MetricsConfig}; when present and
     *                      {@link MetricsConfig#enabled()} is {@code false}, all recording is
     *                      skipped; when empty recording is active (default-enabled)
     */
    @Inject
    public ServiceDispatchMetricsInterceptor(MeterRegistry registry, Optional<MetricsConfig> metricsConfig) {
        this.registry = registry;
        this.enabled = metricsConfig.map(MetricsConfig::enabled).orElse(true);
    }

    /**
     * Records the terminal dispatch outcome as a timer sample.
     *
     * <p>When {@code metricsEnabled=false}, returns immediately without recording any meter.
     * Any exception thrown during meter recording is caught and swallowed so that a misbehaving
     * registry never affects dispatch processing.
     *
     * <p>Negative durations are clamped to zero before recording.
     *
     * @param ctx       the dispatch context; never {@code null}
     * @param result    the terminal dispatch result (post-recovery); never {@code null}
     * @param startTime the wall-clock instant at which dispatch was initiated; never {@code null}
     * @param endTime   the wall-clock instant at which the terminal outcome was reached; never {@code null}
     */
    @Override
    public void onTerminalComplete(ServiceDispatchContext ctx, Result<?> result, Instant startTime, Instant endTime) {
        if (!enabled) {
            return;
        }
        try {
            String target = ctx.stableTargetId() != null ? ctx.stableTargetId() : UNKNOWN;
            String outcome = result.isSuccess() ? OUTCOME_SUCCESS : OUTCOME_ERROR;
            String oneway = String.valueOf(ctx.oneWay());
            Throwable cause = result.isFailure() ? result.cause() : null;
            String errorType = cause != null ? cause.getClass().getSimpleName() : NONE;

            Tags tags = Tags.of(
                    TAG_TARGET, target,
                    TAG_OUTCOME, outcome,
                    TAG_ONEWAY, oneway,
                    TAG_ERROR_TYPE, errorType);

            Duration duration = Duration.between(startTime, endTime);
            if (duration.isNegative()) {
                duration = Duration.ZERO;
            }

            Timer.builder(METER_NAME)
                    .description("Per-dispatch service timer")
                    .tags(tags)
                    .register(registry)
                    .record(duration);
        } catch (Exception e) {
            log.warn(
                    "ServiceDispatchMetricsInterceptor failed: {}", e.getClass().getName());
        }
    }
}
