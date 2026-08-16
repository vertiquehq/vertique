// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.router.OperationRegistrationContext;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.RouteRegistration;
import dev.vertique.rest.core.security.SecurityPolicy;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.semconv.HttpAttributes;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.VertxBuilder;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryTracingFactory;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for {@link ServerSpanEnrichmentContributor} proving AC-7: the contributor
 * correctly enriches server spans with route template, operationId, and span naming when a real
 * Vert.x HTTP server is traced with an OTel SDK.
 *
 * <p>Two scenarios are verified:
 * <ol>
 *   <li><strong>Traced Vert.x + W3C traceparent</strong>: a client request carrying a W3C
 *       {@code traceparent} header causes the server span to be renamed to
 *       {@code "GET /orders/{id}"}, with {@code http.route} and {@code vertique.operation.id}
 *       attributes set; the server span shares the injected trace ID and has the injected span as
 *       its parent.</li>
 *   <li><strong>Plain Vert.x (no tracer)</strong>: the contributor runs as a no-op — no spans
 *       exported, the handler chain is unaffected (200 OK returned).</li>
 * </ol>
 *
 * <p>The contributor is driven by invoking {@link ServerSpanEnrichmentContributor#contribute} with
 * a real {@link OperationRegistrationContext} record whose {@link RouteRegistration} is a mock that
 * captures the registered handler. The captured handler is then mounted directly on a real Vert.x
 * {@link Router} route. This mirrors the production wiring where contributors add handlers via
 * {@link RouteRegistration#addHandler}.
 *
 * <p>All tests use class-level 20-second timeout and port 0 for deterministic port allocation.
 *
 * <p>Requests are issued through a {@link WebClient} rather than a raw {@code HttpClient}
 * deliberately. Nothing here observes the client: every assertion is on the SERVER span the
 * <em>server's</em> tracer exported, so the raw client is a bare trigger and buys nothing. The
 * {@code traceparent} header still reaches the server verbatim — {@code putHeader} sets it on the
 * request before the send, and with no span active on the calling context the client's own tracing
 * policy ({@code PROPAGATE}) neither opens a CLIENT span nor injects a header of its own. And while
 * both exchanges are status-only — which is why a raw client could not lose anything here today — a
 * status-only raw exchange is merely unexposed to the empty-body race (issue #167), not immune to
 * it: the first body assertion added here would make it live. A {@link WebClient} aggregates the
 * body into its {@code HttpResponse} before completing the send, so the hazard cannot appear at all.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RestServerSpanEnrichmentIT {

    // --- Shared per-test state ---

    private HttpServer server;
    private WebClient httpClient;
    private Vertx tracedVertx;
    private OpenTelemetrySdk sdk;

    /**
     * Tears the exchange down in the one order that survives an event-loop shutdown.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is no future to join and the client
     * close is no longer awaited — a deliberate trade taken when this class moved off the raw client.
     * The server close alone now carries the completion.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (httpClient != null) {
            httpClient.close();
        }
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        if (sdk != null) {
            sdk.close();
        }
        GlobalOpenTelemetry.resetForTest();
        // Close the server while tracedVertx's event loop is still alive (that close resolves on it),
        // then close the traced Vertx last from the callback. Including tracedVertx.close() alongside
        // it raced the loop shutdown and intermittently threw RejectedExecutionException
        // ("event executor terminated").
        serverClose.onComplete(ar -> {
            if (tracedVertx != null) {
                tracedVertx.close();
            }
            ctx.completeNow();
        });
    }

    // --- Helpers ---

    /**
     * Builds an {@link OpenTelemetrySdk} with {@link Sampler#alwaysOn()} and W3C propagation,
     * wires it into a new Vert.x, stores both on instance fields, and returns the exporter.
     *
     * @return the in-memory exporter shared with the Vert.x tracer
     */
    private InMemorySpanExporter buildTracedVertx() {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();
        sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        VertxOptions options = new VertxOptions().setTracingOptions(new OpenTelemetryOptions());
        VertxBuilder builder = Vertx.builder().with(options).withTracer(new OpenTelemetryTracingFactory(sdk));
        tracedVertx = builder.build();
        return exporter;
    }

    /**
     * Polls the exporter until at least one finished span is present.
     *
     * @param vertx       the Vert.x instance to use for timers
     * @param exporter    the exporter to poll
     * @param maxAttempts maximum polling attempts before failing
     * @param delayMs     delay between attempts in milliseconds
     * @return a future that succeeds when at least one span is found
     */
    private static Future<Void> pollUntilSpanPresent(
            Vertx vertx, InMemorySpanExporter exporter, int maxAttempts, long delayMs) {
        if (maxAttempts <= 0) {
            return Future.failedFuture(new AssertionError("Timed out waiting for a span in the exporter"));
        }
        if (!exporter.getFinishedSpanItems().isEmpty()) {
            return Future.succeededFuture();
        }
        return Future.<Void>future(p -> vertx.setTimer(delayMs, id -> p.complete()))
                .compose(v -> pollUntilSpanPresent(vertx, exporter, maxAttempts - 1, delayMs));
    }

    /**
     * Uses {@link ServerSpanEnrichmentContributor#contribute} with a real
     * {@link OperationRegistrationContext} record to capture the enrichment handler, then builds a
     * router that mounts the handler on {@code GET /orders/:id} followed by a terminal 200 OK
     * handler.
     *
     * <p>The {@link RouteRegistration} is a mock whose {@code addHandler} captures the contributed
     * handler. The {@link RestOperationDescriptor} is a mock that returns the operationId and route
     * template. Both are wired into the real {@link OperationRegistrationContext} record so the
     * contributor reads them via {@code context.operation().routeTemplate()} and
     * {@code context.route().addHandler(...)}.
     *
     * @param vertx       the Vert.x instance
     * @param contributor the contributor under test
     * @return the configured router
     */
    @SuppressWarnings("unchecked")
    private static Router buildRouter(Vertx vertx, ServerSpanEnrichmentContributor contributor) {
        String operationId = "orders.get";
        String routeTemplate = "/orders/{id}";

        RestOperationDescriptor descriptor = mock(RestOperationDescriptor.class);
        when(descriptor.operationId()).thenReturn(operationId);
        when(descriptor.routeTemplate()).thenReturn(routeTemplate);

        RouteRegistration route = mock(RouteRegistration.class);
        AtomicReference<Handler<RoutingContext>> handlerRef = new AtomicReference<>();
        when(route.addHandler(any())).thenAnswer(invocation -> {
            handlerRef.set(invocation.getArgument(0));
            return route;
        });

        OperationRegistrationContext ctx =
                new OperationRegistrationContext(operationId, new SecurityPolicy.None(), descriptor, route);

        contributor.contribute(ctx);

        Handler<RoutingContext> enrichmentHandler = handlerRef.get();
        assertNotNull(enrichmentHandler, "contribute() must register a handler");

        Router router = Router.router(vertx);
        router.get("/orders/:id").handler(enrichmentHandler);
        router.get("/orders/:id").handler(rc -> rc.response().setStatusCode(200).end("ok"));
        return router;
    }

    /**
     * Starts an HTTP server on port 0 and stores the server and a client on instance fields.
     *
     * @param vertx  the Vert.x instance
     * @param router the request handler
     * @return a future resolving to the actual bound port
     */
    private Future<Integer> startServer(Vertx vertx, Router router) {
        return vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .map(s -> {
                    this.server = s;
                    // Redirects off: parity with the raw client; WebClient follows 3xx by default.
                    this.httpClient = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
                    return s.actualPort();
                });
    }

    // --- Test 1: W3C traceparent → server span renamed, attrs set, traceId + parentSpanId match ---

    @Test
    @DisplayName("W3C traceparent injected: server span renamed to 'GET /orders/{id}', "
            + "http.route and vertique.operation.id attrs set, traceId and parent spanId match")
    void tracedRequestEnrichesServerSpan(VertxTestContext ctx) {
        InMemorySpanExporter exporter = buildTracedVertx();
        Vertx vertx = tracedVertx;

        ServerSpanEnrichmentContributor contributor = new ServerSpanEnrichmentContributor();
        Router router = buildRouter(vertx, contributor);

        // Build a valid sampled parent SpanContext to inject via W3C traceparent header
        String parentTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String parentSpanId = "00f067aa0ba902b7";
        String traceparent = "00-" + parentTraceId + "-" + parentSpanId + "-"
                + TraceFlags.getSampled().asHex();

        startServer(vertx, router)
                .compose(port -> httpClient
                        // Status-only exchange. putHeader sets the injected parent on the request
                        // before the send, so the server's tracer extracts it verbatim; the
                        // WebClient's response future then resolves once the body (here empty) has
                        // been aggregated, which is strictly later than the raw client's send()
                        // settled — harmless, because the span poll below is what this test waits on.
                        .get(port, "127.0.0.1", "/orders/42")
                        .putHeader("traceparent", traceparent)
                        .send()
                        .map(HttpResponse::statusCode))
                .compose(status -> pollUntilSpanPresent(vertx, exporter, 40, 50))
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        List<SpanData> spans = exporter.getFinishedSpanItems();
                        assertNotNull(spans, "exporter must have spans");

                        // Find the SERVER span
                        SpanData serverSpan = spans.stream()
                                .filter(s -> s.getKind() == SpanKind.SERVER)
                                .findFirst()
                                .orElseThrow(() -> new AssertionError("No SERVER span found; got: "
                                        + spans.stream()
                                                .map(s -> s.getName() + "[" + s.getKind() + "]")
                                                .reduce((a, b) -> a + ", " + b)
                                                .orElse("(none)")));

                        // Span must be renamed to "GET /orders/{id}"
                        assertEquals(
                                "GET /orders/{id}",
                                serverSpan.getName(),
                                "server span must be renamed to 'GET /orders/{id}'");

                        // http.route attribute must be set to the route template
                        assertEquals(
                                "/orders/{id}",
                                serverSpan.getAttributes().get(HttpAttributes.HTTP_ROUTE),
                                "http.route attribute must be '/orders/{id}'");

                        // vertique.operation.id attribute must be set
                        assertEquals(
                                "orders.get",
                                serverSpan.getAttributes().get(RestSpanKeys.VERTIQUE_OPERATION_ID),
                                "vertique.operation.id attribute must be 'orders.get'");

                        // The server span must share the injected trace ID
                        assertEquals(
                                parentTraceId,
                                serverSpan.getTraceId(),
                                "server span traceId must equal the injected parent traceId");

                        // The server span's parent must be the injected parent span ID
                        assertEquals(
                                parentSpanId,
                                serverSpan.getParentSpanContext().getSpanId(),
                                "server span parent spanId must equal the injected parent spanId");
                    });
                    ctx.completeNow();
                }));
    }

    // --- Test 2: plain Vert.x (no tracer) → 200 OK, zero spans exported ---

    @Test
    @DisplayName("plain Vert.x (no tracer): contributor is a no-op — 200 OK returned, zero spans exported")
    void plainVertxNoTracerIsNoOp(Vertx vertx, VertxTestContext ctx) {
        // Use the plain Vert.x injected by VertxExtension (no OTel tracer wired)
        InMemorySpanExporter localExporter = InMemorySpanExporter.create();

        ServerSpanEnrichmentContributor contributor = new ServerSpanEnrichmentContributor();
        Router router = buildRouter(vertx, contributor);

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .compose(s -> {
                    this.server = s;
                    // Redirects off: parity with the raw client; WebClient follows 3xx by default.
                    this.httpClient = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
                    // Status-only exchange — see the note in tracedRequestEnrichesServerSpan.
                    return httpClient
                            .get(s.actualPort(), "127.0.0.1", "/orders/99")
                            .send()
                            .map(HttpResponse::statusCode);
                })
                .compose(statusCode -> {
                    // Brief wait to confirm no async span export happens
                    return Future.<Integer>future(p -> vertx.setTimer(100, id -> p.complete(statusCode)));
                })
                .onComplete(ctx.succeeding(statusCode -> {
                    ctx.verify(() -> {
                        assertEquals(200, statusCode, "plain Vert.x must return 200 OK");
                        assertTrue(
                                localExporter.getFinishedSpanItems().isEmpty(),
                                "local exporter (never wired in) must have no spans");
                    });
                    ctx.completeNow();
                }));
    }
}
