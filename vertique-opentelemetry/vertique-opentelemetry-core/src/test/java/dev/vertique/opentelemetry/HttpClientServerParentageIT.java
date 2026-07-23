// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxBuilder;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryTracingFactory;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test verifying HTTP client-to-server span parentage under Vert.x tracing.
 *
 * <p>Verifies that when an HTTP client request is issued inside a manually-started parent span
 * (made current on a Vert.x context), the resulting CLIENT and SERVER spans are children of the
 * parent span, and all three share the same trace ID.
 *
 * <p>Both the manual parent span and the Vert.x-instrumented spans are created from the SAME
 * {@link OpenTelemetrySdk} and share the same {@link InMemorySpanExporter} — this is required so
 * that all spans appear in the same exporter for assertion.
 */
@ExtendWith(io.vertx.junit5.VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class HttpClientServerParentageIT {

    private HttpServer server;
    private HttpClient httpClient;
    private Vertx tracedVertx;
    private OpenTelemetrySdk sdk;

    @AfterEach
    void tearDown(io.vertx.junit5.VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<?> clientClose = httpClient != null ? httpClient.close() : Future.succeededFuture();
        if (sdk != null) {
            sdk.close();
        }
        GlobalOpenTelemetry.resetForTest();
        // Join the server/client closes while tracedVertx's event loop is still alive (they resolve on
        // it), then close the traced Vertx last from the callback. Including tracedVertx.close() in the
        // join raced the loop shutdown and intermittently threw RejectedExecutionException
        // ("event executor terminated").
        Future.join(serverClose, clientClose).onComplete(ar -> {
            if (tracedVertx != null) {
                tracedVertx.close();
            }
            ctx.completeNow();
        });
    }

    /**
     * Builds an {@link OpenTelemetrySdk} with the given exporter, wires it into a new traced Vert.x,
     * and returns both so that tests can use the SDK's tracer to create parent spans that appear in
     * the same exporter as the Vert.x-instrumented spans.
     *
     * @param exporter the shared span exporter
     * @return the built {@link OpenTelemetrySdk} (already installed in the returned Vert.x)
     */
    private OpenTelemetrySdk buildSdkAndTracedVertx(InMemorySpanExporter exporter) {
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
        return sdk;
    }

    /**
     * Polls the exporter until at least {@code minSpans} spans are available.
     *
     * @param vertx       the Vert.x instance to use for timers
     * @param exporter    the exporter to poll
     * @param minSpans    the minimum number of spans to wait for
     * @param maxAttempts maximum polling iterations
     * @param delayMs     delay between iterations in milliseconds
     * @return a future that succeeds when enough spans are found
     */
    private static Future<Void> pollUntilSpans(
            Vertx vertx, InMemorySpanExporter exporter, int minSpans, int maxAttempts, long delayMs) {
        if (maxAttempts <= 0) {
            return Future.failedFuture(new AssertionError("Timed out waiting for " + minSpans + " spans; got "
                    + exporter.getFinishedSpanItems().size() + ": " + spanNames(exporter.getFinishedSpanItems())));
        }
        if (exporter.getFinishedSpanItems().size() >= minSpans) {
            return Future.succeededFuture();
        }
        return Future.<Void>future(p -> vertx.setTimer(delayMs, id -> p.complete()))
                .compose(v -> pollUntilSpans(vertx, exporter, minSpans, maxAttempts - 1, delayMs));
    }

    // --- Test: client span parent == manual parent; server span parent == client span ---

    @Test
    @DisplayName("HTTP client/server: CLIENT parent == manual root; SERVER parent == CLIENT span; shared traceId")
    void clientServerSpanParentage(io.vertx.junit5.VertxTestContext ctx) {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        // Build SDK and Vert.x sharing the same exporter
        OpenTelemetrySdk builtSdk = buildSdkAndTracedVertx(exporter);
        Vertx vertx = tracedVertx;

        // Obtain a tracer from the SAME SDK that Vert.x uses, so the parent span appears in
        // the same exporter as the CLIENT and SERVER spans.
        Tracer tracer = builtSdk.getTracer("test-parent");

        // Start a plain HTTP server with a trivial 200 handler
        Router router = Router.router(vertx);
        router.route("/ping").handler(rc -> rc.response().setStatusCode(200).end("pong"));

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0)
                .compose(s -> {
                    this.server = s;
                    this.httpClient = vertx.createHttpClient();
                    int port = s.actualPort();

                    // Start a parent span using the same SDK (same exporter as Vert.x tracing)
                    Span parentSpan = tracer.spanBuilder("manual-parent")
                            .setSpanKind(SpanKind.INTERNAL)
                            .startSpan();

                    // Issue the HTTP client request from a Vert.x context with the parent span current.
                    // The Vert.x HTTP client (PROPAGATE policy) reads ACTIVE_CONTEXT from the calling
                    // Vert.x context to create the CLIENT span and inject W3C headers. The scope must
                    // remain open until sendRequest fires (asynchronously) — do NOT use try-with-resources
                    // here, as that would close the scope synchronously before the request is dispatched.
                    return Future.<Void>future(promise -> vertx.runOnContext(v -> {
                        Scope scope = parentSpan.makeCurrent();
                        httpClient
                                .request(HttpMethod.GET, port, "localhost", "/ping")
                                .compose(req -> req.send())
                                .onComplete(ar -> {
                                    scope.close();
                                    parentSpan.end();
                                    if (ar.succeeded()) {
                                        promise.complete();
                                    } else {
                                        promise.fail(ar.cause());
                                    }
                                });
                    }));
                })
                // Wait for CLIENT + SERVER + manual parent = 3 spans
                .compose(ignored -> pollUntilSpans(vertx, exporter, 3, 40, 50))
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        List<SpanData> spans = exporter.getFinishedSpanItems();

                        SpanData parentSpanData = findSpanByName(spans, "manual-parent");
                        assertNotNull(parentSpanData, "manual-parent span must be exported; got: " + spanNames(spans));

                        SpanData clientSpanData = findSpanByKind(spans, SpanKind.CLIENT);
                        assertNotNull(clientSpanData, "CLIENT span must be exported; got: " + spanNames(spans));

                        SpanData serverSpanData = findSpanByKind(spans, SpanKind.SERVER);
                        assertNotNull(serverSpanData, "SERVER span must be exported; got: " + spanNames(spans));

                        // All three must share the same traceId
                        String traceId = parentSpanData.getTraceId();
                        assertEquals(traceId, clientSpanData.getTraceId(), "CLIENT must share traceId with parent");
                        assertEquals(traceId, serverSpanData.getTraceId(), "SERVER must share traceId with parent");

                        // CLIENT span's parent must be the manual parent span
                        SpanContext clientParentCtx = clientSpanData.getParentSpanContext();
                        assertTrue(clientParentCtx.isValid(), "CLIENT span must have a valid parent span context");
                        assertEquals(
                                parentSpanData.getSpanId(),
                                clientParentCtx.getSpanId(),
                                "CLIENT span parent must be the manual parent span");

                        // SERVER span's parent must be the CLIENT span
                        SpanContext serverParentCtx = serverSpanData.getParentSpanContext();
                        assertTrue(serverParentCtx.isValid(), "SERVER span must have a valid parent span context");
                        assertEquals(
                                clientSpanData.getSpanId(),
                                serverParentCtx.getSpanId(),
                                "SERVER span parent must be the CLIENT span");
                    });
                    ctx.completeNow();
                }));
    }

    // --- Helpers ---

    private static SpanData findSpanByName(List<SpanData> spans, String name) {
        return spans.stream().filter(s -> name.equals(s.getName())).findFirst().orElse(null);
    }

    private static SpanData findSpanByKind(List<SpanData> spans, SpanKind kind) {
        return spans.stream().filter(s -> s.getKind() == kind).findFirst().orElse(null);
    }

    private static String spanNames(List<SpanData> spans) {
        return spans.stream()
                .map(s -> s.getName() + "[" + s.getKind() + "]")
                .reduce((a, b) -> a + ", " + b)
                .orElse("(none)");
    }
}
