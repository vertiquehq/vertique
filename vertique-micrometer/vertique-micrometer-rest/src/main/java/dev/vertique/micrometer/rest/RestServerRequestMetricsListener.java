// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest;

import dev.vertique.micrometer.MetricsConfig;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.events.RestRequestCompletedListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;

/**
 * Records a per-request timer ({@value #METER_NAME}) for every completed HTTP server request.
 *
 * <p>Subscribed to the {@link RestRequestCompletedListener} multibinding. Contributed automatically
 * when {@link MicrometerRestModule} is installed.
 *
 * <p>Meter tags:
 * <ul>
 *   <li>{@code method} — HTTP method (e.g. {@code GET})</li>
 *   <li>{@code route} — OpenAPI path template (e.g. {@code /orders/{id}}), or {@code UNKNOWN} when
 *       the request did not reach operation dispatch</li>
 *   <li>{@code operation} — OpenAPI operationId, or {@code UNKNOWN} when unavailable</li>
 *   <li>{@code status} — HTTP response status code as a string (e.g. {@code 200})</li>
 *   <li>{@code outcome} — low-cardinality {@link HttpOutcome} bucket (e.g. {@code SUCCESS})</li>
 *   <li>{@code error.type} — {@code failureCode} when present, else {@code wireFailureCode}
 *       (a post-handoff wire failure — see {@code RestRequestCompletedEvent}), else {@code none}.
 *       A {@code 200}-status series may therefore carry a non-{@code none} {@code error.type}:
 *       that combination is the truncated-response signature.</li>
 * </ul>
 *
 * <p>When {@code metricsConfig} is present and {@link MetricsConfig#enabled()} returns
 * {@code false}, this listener returns immediately without recording any meter. When the optional
 * is empty (no binding present), recording is active (default-enabled).
 *
 * <p>Throwing meter registries are isolated: any exception thrown during meter recording is caught
 * and swallowed so that a misbehaving registry never affects request processing.
 *
 * @see MicrometerRestModule
 * @see HttpOutcome
 */
@Slf4j
@Singleton
public final class RestServerRequestMetricsListener implements RestRequestCompletedListener {

    /** Micrometer meter name for the per-request server timer. */
    public static final String METER_NAME = "vertique.rest.server.requests";

    /** Tag name for the HTTP method. */
    static final String TAG_METHOD = "method";

    /** Tag name for the OpenAPI route template. */
    static final String TAG_ROUTE = "route";

    /** Tag name for the OpenAPI operationId. */
    static final String TAG_OPERATION = "operation";

    /** Tag name for the HTTP status code. */
    static final String TAG_STATUS = "status";

    /** Tag name for the low-cardinality outcome bucket. */
    static final String TAG_OUTCOME = "outcome";

    /** Tag name for the low-cardinality error type. */
    static final String TAG_ERROR_TYPE = "error.type";

    /** Sentinel value used for route and operation tags when the value is unavailable. */
    static final String UNKNOWN = "UNKNOWN";

    /** Sentinel value used for error.type when no failure was recorded. */
    static final String NONE = "none";

    private final MeterRegistry registry;
    private final boolean enabled;

    /**
     * Creates the listener with the given meter registry and optional metrics configuration.
     *
     * @param registry      the application-wide meter registry; never {@code null}
     * @param metricsConfig an optional {@link MetricsConfig}; when present and
     *                      {@link MetricsConfig#enabled()} is {@code false}, all recording is
     *                      skipped; when empty recording is active (default-enabled)
     */
    @Inject
    public RestServerRequestMetricsListener(MeterRegistry registry, Optional<MetricsConfig> metricsConfig) {
        this.registry = registry;
        this.enabled = metricsConfig.map(MetricsConfig::enabled).orElse(true);
    }

    /**
     * Records the completed request as a timer sample.
     *
     * <p>When {@code metricsEnabled=false}, returns immediately without recording any meter.
     * Any exception thrown during meter recording is caught and swallowed so that a misbehaving
     * registry never affects request processing.
     *
     * @param event the completed-request event; never {@code null}
     */
    @Override
    public void onCompleted(RestRequestCompletedEvent event) {
        if (!enabled) {
            return;
        }
        try {
            String method = event.method() != null ? event.method() : UNKNOWN;
            String route = event.routeTemplate() != null ? event.routeTemplate() : UNKNOWN;
            String operation = event.operationId() != null ? event.operationId() : UNKNOWN;
            String status = String.valueOf(event.statusCode());
            String outcome = HttpOutcome.from(event.statusCode()).name();
            // error.type (D1=A): failureCode wins when present; otherwise fall back to
            // wireFailureCode (a truncated-response signal — see RestRequestCompletedEvent); a
            // 200-status series may therefore carry a non-"none" error.type.
            String errorType = event.failureCode() != null
                    ? event.failureCode()
                    : (event.wireFailureCode() != null ? event.wireFailureCode() : NONE);

            Tags tags = Tags.of(
                    TAG_METHOD, method,
                    TAG_ROUTE, route,
                    TAG_OPERATION, operation,
                    TAG_STATUS, status,
                    TAG_OUTCOME, outcome,
                    TAG_ERROR_TYPE, errorType);

            Duration duration = Duration.between(event.startTime(), event.endTime());
            if (duration.isNegative()) {
                duration = Duration.ZERO;
            }

            Timer.builder(METER_NAME)
                    .description("Per-request HTTP server timer")
                    .tags(tags)
                    .register(registry)
                    .record(duration);
        } catch (Exception e) {
            log.warn("RestServerRequestMetricsListener failed: {}", e.getClass().getName());
        }
    }
}
