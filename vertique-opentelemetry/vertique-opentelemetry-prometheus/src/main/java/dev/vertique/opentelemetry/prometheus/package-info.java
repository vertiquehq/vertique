// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

/**
 * OpenTelemetry to Prometheus exemplar bridge.
 *
 * <p>Provides the Prometheus exemplar {@link io.prometheus.metrics.tracer.common.SpanContext}
 * backed by {@link io.opentelemetry.api.trace.Span#current()}, decoupling the Prometheus
 * registry module from OpenTelemetry and keeping {@code vertique-opentelemetry-core} free of
 * any metrics-backend dependency.
 *
 * <p>Install alongside {@code vertique-opentelemetry-core} and
 * {@code vertique-micrometer-registry-prometheus} and set
 * {@code metrics.prometheus.exemplars.enabled=true}. The bridge satisfies the
 * {@code @BindsOptionalOf SpanContext} seam declared by
 * {@link dev.vertique.micrometer.prometheus.MicrometerPrometheusModule}.
 *
 * <p>Key class:
 * <ul>
 *   <li>{@link dev.vertique.opentelemetry.prometheus.OpenTelemetryPrometheusExemplarModule} —
 *       Dagger module providing the {@link io.prometheus.metrics.tracer.common.SpanContext}
 *       singleton backed by {@link io.prometheus.metrics.tracer.otel.OpenTelemetrySpanContext}</li>
 * </ul>
 */
package dev.vertique.opentelemetry.prometheus;
