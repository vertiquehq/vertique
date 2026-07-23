// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.prometheus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dagger.Component;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.prometheus.metrics.tracer.common.SpanContext;
import io.prometheus.metrics.tracer.otel.OpenTelemetrySpanContext;
import jakarta.inject.Singleton;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for {@link OpenTelemetryPrometheusExemplarModule}.
 *
 * <p>Verifies two things:
 * <ol>
 *   <li><b>Dagger graph</b>: the module wires cleanly; the provided {@link SpanContext} is
 *       non-null and an {@link OpenTelemetrySpanContext}.</li>
 *   <li><b>Behaviour</b>: the bridge reads {@link Span#current()} at exemplar-sample time — it
 *       returns the active span's trace id when a sampled span is current, and {@code null} trace
 *       id when no span is active.</li>
 * </ol>
 */
class OpenTelemetryPrometheusExemplarModuleTest {

    // =========================================================================
    // Test 1 — Dagger graph: binding resolves to OpenTelemetrySpanContext
    // =========================================================================

    @Nested
    @DisplayName("Dagger graph: SpanContext binding is non-null and OpenTelemetrySpanContext")
    class DaggerGraphBinding {

        @Test
        @DisplayName("SpanContext is non-null and instanceof OpenTelemetrySpanContext")
        void spanContextBindingResolvesCorrectly() {
            TestComponent component = DaggerOpenTelemetryPrometheusExemplarModuleTest_TestComponent.create();

            SpanContext spanContext = component.spanContext();

            assertNotNull(spanContext, "SpanContext binding must not be null");
            assertInstanceOf(
                    OpenTelemetrySpanContext.class, spanContext, "SpanContext must be an OpenTelemetrySpanContext");
        }

        @Test
        @DisplayName("SpanContext binding is singleton (same reference on every call)")
        void spanContextBindingIsSingleton() {
            TestComponent component = DaggerOpenTelemetryPrometheusExemplarModuleTest_TestComponent.create();

            SpanContext first = component.spanContext();
            SpanContext second = component.spanContext();

            assertSame(first, second, "SpanContext binding must be a singleton");
        }
    }

    // =========================================================================
    // Test 2 — Behaviour: bridge reads Span.current()
    // =========================================================================

    @Nested
    @DisplayName("Behaviour: bridge reads Span.current() at exemplar-sample time")
    class BridgeReadsSpanCurrent {

        @RegisterExtension
        OpenTelemetryExtension otelExtension = OpenTelemetryExtension.create();

        @Test
        @DisplayName("getCurrentTraceId() returns null when no span is current")
        void noCurrentSpanYieldsNullTraceId() {
            SpanContext bridge = new OpenTelemetrySpanContext();

            String traceId = bridge.getCurrentTraceId();

            assertNull(traceId, "getCurrentTraceId() must return null when no span is active");
        }

        @Test
        @DisplayName("getCurrentTraceId() returns the active span's trace id when a sampled span is current")
        void currentSampledSpanYieldsTraceId() {
            Tracer tracer = otelExtension.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();
            String expectedTraceId = span.getSpanContext().getTraceId();
            SpanContext bridge = new OpenTelemetrySpanContext();

            try (Scope ignored = span.makeCurrent()) {
                String actualTraceId = bridge.getCurrentTraceId();
                assertEquals(
                        expectedTraceId, actualTraceId, "getCurrentTraceId() must match the active span's trace id");
            } finally {
                span.end();
            }
        }

        @Test
        @DisplayName("getCurrentTraceId() returns null again after the span scope is closed")
        void traceIdIsNullAfterScopeClose() {
            Tracer tracer = otelExtension.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test-span").startSpan();
            SpanContext bridge = new OpenTelemetrySpanContext();

            try (Scope ignored = span.makeCurrent()) {
                // inside scope — trace id is present
                assertNotNull(bridge.getCurrentTraceId(), "trace id must be present inside scope");
            } finally {
                span.end();
            }

            // after scope is closed — bridge should yield null again
            assertNull(bridge.getCurrentTraceId(), "getCurrentTraceId() must return null after scope closes");
        }
    }

    // =========================================================================
    // --- Test Dagger component ---
    // =========================================================================

    /**
     * Minimal Dagger component that exercises {@link OpenTelemetryPrometheusExemplarModule} wiring.
     *
     * <p>No other modules are needed: the bridge module is a self-contained leaf with no
     * dependency on {@code vertique-opentelemetry-core} or
     * {@code vertique-micrometer-registry-prometheus}.
     */
    @Singleton
    @Component(modules = {OpenTelemetryPrometheusExemplarModule.class})
    interface TestComponent {

        /**
         * Returns the {@link SpanContext} binding provided by
         * {@link OpenTelemetryPrometheusExemplarModule}.
         *
         * @return the span context bridge; non-null when graph resolves
         */
        SpanContext spanContext();
    }
}
