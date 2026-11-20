// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.cache;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.cache.spi.event.CacheOperation;
import dev.vertique.cache.spi.event.CacheOperationCompleted;
import dev.vertique.cache.spi.event.CacheOutcome;
import dev.vertique.opentelemetry.TracingConfig;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class CacheObserverTest {

    @Test
    void emitsBoundedAttributesWithoutSensitiveValues() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk telemetry =
                OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        try {
            Tracer tracer = telemetry.getTracer("test");
            CacheTracingObserver observer =
                    new CacheTracingObserver(tracer, TracingConfig.builder().build());

            observer.onEvent(new CacheOperationCompleted(
                    CacheOperation.GET, "redis", "profiles", CacheOutcome.MISS, Duration.ofMillis(1)));

            List<io.opentelemetry.sdk.trace.data.SpanData> spans = exporter.getFinishedSpanItems();
            assertEquals(1, spans.size());
            var span = spans.getFirst();
            assertNotNull(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("provider")));
            assertEquals(
                    "redis", span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("provider")));
            assertFalse(span.getAttributes().toString().contains("raw-selector"));
            assertFalse(span.getAttributes().toString().contains("payload"));
            assertFalse(span.getAttributes().toString().contains("principal"));
        } finally {
            telemetry.close();
        }
    }

    @Test
    void spanCoversTheElapsedTimeOfTheCacheOperation() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        OpenTelemetrySdk telemetry =
                OpenTelemetrySdk.builder().setTracerProvider(provider).build();
        try {
            CacheTracingObserver observer = new CacheTracingObserver(
                    telemetry.getTracer("test"), TracingConfig.builder().build());
            Duration elapsed = Duration.ofMillis(500);

            observer.onEvent(
                    new CacheOperationCompleted(CacheOperation.GET, "redis", "profiles", CacheOutcome.HIT, elapsed));

            var span = exporter.getFinishedSpanItems().getFirst();
            long spanNanos = span.getEndEpochNanos() - span.getStartEpochNanos();
            assertTrue(
                    spanNanos >= elapsed.toNanos(),
                    () -> "span width " + spanNanos + " ns is shorter than the cache operation's " + elapsed.toNanos()
                            + " ns");
        } finally {
            telemetry.close();
        }
    }

    @Test
    void tracerFailureDoesNotEscapeTheObserver() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.spanBuilder("cache.get")).thenThrow(new AssertionError("tracer failure"));
        CacheTracingObserver observer =
                new CacheTracingObserver(tracer, TracingConfig.builder().build());

        assertDoesNotThrow(() -> observer.onEvent(new CacheOperationCompleted(
                CacheOperation.GET, "redis", "profiles", CacheOutcome.ERROR, Duration.ofMillis(1))));
    }
}
