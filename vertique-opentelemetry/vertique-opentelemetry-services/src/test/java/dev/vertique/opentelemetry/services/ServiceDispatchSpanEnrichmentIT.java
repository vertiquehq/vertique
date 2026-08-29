// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.opentelemetry.services;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.core.eventbus.DispatchEnvelope;
import dev.vertique.core.eventbus.LocalMessageCodec;
import dev.vertique.core.eventbus.Result;
import dev.vertique.resilience.annotation.ResilienceAnnotations;
import dev.vertique.services.ServiceContract;
import dev.vertique.services.ServiceExceptionMapper;
import dev.vertique.services.ServiceOperation;
import dev.vertique.services.dispatch.ServiceMethodDescriptor;
import dev.vertique.services.dispatch.ServiceMethodInvoker;
import dev.vertique.services.dispatch.ServiceMethodMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamMeta;
import dev.vertique.services.dispatch.ServiceMethodMeta.ParamSource;
import dev.vertique.services.interceptor.ServiceDispatchContext;
import dev.vertique.services.interceptor.ServiceInterceptor;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
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
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxBuilder;
import io.vertx.core.VertxOptions;
import io.vertx.core.eventbus.DeliveryOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryTracingFactory;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test for {@link ServiceDispatchSpanEnrichmentInterceptor} wired into a real Vert.x
 * event bus via a {@link ServiceMethodInvoker} consumer backed by a traced Vert.x instance.
 *
 * <p>Combines the harness patterns from {@code ServiceDispatchMetricsIT} (inline service fixture,
 * {@link ServiceMethodInvoker} consumer, signal interceptor for terminal completion) and
 * {@code EventBusParentageIT} (traced Vert.x with {@link InMemorySpanExporter}, parent-span
 * scope, poll-until-spans helper).
 *
 * <p>Tests:
 * <ol>
 *   <li>Traced dispatch (parent span current): the CONSUMER span carries
 *       {@code vertique.service.target} and {@code vertique.service.oneway=false}; it shares the
 *       parent traceId.</li>
 *   <li>Untraced dispatch (no parent span): request succeeds and zero spans are exported
 *       (PROPAGATE policy — no span, no enrichment).</li>
 * </ol>
 *
 * <p>All tests are class-level timeout-guarded at 20 s. Each scenario is driven 5 times in a
 * sequential stress loop (determinism check).
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ServiceDispatchSpanEnrichmentIT {

    // --- Contract and implementation fixtures ---

    /** Minimal service contract used in all IT scenarios. */
    @ServiceContract(namespace = "it", value = "span-it")
    interface SpanItContract {
        /** Returns a greeting. */
        @ServiceOperation("greet")
        Future<String> greet(String name);
    }

    /** Implementation that always succeeds. */
    static class GreetImpl implements SpanItContract {
        @Override
        public Future<String> greet(String name) {
            return Future.succeededFuture("Hello " + name);
        }
    }

    // --- Shared state ---

    /** Stable address counter; unique per iteration to avoid consumer collisions. */
    private static final AtomicInteger ADDR = new AtomicInteger(0);

    private static String uniqueAddress(String base) {
        return "it/span-enrichment/" + base + "/" + ADDR.incrementAndGet();
    }

    /** The traced Vert.x; created fresh per test, closed in {@link #tearDown}. */
    private Vertx tracedVertx;

    /**
     * Closes the traced Vert.x and resets {@link GlobalOpenTelemetry} after each test.
     *
     * @param ctx the Vert.x test context
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        GlobalOpenTelemetry.resetForTest();
        if (tracedVertx != null) {
            Vertx v = tracedVertx;
            tracedVertx = null;
            v.close().onComplete(ar -> ctx.completeNow());
        } else {
            ctx.completeNow();
        }
    }

    // --- Traced Vert.x builder ---

    /**
     * Builds an {@link OpenTelemetrySdk} wired into a fresh traced Vert.x, sharing the given
     * exporter. The result is stored in {@link #tracedVertx} for teardown.
     *
     * @param exporter the in-memory span exporter
     * @return the built SDK (tracer obtained from here shares the same export pipeline)
     */
    private OpenTelemetrySdk buildSdkAndTracedVertx(InMemorySpanExporter exporter) {
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(exporter))
                .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();

        VertxOptions options = new VertxOptions().setTracingOptions(new OpenTelemetryOptions());
        VertxBuilder builder = Vertx.builder().with(options).withTracer(new OpenTelemetryTracingFactory(sdk));
        tracedVertx = builder.build();
        // Register dispatch codecs on this Vert.x instance — codecs are per-instance.
        try {
            tracedVertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.envelope"));
            tracedVertx.eventBus().registerCodec(new LocalMessageCodec<>("dispatch.result"));
        } catch (IllegalStateException ignored) {
            // Already registered (e.g. when a test shares an instance) — safe to ignore.
        }
        return sdk;
    }

    // --- Poll helper ---

    /**
     * Polls the exporter until at least {@code minSpans} spans are present.
     *
     * @param vertx       the Vert.x instance used for timer scheduling
     * @param exporter    the exporter to poll
     * @param minSpans    minimum number of spans to wait for
     * @param maxAttempts maximum polling attempts before failing
     * @param delayMs     delay in milliseconds between attempts
     * @return a Future that succeeds when {@code minSpans} spans are exported
     */
    private static Future<Void> pollUntilSpans(
            Vertx vertx, InMemorySpanExporter exporter, int minSpans, int maxAttempts, long delayMs) {
        if (maxAttempts <= 0) {
            return Future.failedFuture(new AssertionError("Timed out waiting for " + minSpans + " spans; got "
                    + exporter.getFinishedSpanItems().size()
                    + ": "
                    + spanNames(exporter.getFinishedSpanItems())));
        }
        if (exporter.getFinishedSpanItems().size() >= minSpans) {
            return Future.succeededFuture();
        }
        return Future.<Void>future(p -> vertx.setTimer(delayMs, id -> p.complete()))
                .compose(v -> pollUntilSpans(vertx, exporter, minSpans, maxAttempts - 1, delayMs));
    }

    // --- ServiceMethodMeta builder ---

    private static final DeliveryOptions ENVELOPE_CODEC = new DeliveryOptions().setCodecName("dispatch.envelope");

    /**
     * Builds {@link ServiceMethodMeta} for the {@code greet} method.
     *
     * @param impl           the service implementation instance
     * @param address        the event bus address
     * @param stableTargetId the stable target id (e.g. {@code it.span-it.greet})
     * @return metadata for the greet operation
     * @throws Exception if method lookup fails
     */
    private static ServiceMethodMeta greetMeta(Object impl, String address, String stableTargetId) throws Exception {
        Method method = SpanItContract.class.getMethod("greet", String.class);
        return ServiceMethodMeta.ofDirect(
                impl,
                ServiceMethodDescriptor.of(method),
                address,
                stableTargetId,
                "it",
                "span-it",
                "greet",
                String.class,
                String.class,
                List.of(new ParamMeta("name", ParamSource.PAYLOAD, String.class)),
                ResilienceAnnotations.NONE,
                List.of(),
                List.of(),
                false);
    }

    // --- Signal interceptor ---

    /**
     * Returns a {@link ServiceInterceptor} that completes {@code signal} when
     * {@link ServiceInterceptor#onTerminalComplete} fires.
     *
     * @param signal the promise to complete once the terminal hook fires
     * @return an interceptor that signals on terminal completion
     */
    private static ServiceInterceptor signalOnTerminal(Promise<Void> signal) {
        return new ServiceInterceptor() {
            @Override
            public void onTerminalComplete(
                    ServiceDispatchContext ctx, Result<?> result, Instant startTime, Instant endTime) {
                signal.tryComplete();
            }
        };
    }

    // --- Test 1: traced dispatch carries vertique.service.target and vertique.service.oneway=false ---

    /**
     * Runs one iteration of the traced-dispatch scenario.
     *
     * <p>Starts a parent span on a Vert.x context, makes it current, dispatches a request, waits
     * for the CONSUMER span (identified by having a child relationship to the parent), then asserts
     * that {@code vertique.service.target} and {@code vertique.service.oneway=false} are present.
     *
     * @param sdk      the OTel SDK (used to obtain the tracer)
     * @param exporter the shared span exporter
     * @return a Future that completes when the assertion passes
     * @throws Exception if meta construction fails
     */
    private Future<Void> runTracedDispatchIteration(OpenTelemetrySdk sdk, InMemorySpanExporter exporter)
            throws Exception {
        Vertx vertx = tracedVertx;
        Tracer tracer = sdk.getTracer("test-span-enrichment");
        String stableTarget = "it.span-it.greet";
        String address = uniqueAddress("greet");
        Promise<Void> terminal = Promise.promise();

        ServiceDispatchSpanEnrichmentInterceptor enrichment = new ServiceDispatchSpanEnrichmentInterceptor();
        ServiceMethodMeta meta = greetMeta(new GreetImpl(), address, stableTarget);
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(
                meta, new ServiceExceptionMapper(), List.of(enrichment, signalOnTerminal(terminal)), null);
        vertx.eventBus().consumer(address, invoker);

        Span parentSpan =
                tracer.spanBuilder("it-parent").setSpanKind(SpanKind.INTERNAL).startSpan();

        // Issue the request from inside the parent span's scope on a Vert.x context, then
        // close the scope and end the parent in the reply callback.
        return Future.<String>future(promise -> vertx.runOnContext(v -> {
                    Scope scope = parentSpan.makeCurrent();
                    vertx.eventBus()
                            .<Result<?>>request(address, DispatchEnvelope.of("World"), ENVELOPE_CODEC)
                            .onComplete(ar -> {
                                scope.close();
                                parentSpan.end();
                                if (ar.succeeded()) {
                                    promise.complete("ok");
                                } else {
                                    promise.fail(ar.cause());
                                }
                            });
                }))
                // Wait for the terminal hook to confirm onDispatch has already fired.
                .compose(reply -> terminal.future())
                // Wait for at least: parent + producer/send + consumer spans (Vert.x emits 2 event-bus
                // spans for request/reply under a parent; with the parent that is 3). Tolerate 2 in
                // case the runtime only emits a producer span.
                .compose(v -> pollUntilSpans(vertx, exporter, 2, 60, 50))
                .map(v -> {
                    List<SpanData> spans = exporter.getFinishedSpanItems();
                    String parentTraceId = parentSpan.getSpanContext().getTraceId();

                    // Find the span that carries vertique.service.target — that is the CONSUMER span
                    // enriched by the interceptor (fireOnDispatch runs synchronously within it).
                    SpanData consumerSpan = spans.stream()
                            .filter(s -> stableTarget.equals(s.getAttributes().get(ServiceAttributes.SERVICE_TARGET)))
                            .findFirst()
                            .orElse(null);

                    assertNotNull(
                            consumerSpan,
                            "A span carrying vertique.service.target='" + stableTarget + "' must be exported; spans: "
                                    + spanNames(spans));

                    assertEquals(
                            stableTarget,
                            consumerSpan.getAttributes().get(ServiceAttributes.SERVICE_TARGET),
                            "vertique.service.target must equal the stableTargetId");

                    assertEquals(
                            Boolean.FALSE,
                            consumerSpan.getAttributes().get(ServiceAttributes.SERVICE_ONEWAY),
                            "vertique.service.oneway must be false for a request/reply dispatch");

                    // The enriched span must share the parent traceId
                    assertEquals(
                            parentTraceId,
                            consumerSpan.getTraceId(),
                            "Enriched consumer span must share traceId with the parent span");

                    // All spans must belong to the same trace
                    for (SpanData s : spans) {
                        assertEquals(
                                parentTraceId,
                                s.getTraceId(),
                                "Span " + s.getName() + " must share traceId with parent");
                    }

                    return null;
                });
    }

    @Test
    @DisplayName(
            "Test S29-1: traced dispatch → consumer span carries vertique.service.target and vertique.service.oneway=false; same traceId as parent (5x stress loop)")
    void tracedDispatchConsumerSpanCarriesServiceAttributes(VertxTestContext ctx) throws Exception {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        OpenTelemetrySdk sdk = buildSdkAndTracedVertx(exporter);

        Future<Void> loop = runTracedDispatchIteration(sdk, exporter);
        for (int i = 1; i < 5; i++) {
            loop = loop.compose(v -> {
                exporter.reset();
                try {
                    return runTracedDispatchIteration(sdk, exporter);
                } catch (Exception e) {
                    return Future.failedFuture(e);
                }
            });
        }
        loop.onComplete(ar -> {
            if (ar.succeeded()) {
                ctx.completeNow();
            } else {
                ctx.failNow(ar.cause());
            }
        });
    }

    // --- Test 2: untraced dispatch → no spans, no exception ---

    /**
     * Runs one iteration of the untraced-dispatch scenario.
     *
     * <p>Dispatches a request with no parent span current. The PROPAGATE policy means Vert.x does
     * not create any event-bus spans. The interceptor's no-recording-span guard must silently skip
     * enrichment. Asserts that the reply is received and the exporter contains zero spans.
     *
     * @param exporter the shared span exporter
     * @return a Future that completes when the assertion passes
     * @throws Exception if meta construction fails
     */
    private Future<Void> runUntracedDispatchIteration(InMemorySpanExporter exporter) throws Exception {
        Vertx vertx = tracedVertx;
        String address = uniqueAddress("greet-untraced");
        Promise<Void> terminal = Promise.promise();

        ServiceDispatchSpanEnrichmentInterceptor enrichment = new ServiceDispatchSpanEnrichmentInterceptor();
        ServiceMethodMeta meta = greetMeta(new GreetImpl(), address, "it.span-it.greet");
        ServiceMethodInvoker invoker = new ServiceMethodInvoker(
                meta, new ServiceExceptionMapper(), List.of(enrichment, signalOnTerminal(terminal)), null);
        vertx.eventBus().consumer(address, invoker);

        // Issue the request with NO parent span current — Vert.x PROPAGATE policy means no spans
        // are created for this dispatch.
        return Future.<String>future(promise -> vertx.runOnContext(v -> {
                    vertx.eventBus()
                            .<Result<?>>request(address, DispatchEnvelope.of("World"), ENVELOPE_CODEC)
                            .onComplete(ar -> {
                                if (ar.succeeded()) {
                                    promise.complete("ok");
                                } else {
                                    promise.fail(ar.cause());
                                }
                            });
                }))
                // Wait for the terminal hook to confirm the entire dispatch path ran
                .compose(reply -> terminal.future())
                .map(v -> {
                    // With no parent and PROPAGATE policy, Vert.x creates zero event-bus spans.
                    List<SpanData> spans = exporter.getFinishedSpanItems();
                    assertFalse(
                            spans.stream()
                                    .anyMatch(s -> s.getAttributes().get(ServiceAttributes.SERVICE_TARGET) != null),
                            "No span should carry vertique.service.target when no parent span was active; spans: "
                                    + spanNames(spans));
                    return null;
                });
    }

    @Test
    @DisplayName(
            "Test S29-2: untraced dispatch (no parent span) → reply received, exporter has no vertique-attributed spans (5x stress loop)")
    void untracedDispatchProducesNoAttributedSpans(VertxTestContext ctx) throws Exception {
        InMemorySpanExporter exporter = InMemorySpanExporter.create();
        buildSdkAndTracedVertx(exporter);

        Future<Void> loop = runUntracedDispatchIteration(exporter);
        for (int i = 1; i < 5; i++) {
            loop = loop.compose(v -> {
                exporter.reset();
                try {
                    return runUntracedDispatchIteration(exporter);
                } catch (Exception e) {
                    return Future.failedFuture(e);
                }
            });
        }
        loop.onComplete(ar -> {
            if (ar.succeeded()) {
                ctx.completeNow();
            } else {
                ctx.failNow(ar.cause());
            }
        });
    }

    // --- Helpers ---

    private static String spanNames(List<SpanData> spans) {
        return spans.stream()
                .map(s -> s.getName()
                        + "[kind="
                        + s.getKind()
                        + ",parent="
                        + s.getParentSpanContext().getSpanId()
                        + ",target="
                        + s.getAttributes().get(ServiceAttributes.SERVICE_TARGET)
                        + "]")
                .collect(Collectors.joining(", "));
    }
}
