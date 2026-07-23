// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.prometheus;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micrometer.core.instrument.Clock;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.tracer.otel.OpenTelemetrySpanContext;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Mechanism-level integration test proving AC-9: when a sampled OpenTelemetry span is active,
 * Prometheus exemplars are attached to timer observations, and the exemplar {@code trace_id}
 * appears in the OpenMetrics scrape body.
 *
 * <p>This test uses {@link OpenTelemetryExtension} (installs a global — fine here because the
 * test verifies the Prometheus exemplar mechanism, not the Vert.x tracing pipeline). The
 * {@link OpenTelemetrySpanContext} reads {@link Span#current()} from the global OTel context,
 * so the extension's installed global is exactly what drives the exemplar values.
 *
 * <p>Scenario:
 * <ol>
 *   <li>Build a {@link PrometheusMeterRegistry} with a {@link DeferredSpanContext} and an
 *       {@link OpenTelemetrySpanContext} wired as the delegate (simulating the wiring done by
 *       {@link PrometheusScrapeEndpoint#contribute} when exemplars are enabled).</li>
 *   <li>Start a sampled span, make it current, record a {@link Timer} sample, end the span.</li>
 *   <li>Scrape with the OpenMetrics content type; assert the body contains an exemplar with the
 *       correct {@code trace_id}.</li>
 *   <li>Scrape with the default Prometheus text format; assert the body contains NO exemplar
 *       (Prometheus text format does not render exemplars for histograms/summaries).</li>
 * </ol>
 *
 * <p>Each test cleans up {@link PrometheusBackend} in {@link AfterEach}.
 */
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class PrometheusExemplarIT {

    /**
     * The {@link OpenTelemetryExtension} installs a global OTel instance for the duration of the
     * test class. {@link OpenTelemetrySpanContext} reads the global, so this drives exemplar values.
     */
    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    @AfterEach
    void clearBackend() {
        PrometheusBackend.clear();
    }

    // --- Helpers ---

    /**
     * Creates a {@link PrometheusMeterRegistry} with a {@link DeferredSpanContext} wired to an
     * {@link OpenTelemetrySpanContext} delegate, then publishes it to {@link PrometheusBackend}.
     *
     * <p>This mirrors the wiring done by {@link PrometheusScrapeEndpoint#contribute} when
     * {@code exemplarsEnabled=true} and the {@link io.prometheus.metrics.tracer.common.SpanContext}
     * optional is present.
     *
     * @return the registry, ready for meter registration and scraping
     */
    private static PrometheusMeterRegistry buildRegistryWithExemplars() {
        DeferredSpanContext deferred = new DeferredSpanContext();
        // Wire the OTel span context delegate (reads Span.current() globally)
        deferred.delegate(new OpenTelemetrySpanContext());

        PrometheusMeterRegistry registry =
                new PrometheusMeterRegistry(PrometheusConfig.DEFAULT, new PrometheusRegistry(), Clock.SYSTEM, deferred);

        PrometheusBackend.publish(registry, deferred);
        return registry;
    }

    // --- Test 1: OpenMetrics scrape contains exemplar with matching trace_id ---

    @Test
    @DisplayName("sampled span: OpenMetrics scrape body contains exemplar with trace_id matching the active span")
    void openMetricsScrapeContainsExemplar() {
        PrometheusMeterRegistry registry = buildRegistryWithExemplars();

        Tracer tracer = OTEL.getOpenTelemetry().getTracer("exemplar-test");
        Span span = tracer.spanBuilder("timer-recording-span").startSpan();

        String traceId;
        try (Scope scope = span.makeCurrent()) {
            SpanContext spanCtx = span.getSpanContext();
            traceId = spanCtx.getTraceId();
            assertTrue(spanCtx.isSampled(), "span must be sampled for exemplar to be emitted");

            // Record a timer observation while the span is current
            Timer timer = Timer.builder("it.exemplar.timer")
                    .description("exemplar IT timer")
                    .register(registry);
            timer.record(100, TimeUnit.MILLISECONDS);
        } finally {
            span.end();
        }

        // Scrape in OpenMetrics format (which carries exemplars)
        String openMetricsBody = registry.scrape("application/openmetrics-text; version=1.0.0; charset=utf-8");

        // The scrape body must contain the exemplar syntax: # { ... trace_id="<traceId>" ...}
        assertTrue(
                openMetricsBody.contains("trace_id"),
                "OpenMetrics body must contain 'trace_id' in exemplar block; body:\n" + openMetricsBody);
        assertTrue(
                openMetricsBody.contains(traceId),
                "OpenMetrics body must contain the active span's traceId '" + traceId + "'; body:\n" + openMetricsBody);
        // Exemplar syntax in OpenMetrics: # { ... } value
        assertTrue(
                openMetricsBody.contains("# {"),
                "OpenMetrics body must contain exemplar block '# {'; body:\n" + openMetricsBody);
    }

    // --- Test 2: Prometheus text format scrape contains NO exemplar ---

    @Test
    @DisplayName("sampled span: Prometheus text format scrape body contains NO exemplar")
    void prometheusTextScrapeContainsNoExemplar() {
        PrometheusMeterRegistry registry = buildRegistryWithExemplars();

        Tracer tracer = OTEL.getOpenTelemetry().getTracer("exemplar-test");
        Span span = tracer.spanBuilder("timer-no-exemplar-span").startSpan();

        try (Scope scope = span.makeCurrent()) {
            Timer timer = Timer.builder("it.text.format.timer")
                    .description("text format IT timer")
                    .register(registry);
            timer.record(50, TimeUnit.MILLISECONDS);
        } finally {
            span.end();
        }

        // Scrape in Prometheus text format
        String textBody = registry.scrape("text/plain; version=0.0.4; charset=utf-8");

        // Prometheus text format does NOT carry exemplars
        assertFalse(
                textBody.contains("# {"),
                "Prometheus text format must NOT contain exemplar block '# {'; body:\n" + textBody);
    }

    // --- Test 3: verify the span was recorded by the extension (sanity check) ---

    @Test
    @DisplayName("sanity: OpenTelemetryExtension captures spans when they are ended")
    void otelExtensionCapturesSpans() {
        Tracer tracer = OTEL.getOpenTelemetry().getTracer("sanity-test");
        Span span = tracer.spanBuilder("sanity-span").startSpan();
        span.end();

        List<SpanData> spans = OTEL.getSpans();
        assertTrue(
                spans.stream().anyMatch(s -> "sanity-span".equals(s.getName())),
                "OpenTelemetryExtension must capture the sanity-span; got: " + spans);
    }
}
