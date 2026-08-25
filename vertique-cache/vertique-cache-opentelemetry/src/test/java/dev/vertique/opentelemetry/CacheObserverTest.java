// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.cache.spi.CacheObservation;
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

            observer.onOperation(new CacheObservation("get", "redis", "profiles", "miss", Duration.ofMillis(1)));

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
    void tracerFailureDoesNotEscapeTheObserver() {
        Tracer tracer = mock(Tracer.class);
        when(tracer.spanBuilder("cache.get")).thenThrow(new AssertionError("tracer failure"));
        CacheTracingObserver observer =
                new CacheTracingObserver(tracer, TracingConfig.builder().build());

        assertDoesNotThrow(() -> observer.onOperation(
                new CacheObservation("get", "redis", "profiles", "failure", Duration.ofMillis(1))));
    }
}
