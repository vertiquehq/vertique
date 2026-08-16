// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.rest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.micrometer.rest.RestServerRequestMetricsListener;
import dev.vertique.rest.core.events.RestRequestCompletionEmitter;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import io.micrometer.core.instrument.Clock;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.tracer.otel.OpenTelemetrySpanContext;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxBuilder;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryTracingFactory;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test for AC-9 (production-path exemplar proof): proves that when a sampled
 * OpenTelemetry server span is active during a Micrometer timer recording on the REAL
 * framework completion path, the resulting Prometheus OpenMetrics scrape body carries an
 * exemplar with a {@code trace_id} matching the recorded server span.
 *
 * <h3>How this works</h3>
 *
 * <p>Vert.x 5's OTel tracer ends the server span and detaches its scope synchronously inside
 * {@code conn.write()} — before any {@code ctx.addEndHandler} callback fires. Without the
 * {@link RequestCompletionScope} SPI, {@link Span#current()} returns the no-op span when
 * {@link RestServerRequestMetricsListener#onCompleted} runs and no exemplar is attached.
 *
 * <p>With {@link ServerSpanCompletionScope} installed, the
 * {@link RestRequestCompletionEmitter} re-establishes the captured span as current before
 * dispatching listeners. The {@link RestServerRequestMetricsListener} then records the timer
 * with the span active, and the {@link OpenTelemetrySpanContext} bridge attaches the
 * {@code trace_id} exemplar to the Prometheus counter.
 *
 * <h3>Test setup</h3>
 *
 * <p>The test mounts the following stack on a traced {@link Vertx}:
 * <ol>
 *   <li>{@link RequestContextLifecycle} — owns the per-request context scope</li>
 *   <li>{@link RestRequestCompletionEmitter} constructed with:
 *       <ul>
 *         <li>{@link RestServerRequestMetricsListener} as the sole listener</li>
 *         <li>{@link ServerSpanCompletionScope} as the completion scope</li>
 *       </ul></li>
 *   <li>A route handler that captures {@link Span#current()} (the active server span) and
 *       stashes it on the routing context under {@link RestSpanKeys#SPAN_KEY}, mirroring
 *       what {@link ServerSpanEnrichmentContributor} does in the production wiring</li>
 * </ol>
 *
 * <p>The negative test confirms that Prometheus text-format scrapes carry no exemplar blocks.
 *
 * <h3>Determinism</h3>
 *
 * <p>The production-path test is repeated 5 times ({@code @RepeatedTest(5)}) to verify
 * determinism — the span capture and scope re-establishment must work on every request.
 *
 * <h3>Raw {@link HttpClient} exemption — post-response export timing</h3>
 *
 * <p>What is under assertion is when the server-side span and its exemplar become observable
 * <em>after</em> the response is written, so the test drives the raw client rather than a
 * {@code WebClient} that would add its own request/response handling to that window. The exchange
 * is status-only and uses the raw-client idiom pinned by {@code HttpClientBodyReadRaceIT}: the
 * response continuation is attached before {@code end()} initiates the send.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RestServerExemplarIT {

    // --- Shared per-test state ---

    private HttpServer server;
    private HttpClient httpClient;
    private Vertx tracedVertx;
    private OpenTelemetrySdk sdk;

    @AfterEach
    void tearDown(VertxTestContext ctx) {
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

    // --- Helpers ---

    /**
     * Builds an {@link OpenTelemetrySdk} with {@link Sampler#alwaysOn()} and W3C propagation,
     * wires it into a new traced Vert.x, stores both on instance fields, and returns the exporter.
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
     * Builds a {@link PrometheusMeterRegistry} with an {@link OpenTelemetrySpanContext} bridge.
     *
     * <p>The {@link OpenTelemetrySpanContext} reads {@link Span#current()} from the OTel thread-
     * local context, so whatever span is current at {@code timer.record()} time drives the exemplar
     * value. This mirrors the production wiring in {@code PrometheusScrapeEndpoint#contribute}
     * when exemplars are enabled.
     *
     * @return the registry configured for exemplar emission via OTel span context
     */
    private static PrometheusMeterRegistry buildRegistryWithExemplars() {
        OpenTelemetrySpanContext otelSpanContext = new OpenTelemetrySpanContext();
        return new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT, new PrometheusRegistry(), Clock.SYSTEM, otelSpanContext);
    }

    /**
     * Polls the exporter until at least one finished span is present.
     *
     * @param vertx       the Vert.x instance for timers
     * @param exporter    the exporter to poll
     * @param maxAttempts maximum attempts before failing
     * @param delayMs     delay between attempts in milliseconds
     * @return a future that resolves when a span is found
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
     * Issues a status-only GET through the raw-client idiom: the response continuation is attached
     * to the request's {@code response()} future <em>before</em> {@code end()} initiates the send,
     * because Vert.x discards response data delivered before a handler is attached. The send's own
     * outcome is deliberately not composed in — the exchange settles on the response, exactly as
     * {@code send()} did. See {@code HttpClientBodyReadRaceIT}.
     *
     * @param client the client issuing the request
     * @param port   the bound server port
     * @param path   the request path
     * @return a future of the response status code
     */
    private static Future<Integer> getStatus(HttpClient client, int port, String path) {
        return client.request(HttpMethod.GET, port, "127.0.0.1", path).compose(request -> {
            Future<Integer> responded = request.response().map(HttpClientResponse::statusCode);
            request.end();
            return responded;
        });
    }

    // --- Test 1: production-path exemplar proof (5x for determinism) ---

    /**
     * Production-path proof: the real framework completion pipeline attaches an exemplar
     * with the server span's trace id to the Prometheus OpenMetrics scrape.
     *
     * <p>The {@link RestRequestCompletionEmitter} dispatches listeners within the
     * {@link ServerSpanCompletionScope}, which re-establishes the server span as current.
     * {@link RestServerRequestMetricsListener#onCompleted} then records the timer with the
     * span active, and the {@link OpenTelemetrySpanContext} bridge attaches the
     * {@code trace_id} to the Prometheus timer exemplar.
     *
     * <p>Repeated 5 times to verify determinism — the scope must work on every request.
     */
    @RepeatedTest(5)
    @DisplayName("AC-9 production-path: OpenMetrics scrape carries exemplar with trace_id "
            + "matching the server span recorded via the framework completion pipeline")
    void productionPathExemplarAttachesViaCompletionScope(VertxTestContext ctx) {
        InMemorySpanExporter exporter = buildTracedVertx();
        Vertx vertx = tracedVertx;

        PrometheusMeterRegistry registry = buildRegistryWithExemplars();
        RestServerRequestMetricsListener listener = new RestServerRequestMetricsListener(registry, Optional.empty());
        ServerSpanCompletionScope completionScope = new ServerSpanCompletionScope();

        // Build emitter: listener + completion scope wired in
        RestRequestCompletionEmitter emitter = new RestRequestCompletionEmitter(
                Optional.empty(), new DefaultContextHolder(), Set.of(listener), Set.of(), Set.of(completionScope));

        AtomicReference<String> capturedTraceId = new AtomicReference<>();

        Router router = Router.router(vertx);
        router.route().order(RequestContextLifecycle.ORDER).handler(new RequestContextLifecycle());
        router.route().order(emitter.priority()).handler(emitter);

        // Route handler: stash the current server span on the RC (mirrors ServerSpanEnrichmentContributor)
        router.get("/exemplar").handler(rc -> {
            Span serverSpan = Span.current();
            if (serverSpan.getSpanContext().isValid()) {
                rc.put(RestSpanKeys.SPAN_KEY, serverSpan);
                capturedTraceId.set(serverSpan.getSpanContext().getTraceId());
            }
            rc.response().setStatusCode(200).end("ok");
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .compose(s -> {
                    this.server = s;
                    this.httpClient = vertx.createHttpClient();
                    return getStatus(httpClient, s.actualPort(), "/exemplar");
                })
                .compose(status -> pollUntilSpanPresent(vertx, exporter, 40, 50))
                // Extra wait for the end handler (completion emitter) to fire after the response
                .compose(v -> Future.<Void>future(p -> vertx.setTimer(100, id -> p.complete())))
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        // Verify a server span was exported
                        List<SpanData> spans = exporter.getFinishedSpanItems();
                        assertFalse(spans.isEmpty(), "at least one span must be exported");

                        String expectedTraceId = capturedTraceId.get();
                        assertNotNull(
                                expectedTraceId,
                                "server span must have been current in the route handler; "
                                        + "capturedTraceId should not be null");
                        assertFalse(expectedTraceId.isEmpty(), "capturedTraceId must not be empty");

                        // Verify the exported span set contains a span with the captured traceId
                        boolean traceFound = spans.stream().anyMatch(s -> expectedTraceId.equals(s.getTraceId()));
                        assertTrue(
                                traceFound,
                                "exported spans must include one with traceId=" + expectedTraceId
                                        + "; spans: "
                                        + spans.stream()
                                                .map(SpanData::getTraceId)
                                                .reduce((a, b) -> a + ", " + b)
                                                .orElse("(none)"));

                        // OpenMetrics scrape must contain the exemplar trace_id
                        String openMetricsBody =
                                registry.scrape("application/openmetrics-text; version=1.0.0; charset=utf-8");

                        assertTrue(
                                openMetricsBody.contains("trace_id"),
                                "OpenMetrics body must contain 'trace_id' in exemplar block; " + "body:\n"
                                        + openMetricsBody);
                        assertTrue(
                                openMetricsBody.contains(expectedTraceId),
                                "OpenMetrics body must contain the server span's traceId '" + expectedTraceId
                                        + "'; body:\n" + openMetricsBody);
                        assertTrue(
                                openMetricsBody.contains("# {"),
                                "OpenMetrics body must contain exemplar block '# {'; body:\n" + openMetricsBody);
                    });
                    ctx.completeNow();
                }));
    }

    // --- Test 2: Prometheus text format has no exemplars ---

    /**
     * Negative test: Prometheus text-format scrapes carry no exemplar blocks regardless of whether
     * a span is active during recording.
     */
    @Test
    @DisplayName("AC-9 negative: Prometheus text-format scrape has no exemplar block")
    void prometheusTextFormatHasNoExemplar(VertxTestContext ctx) {
        InMemorySpanExporter exporter = buildTracedVertx();
        Vertx vertx = tracedVertx;

        PrometheusMeterRegistry registry = buildRegistryWithExemplars();
        RestServerRequestMetricsListener listener = new RestServerRequestMetricsListener(registry, Optional.empty());
        ServerSpanCompletionScope completionScope = new ServerSpanCompletionScope();

        RestRequestCompletionEmitter emitter = new RestRequestCompletionEmitter(
                Optional.empty(), new DefaultContextHolder(), Set.of(listener), Set.of(), Set.of(completionScope));

        Router router = Router.router(vertx);
        router.route().order(RequestContextLifecycle.ORDER).handler(new RequestContextLifecycle());
        router.route().order(emitter.priority()).handler(emitter);

        router.get("/negative").handler(rc -> {
            Span serverSpan = Span.current();
            if (serverSpan.getSpanContext().isValid()) {
                rc.put(RestSpanKeys.SPAN_KEY, serverSpan);
            }
            rc.response().setStatusCode(200).end("ok");
        });

        vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .compose(s -> {
                    this.server = s;
                    this.httpClient = vertx.createHttpClient();
                    return getStatus(httpClient, s.actualPort(), "/negative");
                })
                .compose(status -> pollUntilSpanPresent(vertx, exporter, 40, 50))
                .compose(v -> Future.<Void>future(p -> vertx.setTimer(100, id -> p.complete())))
                .onComplete(ctx.succeeding(v -> {
                    ctx.verify(() -> {
                        // Prometheus text format must NOT carry exemplar blocks
                        String textBody = registry.scrape("text/plain; version=0.0.4; charset=utf-8");
                        assertFalse(
                                textBody.contains("# {"),
                                "Prometheus text format must NOT contain exemplar block '# {'; body:\n" + textBody);
                    });
                    ctx.completeNow();
                }));
    }
}
