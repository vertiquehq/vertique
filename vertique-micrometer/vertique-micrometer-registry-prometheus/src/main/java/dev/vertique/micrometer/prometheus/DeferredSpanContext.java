// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.prometheus;

import io.prometheus.metrics.tracer.common.SpanContext;

/**
 * A {@link SpanContext} implementation that delegates to another {@link SpanContext} once one is
 * wired in after construction.
 *
 * <p>Before the delegate is attached, all methods return null / false / no-op values so that the
 * {@link io.micrometer.prometheusmetrics.PrometheusMeterRegistry} can be created immediately
 * without a real span context. Once a tracing integration is available and exemplars should be
 * enabled, call {@link #delegate(SpanContext)} to wire in the real implementation.
 *
 * <p>This class is package-private; external code interacts with it only through the
 * {@link PrometheusBackend} accessor.
 */
final class DeferredSpanContext implements SpanContext {

    /** The real span context to delegate to; {@code null} until {@link #delegate(SpanContext)} is called. */
    private volatile SpanContext delegate;

    // --- Configuration ---

    /**
     * Wires in the real {@link SpanContext} delegate.
     *
     * <p>After this call, all methods of this instance forward to {@code d}. This method is
     * idempotent in the sense that later calls overwrite the earlier delegate; however, in normal
     * use it is called at most once.
     *
     * @param d the real span context to delegate to; must not be {@code null}
     */
    void delegate(SpanContext d) {
        this.delegate = d;
    }

    // --- SpanContext ---

    /**
     * Returns the current trace ID from the delegate, or {@code null} if no delegate is set.
     *
     * @return the current trace ID, or {@code null} outside a span or when no delegate is wired
     */
    @Override
    public String getCurrentTraceId() {
        SpanContext d = delegate;
        return d != null ? d.getCurrentTraceId() : null;
    }

    /**
     * Returns the current span ID from the delegate, or {@code null} if no delegate is set.
     *
     * @return the current span ID, or {@code null} outside a span or when no delegate is wired
     */
    @Override
    public String getCurrentSpanId() {
        SpanContext d = delegate;
        return d != null ? d.getCurrentSpanId() : null;
    }

    /**
     * Returns whether the current span is sampled according to the delegate, or {@code false} if no
     * delegate is set.
     *
     * @return {@code true} if the current span is sampled; {@code false} otherwise or when no
     *     delegate is wired
     */
    @Override
    public boolean isCurrentSpanSampled() {
        SpanContext d = delegate;
        return d != null && d.isCurrentSpanSampled();
    }

    /**
     * Marks the current span as an exemplar via the delegate, or does nothing if no delegate is set.
     */
    @Override
    public void markCurrentSpanAsExemplar() {
        SpanContext d = delegate;
        if (d != null) {
            d.markCurrentSpanAsExemplar();
        }
    }
}
