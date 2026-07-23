// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.prometheus;

import dagger.Module;
import dagger.Provides;
import io.prometheus.metrics.tracer.common.SpanContext;
import io.prometheus.metrics.tracer.otel.OpenTelemetrySpanContext;
import jakarta.inject.Singleton;

/**
 * Dagger module providing the Prometheus exemplar {@link SpanContext} backed by OpenTelemetry.
 *
 * <p>Install this module alongside {@code vertique-opentelemetry-core} and
 * {@code vertique-micrometer-registry-prometheus} to wire the exemplar bridge. The module
 * satisfies the {@code @BindsOptionalOf SpanContext} seam declared by
 * {@link dev.vertique.micrometer.prometheus.MicrometerPrometheusModule}, enabling Prometheus
 * exemplars to carry the current OpenTelemetry trace id.
 *
 * <pre>{@code
 * @Component(modules = {
 *     VertxModule.class,
 *     OpenTelemetryModule.class,
 *     MicrometerModule.class,
 *     MicrometerPrometheusModule.class,
 *     OpenTelemetryPrometheusExemplarModule.class,
 *     ...
 * })
 * interface AppComponent { ... }
 * }</pre>
 *
 * <h2>Exemplar behaviour</h2>
 * <p>The provided {@link OpenTelemetrySpanContext} reads
 * {@link io.opentelemetry.api.trace.Span#current()} lazily at exemplar-sample time. When no span
 * is current the trace and span ids are {@code null} and the exemplar machinery silently emits no
 * trace information. When a sampled span is current its trace id is attached to the metric
 * <em>sample</em> as an OpenMetrics exemplar — the {@code # {trace_id="..."} <value> <timestamp>}
 * suffix appended after the sample line (not a {@code # HELP} comment) — rendered only in
 * OpenMetrics format.
 *
 * <h2>Layering</h2>
 * <p>This module has NO dependency on {@code vertique-opentelemetry-core} or
 * {@code vertique-micrometer-registry-prometheus} — it is a leaf bridge that solely connects
 * the Prometheus tracer SPI to the OpenTelemetry API. Neither the tracing core nor the
 * Prometheus backend need know about the other.
 *
 * @see OpenTelemetrySpanContext
 * @see dev.vertique.micrometer.prometheus.MicrometerPrometheusModule
 */
@Module
public abstract class OpenTelemetryPrometheusExemplarModule {

    // --- Prometheus exemplar SpanContext ---

    /**
     * Provides the Prometheus {@link SpanContext} singleton backed by OpenTelemetry.
     *
     * <p>{@link OpenTelemetrySpanContext} delegates to
     * {@link io.opentelemetry.api.trace.Span#current()} when Prometheus samples an exemplar — it
     * requires no constructor arguments and reads the active span lazily. This is a guaranteed
     * no-op (returns {@code null} trace and span ids) when no span is current.
     *
     * <p>This binding satisfies the {@code @BindsOptionalOf SpanContext} declared by
     * {@link dev.vertique.micrometer.prometheus.MicrometerPrometheusModule}.
     *
     * @return the span context bridge; never null
     */
    @Provides
    @Singleton
    static SpanContext exemplarSpanContext() {
        return new OpenTelemetrySpanContext();
    }

    private OpenTelemetryPrometheusExemplarModule() {}
}
