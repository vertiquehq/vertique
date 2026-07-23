// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.rest;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.RouteRegistration;
import dev.vertique.rest.core.security.SecurityPolicy;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.testing.junit5.OpenTelemetryExtension;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.HttpAttributes;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link ServerSpanEnrichmentContributor}.
 *
 * <p>Verifies:
 * <ol>
 *   <li>{@link ServerSpanEnrichmentContributor#contribute} registers exactly one handler on the route.</li>
 *   <li>The handler with a recording span current: renames the span, records
 *       {@code http.route} and {@code vertique.operation.id} attributes, stores the span
 *       under {@link RestSpanKeys#SPAN_KEY}, and calls {@code rc.next()} exactly once.</li>
 *   <li>The handler with no current recording span: no span stored, no exception,
 *       {@code rc.next()} still called exactly once.</li>
 *   <li>The handler when span ops throw: {@code rc.next()} STILL called exactly once —
 *       enrichment failure never breaks the pipeline.</li>
 *   <li>{@link ServerSpanEnrichmentContributor#priority()} returns 360.</li>
 * </ol>
 */
class ServerSpanEnrichmentContributorTest {

    @RegisterExtension
    static final OpenTelemetryExtension OTEL = OpenTelemetryExtension.create();

    private final ServerSpanEnrichmentContributor contributor = new ServerSpanEnrichmentContributor();

    // --- Test 5: priority ---

    @Test
    @DisplayName("priority() returns 360 — one step after OperationIdCaptureContributor at 350")
    void priorityIs360() {
        assertEquals(360, contributor.priority());
    }

    // --- Test 1: contribute() registers exactly one handler ---

    @Test
    @DisplayName("contribute() calls route.addHandler() exactly once")
    @SuppressWarnings("unchecked")
    void contributeRegistersExactlyOneHandler() {
        RouteRegistration route = mockRoute();
        OperationRegistrationContext ctx = buildContext("orders.get", "/orders/{id}", route);

        contributor.contribute(ctx);

        verify(route).addHandler(any());
    }

    // --- Test 2: recording span — enrichment and next() ---

    @Nested
    @DisplayName("recording span — enrichment and rc.next()")
    class RecordingSpan {

        @Test
        @DisplayName("handler renames span to 'METHOD /route/template' and sets attributes on a recording span")
        @SuppressWarnings("unchecked")
        void handlerEnrichesRecordingSpan() {
            String operationId = "orders.get";
            String routeTemplate = "/orders/{id}";
            String httpMethod = "GET";

            RouteRegistration route = mockRoute();
            OperationRegistrationContext ctx = buildContext(operationId, routeTemplate, route);

            contributor.contribute(ctx);

            ArgumentCaptor<Handler<RoutingContext>> handlerCaptor = forClass(Handler.class);
            verify(route).addHandler(handlerCaptor.capture());

            // Start a recording span and make it current
            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("initial-name").startSpan();

            Map<String, Object> store = new HashMap<>();
            RoutingContext rc = mockRoutingContextWithSpan(store, span, httpMethod);

            try (var ignored = span.makeCurrent()) {
                handlerCaptor.getValue().handle(rc);
            } finally {
                span.end();
            }

            // Verify span was renamed and attributes set
            List<SpanData> spans = OTEL.getSpans();
            assertEquals(1, spans.size(), "one span must be exported");
            SpanData spanData = spans.get(0);

            assertEquals("GET /orders/{id}", spanData.getName(), "span must be renamed to 'METHOD /route/template'");
            assertEquals(
                    routeTemplate,
                    spanData.getAttributes().get(HttpAttributes.HTTP_ROUTE),
                    "http.route attribute must be set to the route template");
            assertEquals(
                    operationId,
                    spanData.getAttributes().get(RestSpanKeys.VERTIQUE_OPERATION_ID),
                    "vertique.operation.id attribute must be set to the operationId");
        }

        @Test
        @DisplayName("handler stores span in routing-context data under RestSpanKeys.SPAN_KEY")
        @SuppressWarnings("unchecked")
        void handlerStoresSpanOnRoutingContext() {
            String operationId = "orders.list";
            String routeTemplate = "/orders";
            String httpMethod = "GET";

            RouteRegistration route = mockRoute();
            OperationRegistrationContext ctx = buildContext(operationId, routeTemplate, route);

            contributor.contribute(ctx);

            ArgumentCaptor<Handler<RoutingContext>> handlerCaptor = forClass(Handler.class);
            verify(route).addHandler(handlerCaptor.capture());

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("orders.list-span").startSpan();

            Map<String, Object> store = new HashMap<>();
            RoutingContext rc = mockRoutingContextWithSpan(store, span, httpMethod);

            try (var ignored = span.makeCurrent()) {
                handlerCaptor.getValue().handle(rc);
            } finally {
                span.end();
            }

            assertNotNull(store.get(RestSpanKeys.SPAN_KEY), "span must be stored under RestSpanKeys.SPAN_KEY");
            assertEquals(span, store.get(RestSpanKeys.SPAN_KEY), "stored span must be the active span");
        }

        @Test
        @DisplayName("handler calls rc.next() exactly once with a recording span")
        @SuppressWarnings("unchecked")
        void handlerCallsNextOnceWithRecordingSpan() {
            RouteRegistration route = mockRoute();
            OperationRegistrationContext ctx = buildContext("orders.get", "/orders/{id}", route);

            contributor.contribute(ctx);

            ArgumentCaptor<Handler<RoutingContext>> handlerCaptor = forClass(Handler.class);
            verify(route).addHandler(handlerCaptor.capture());

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test").startSpan();

            Map<String, Object> store = new HashMap<>();
            RoutingContext rc = mockRoutingContextWithSpan(store, span, "GET");

            try (var ignored = span.makeCurrent()) {
                handlerCaptor.getValue().handle(rc);
            } finally {
                span.end();
            }

            verify(rc).next();
        }
    }

    // --- Test 3: no recording span ---

    @Nested
    @DisplayName("no current recording span — no span stored, no exception, rc.next() called")
    class NoRecordingSpan {

        @Test
        @DisplayName("handler with no current span stores nothing, calls rc.next() exactly once, does not throw")
        @SuppressWarnings("unchecked")
        void handlerWithNoSpanCallsNextAndDoesNotThrow() {
            RouteRegistration route = mockRoute();
            OperationRegistrationContext ctx = buildContext("orders.get", "/orders/{id}", route);

            contributor.contribute(ctx);

            ArgumentCaptor<Handler<RoutingContext>> handlerCaptor = forClass(Handler.class);
            verify(route).addHandler(handlerCaptor.capture());

            Map<String, Object> store = new HashMap<>();
            RoutingContext rc = mockRoutingContextWithSpan(store, null, "GET");

            // No span made current — Span.current() returns a no-op non-recording span
            assertDoesNotThrow(() -> handlerCaptor.getValue().handle(rc));

            assertNull(store.get(RestSpanKeys.SPAN_KEY), "no span must be stored when no recording span is current");
            verify(rc).next();
        }
    }

    // --- Test 4: span ops throw — rc.next() still called ---

    @Nested
    @DisplayName("span ops throw — enrichment failure never breaks the pipeline")
    class SpanOpsFail {

        @Test
        @DisplayName("when rc.put() throws, rc.next() is still called exactly once")
        @SuppressWarnings("unchecked")
        void nextCalledEvenWhenPutThrows() {
            RouteRegistration route = mockRoute();
            OperationRegistrationContext ctx = buildContext("orders.get", "/orders/{id}", route);

            contributor.contribute(ctx);

            ArgumentCaptor<Handler<RoutingContext>> handlerCaptor = forClass(Handler.class);
            verify(route).addHandler(handlerCaptor.capture());

            Tracer tracer = OTEL.getOpenTelemetry().getTracer("test");
            Span span = tracer.spanBuilder("test").startSpan();

            // RoutingContext that throws on put()
            RoutingContext rc = mock(RoutingContext.class);
            HttpServerRequest request = mock(HttpServerRequest.class);
            when(rc.request()).thenReturn(request);
            when(request.method()).thenReturn(HttpMethod.GET);
            when(rc.put(any(), any())).thenThrow(new RuntimeException("simulated put failure"));

            try (var ignored = span.makeCurrent()) {
                assertDoesNotThrow(() -> handlerCaptor.getValue().handle(rc));
            } finally {
                span.end();
            }

            // rc.next() must still be called
            verify(rc, times(1)).next();
        }
    }

    // --- Helpers ---

    /**
     * Creates a mock {@link RouteRegistration} whose {@link RouteRegistration#addHandler} records
     * the registered handler and returns itself for fluent chaining.
     *
     * @return a configured mock {@link RouteRegistration}
     */
    private static RouteRegistration mockRoute() {
        RouteRegistration route = mock(RouteRegistration.class);
        when(route.addHandler(any())).thenReturn(route);
        return route;
    }

    /**
     * Creates a mock {@link RestOperationDescriptor} that exposes the given operationId and route
     * template.
     *
     * @param operationId   the operation identifier
     * @param routeTemplate the route template, e.g. {@code "/orders/{id}"}
     * @return a configured mock {@link RestOperationDescriptor}
     */
    private static RestOperationDescriptor mockDescriptor(String operationId, String routeTemplate) {
        RestOperationDescriptor descriptor = mock(RestOperationDescriptor.class);
        when(descriptor.operationId()).thenReturn(operationId);
        when(descriptor.routeTemplate()).thenReturn(routeTemplate);
        return descriptor;
    }

    /**
     * Builds a real {@link OperationRegistrationContext} record for the given operationId, route
     * template, and pre-built {@link RouteRegistration} mock.
     *
     * <p>Uses {@link SecurityPolicy.None} as the security policy (irrelevant to this test) and
     * wires {@code routeTemplate} through a {@link RestOperationDescriptor} mock so the contributor
     * reads it via {@code context.operation().routeTemplate()}.
     *
     * @param operationId   the operation identifier
     * @param routeTemplate the route template to expose via the descriptor
     * @param route         the {@link RouteRegistration} mock onto which the contributor adds its handler
     * @return the constructed {@link OperationRegistrationContext}
     */
    private static OperationRegistrationContext buildContext(
            String operationId, String routeTemplate, RouteRegistration route) {
        RestOperationDescriptor descriptor = mockDescriptor(operationId, routeTemplate);
        return new OperationRegistrationContext(operationId, new SecurityPolicy.None(), descriptor, route);
    }

    /**
     * Creates a mock {@link RoutingContext} that:
     * <ul>
     *   <li>records {@code put(key, value)} calls in the provided {@code store} map</li>
     *   <li>returns the given span from {@code get(RestSpanKeys.SPAN_KEY)} for downstream retrieval</li>
     *   <li>returns the given HTTP method from {@code request().method()}</li>
     *   <li>stubs {@code next()} as a no-op for verification</li>
     * </ul>
     *
     * @param store      the map to populate when {@code rc.put(key, value)} is called
     * @param activeSpan the span to make available when {@code Span.current()} resolves
     *                   (the handler reads it from context, not from the mock)
     * @param httpMethod the HTTP method string to return from the mocked request
     * @return a configured mock routing context
     */
    private static RoutingContext mockRoutingContextWithSpan(
            Map<String, Object> store, Span activeSpan, String httpMethod) {
        RoutingContext rc = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(rc.request()).thenReturn(request);
        when(request.method()).thenReturn(HttpMethod.valueOf(httpMethod));
        when(rc.put(any(), any())).thenAnswer(invocation -> {
            store.put(invocation.getArgument(0), invocation.getArgument(1));
            return rc;
        });
        when(rc.get(any(String.class))).thenAnswer(invocation -> store.get(invocation.getArgument(0)));
        return rc;
    }
}
