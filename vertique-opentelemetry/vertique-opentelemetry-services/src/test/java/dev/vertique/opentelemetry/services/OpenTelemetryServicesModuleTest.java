// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dagger.Component;
import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.services.interceptor.ServiceDispatchContext;
import dev.vertique.services.interceptor.ServiceInterceptor;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import jakarta.inject.Singleton;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Unit tests for {@link OpenTelemetryServicesModule}.
 *
 * <p>Verifies that installing the module alone (no other Vertique module, no tracer or config
 * binding) contributes exactly one {@link ServiceDispatchSpanEnrichmentInterceptor} to the
 * {@code Set<ServiceInterceptor>} multibinding, and that the contributed instance enriches the
 * current span.
 */
@DisplayName("OpenTelemetry services module")
class OpenTelemetryServicesModuleTest {

    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    @Test
    @DisplayName("contributes exactly one ServiceDispatchSpanEnrichmentInterceptor to Set<ServiceInterceptor>")
    void contributesSpanEnrichmentInterceptorToServiceInterceptorSet() {
        Set<ServiceInterceptor> interceptors =
                DaggerOpenTelemetryServicesModuleTest_TestComponent.create().serviceInterceptors();

        assertEquals(1, interceptors.size(), "Set must contain exactly one interceptor");
        assertInstanceOf(
                ServiceDispatchSpanEnrichmentInterceptor.class,
                interceptors.iterator().next(),
                "The interceptor must be a ServiceDispatchSpanEnrichmentInterceptor");
    }

    @Test
    @DisplayName("the contributed interceptor enriches the current span on dispatch")
    void contributedInterceptorEnrichesCurrentSpan() {
        ServiceInterceptor interceptor = DaggerOpenTelemetryServicesModuleTest_TestComponent.create()
                .serviceInterceptors()
                .iterator()
                .next();
        Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
        Span span = tracer.spanBuilder("test-span").startSpan();

        try (var ignored = span.makeCurrent()) {
            interceptor.onDispatch(new ServiceDispatchContext(
                    "services/test/svc/op",
                    "integration.svc.op",
                    "test",
                    "svc",
                    "op",
                    DispatchEnvelope.empty(),
                    false,
                    List.of(),
                    List.of(),
                    Map.of()));
        } finally {
            span.end();
        }

        List<SpanData> spans = OTEL.getSpans();
        assertEquals(1, spans.size(), "one span must be exported");
        assertEquals(
                "integration.svc.op",
                spans.get(0).getAttributes().get(ServiceAttributes.SERVICE_TARGET),
                "vertique.service.target must be set by the Dagger-contributed interceptor");
    }

    /** Wires {@link OpenTelemetryServicesModule} alone — it needs no other binding. */
    @Singleton
    @Component(modules = OpenTelemetryServicesModule.class)
    interface TestComponent {

        Set<ServiceInterceptor> serviceInterceptors();
    }
}
