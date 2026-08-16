// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.prometheus;

import static org.junit.jupiter.api.Assertions.*;

import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.tracer.common.SpanContext;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit and integration tests for {@link PrometheusScrapeEndpoint}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Backend absent → contribute adds zero routes to the router.</li>
 *   <li>Backend present → exactly one GET route at the configured path.</li>
 *   <li>Exemplar wiring: disabled or enabled based on config + Optional presence.</li>
 *   <li>Handler behavior: 200 + default content type + meter presence; Accept: openmetrics →
 *       openmetrics content type + EOF marker; 500 failure path via renderer-factory seam.</li>
 * </ul>
 *
 * <p>The real-HTTP cases dial through a single {@link WebClient} bound to the per-test {@link Vertx}
 * instance rather than a fresh inline {@code HttpClient} per request. Two reasons: an inline client
 * is unclosable by construction, so {@code Vertx} teardown reclaims its netty pools while requests
 * may still be in flight; and a raw {@code HttpClientResponse} discards body buffers that arrive
 * before a body handler is attached, so under load {@code body()} can succeed with zero bytes while
 * the status code is correct — which every scrape assertion below, reading the rendered exposition
 * text, would surface as a content mismatch unrelated to the endpoint (issues #167, #330). A
 * {@link WebClient} aggregates the body into its {@code HttpResponse} before completing the send.
 *
 * <p>No case here answers 3xx, so {@link WebClient}'s follow-redirects default (a raw
 * {@code HttpClient} follows none) never engages.
 */
@ExtendWith(VertxExtension.class)
class PrometheusScrapeEndpointTest {

    /** The scrape client for the test that is running; recreated per test alongside {@link Vertx}. */
    private WebClient client;

    /**
     * Creates the per-test {@link WebClient} on the same {@link Vertx} instance the test bodies
     * receive, so the client never outlives the event loop it runs on.
     *
     * @param vertx the per-test Vert.x instance injected by vertx-junit5
     */
    @BeforeEach
    void createClient(Vertx vertx) {
        // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
    }

    @AfterEach
    void clearBackend() {
        PrometheusBackend.clear();
    }

    /**
     * Closes the per-test {@link WebClient} before the extension closes the {@link Vertx} instance
     * it was created on.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns
     * once the underlying client has been asked to close, so there is no future to await here.
     */
    @AfterEach
    void closeClient() {
        if (client != null) {
            client.close();
        }
    }

    // --- Backend absent ---

    @Nested
    @DisplayName("backend absent")
    class BackendAbsent {

        @Test
        @DisplayName("contribute adds zero routes when backend is empty")
        void noRoutesWhenBackendAbsent(Vertx vertx) {
            PrometheusScrapeConfig config = PrometheusScrapeConfig.builder().build();
            PrometheusScrapeEndpoint endpoint = new PrometheusScrapeEndpoint(vertx, Optional.empty(), config);

            Router router = Router.router(vertx);
            int routesBefore = router.getRoutes().size();

            endpoint.contribute(router);

            assertEquals(routesBefore, router.getRoutes().size(), "no routes must be added when backend is absent");
        }
    }

    // --- Backend present ---

    @Nested
    @DisplayName("backend present")
    class BackendPresent {

        @Test
        @DisplayName("contribute adds exactly one GET route at the configured path")
        void exactlyOneRouteAtConfiguredPath(Vertx vertx) {
            PrometheusScrapeConfig config = PrometheusScrapeConfig.builder().build();
            publishFakeRegistry();

            PrometheusScrapeEndpoint endpoint = new PrometheusScrapeEndpoint(vertx, Optional.empty(), config);
            Router router = Router.router(vertx);
            endpoint.contribute(router);

            long getRoutes = router.getRoutes().stream()
                    .filter(r -> r.methods() != null && r.methods().contains(HttpMethod.GET))
                    .filter(r -> "/metrics".equals(r.getPath()))
                    .count();
            assertEquals(1, getRoutes, "exactly one GET /metrics route must be added");
        }

        @Test
        @DisplayName("custom path is used when configured")
        void customPathUsed(Vertx vertx) {
            PrometheusScrapeConfig config =
                    PrometheusScrapeConfig.builder().path("/prom/metrics").build();
            publishFakeRegistry();

            PrometheusScrapeEndpoint endpoint = new PrometheusScrapeEndpoint(vertx, Optional.empty(), config);
            Router router = Router.router(vertx);
            endpoint.contribute(router);

            boolean hasCustomPath = router.getRoutes().stream().anyMatch(r -> "/prom/metrics".equals(r.getPath()));
            assertTrue(hasCustomPath, "custom path must appear in the router");
        }
    }

    // --- Exemplar wiring ---

    @Nested
    @DisplayName("exemplar wiring")
    class ExemplarWiring {

        @Test
        @DisplayName("exemplarsEnabled=false + optional present → DeferredSpanContext delegate stays null")
        void exemplarsDisabledDelegateStaysNull(Vertx vertx) {
            PrometheusScrapeConfig config =
                    PrometheusScrapeConfig.builder().exemplarsEnabled(false).build();
            publishFakeRegistry();

            SpanContext fakeSpanCtx = fakeSpanContext("t1", "s1");
            PrometheusScrapeEndpoint endpoint = new PrometheusScrapeEndpoint(vertx, Optional.of(fakeSpanCtx), config);
            Router router = Router.router(vertx);
            endpoint.contribute(router);

            // Verify deferred span context delegate is NOT wired
            DeferredSpanContext deferred = PrometheusBackend.spanContext()
                    .orElseThrow(() -> new AssertionError("span context must be present"));
            assertNull(deferred.getCurrentTraceId(), "delegate must stay null when exemplars disabled");
        }

        @Test
        @DisplayName("exemplarsEnabled=true + optional present → delegate is wired")
        void exemplarsEnabledDelegateWired(Vertx vertx) {
            PrometheusScrapeConfig config =
                    PrometheusScrapeConfig.builder().exemplarsEnabled(true).build();
            publishFakeRegistry();

            SpanContext fakeSpanCtx = fakeSpanContext("trace-abc", "span-def");
            PrometheusScrapeEndpoint endpoint = new PrometheusScrapeEndpoint(vertx, Optional.of(fakeSpanCtx), config);
            Router router = Router.router(vertx);
            endpoint.contribute(router);

            DeferredSpanContext deferred = PrometheusBackend.spanContext()
                    .orElseThrow(() -> new AssertionError("span context must be present"));
            assertEquals("trace-abc", deferred.getCurrentTraceId(), "delegate must be wired when exemplars enabled");
            assertEquals("span-def", deferred.getCurrentSpanId());
        }
    }

    // --- Handler behavior: real HTTP ---

    @Nested
    @DisplayName("handler behavior (real HTTP)")
    class HandlerBehavior {

        @Test
        @DisplayName("GET /metrics → 200 with Prometheus text content-type and recorded meter in body")
        void defaultContentTypeAndMeter(Vertx vertx, VertxTestContext ctx) {
            PrometheusMeterRegistry registry = newRegistry();
            registry.counter("test.counter").increment(3.0);
            PrometheusBackend.publish(registry, new DeferredSpanContext());

            PrometheusScrapeConfig config = PrometheusScrapeConfig.builder().build();
            PrometheusScrapeEndpoint endpoint = new PrometheusScrapeEndpoint(vertx, Optional.empty(), config);
            Router router = Router.router(vertx);
            endpoint.contribute(router);

            vertx.createHttpServer()
                    .requestHandler(router)
                    .listen(0, "127.0.0.1")
                    .compose(server -> client.get(server.actualPort(), "127.0.0.1", "/metrics")
                            .send()
                            .map(resp -> {
                                ctx.verify(() -> {
                                    assertEquals(200, resp.statusCode());
                                    String ct = resp.getHeader("Content-Type");
                                    assertNotNull(ct, "Content-Type must be set");
                                    assertTrue(
                                            ct.contains("text/plain"),
                                            "Content-Type must be text/plain for default format, got: " + ct);
                                    String bodyStr = String.valueOf(resp.bodyAsString());
                                    assertTrue(
                                            bodyStr.contains("test_counter_total"),
                                            "body must contain test_counter_total, got: " + bodyStr);
                                });
                                return resp;
                            }))
                    .onSuccess(ignored -> ctx.completeNow())
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("GET /metrics with Accept: application/openmetrics-text → openmetrics content-type and '# EOF'")
        void openmetricsAcceptHeader(Vertx vertx, VertxTestContext ctx) {
            PrometheusMeterRegistry registry = newRegistry();
            PrometheusBackend.publish(registry, new DeferredSpanContext());

            PrometheusScrapeConfig config = PrometheusScrapeConfig.builder().build();
            PrometheusScrapeEndpoint endpoint = new PrometheusScrapeEndpoint(vertx, Optional.empty(), config);
            Router router = Router.router(vertx);
            endpoint.contribute(router);

            vertx.createHttpServer()
                    .requestHandler(router)
                    .listen(0, "127.0.0.1")
                    .compose(server -> client.get(server.actualPort(), "127.0.0.1", "/metrics")
                            .putHeader("Accept", "application/openmetrics-text; version=1.0.0")
                            .send()
                            .map(resp -> {
                                ctx.verify(() -> {
                                    assertEquals(200, resp.statusCode());
                                    String ct = resp.getHeader("Content-Type");
                                    assertNotNull(ct);
                                    assertTrue(
                                            ct.contains("application/openmetrics-text"),
                                            "Content-Type must be openmetrics, got: " + ct);
                                    String bodyStr = String.valueOf(resp.bodyAsString());
                                    assertTrue(
                                            bodyStr.contains("# EOF"),
                                            "OpenMetrics body must end with '# EOF', body=" + bodyStr);
                                });
                                return resp;
                            }))
                    .onSuccess(ignored -> ctx.completeNow())
                    .onFailure(ctx::failNow);
        }

        @Test
        @DisplayName("renderer throws → 500 with 'metrics unavailable' and no exception details")
        void rendererThrows500Path(Vertx vertx, VertxTestContext ctx) {
            // Use the package-private renderer-factory seam to force a failure without side-effects
            PrometheusMeterRegistry registry = newRegistry();
            PrometheusBackend.publish(registry, new DeferredSpanContext());

            PrometheusScrapeConfig config = PrometheusScrapeConfig.builder().build();
            Function<String, Callable<String>> failingRenderer = contentType -> () -> {
                throw new RuntimeException("simulated scrape failure");
            };
            PrometheusScrapeEndpoint endpoint =
                    new PrometheusScrapeEndpoint(vertx, Optional.empty(), config, failingRenderer);
            Router router = Router.router(vertx);
            endpoint.contribute(router);

            vertx.createHttpServer()
                    .requestHandler(router)
                    .listen(0, "127.0.0.1")
                    .compose(server -> client.get(server.actualPort(), "127.0.0.1", "/metrics")
                            .send()
                            .map(resp -> {
                                ctx.verify(() -> {
                                    assertEquals(500, resp.statusCode());
                                    String bodyStr = String.valueOf(resp.bodyAsString());
                                    assertEquals("metrics unavailable", bodyStr);
                                    // Must NOT contain the exception message
                                    assertFalse(
                                            bodyStr.contains("simulated"),
                                            "exception details must not be in the response body");
                                });
                                return resp;
                            }))
                    .onSuccess(ignored -> ctx.completeNow())
                    .onFailure(ctx::failNow);
        }
    }

    // --- Fail-closed after backend cleared ---

    @Nested
    @DisplayName("fail-closed when backend cleared after contribute")
    class FailClosedAfterClear {

        @Test
        @DisplayName("backend present at contribute time, cleared before request → 503 with constant body, not 200")
        void backedClearedAfterContribute503(Vertx vertx, VertxTestContext ctx) {
            // Publish a registry and mount the route
            PrometheusMeterRegistry registry = newRegistry();
            PrometheusBackend.publish(registry, new DeferredSpanContext());

            PrometheusScrapeConfig config = PrometheusScrapeConfig.builder().build();
            PrometheusScrapeEndpoint endpoint = new PrometheusScrapeEndpoint(vertx, Optional.empty(), config);
            Router router = Router.router(vertx);
            endpoint.contribute(router);

            // Clear the backend BEFORE any request arrives (simulates shutdown/rollback)
            PrometheusBackend.clear();

            vertx.createHttpServer()
                    .requestHandler(router)
                    .listen(0, "127.0.0.1")
                    .compose(server -> client.get(server.actualPort(), "127.0.0.1", "/metrics")
                            .send()
                            .map(resp -> {
                                // Never null, exactly as the raw client's Buffer projection was: an
                                // aggregated WebClient body is null for an empty response, and this
                                // assertion must fail on content, not with an NPE.
                                String body = String.valueOf(resp.bodyAsString());
                                ctx.verify(() -> {
                                    // Must be 503, NOT 200 (stale registry scrape) and NOT 500 (render failure)
                                    assertEquals(503, resp.statusCode(), "cleared backend must yield 503");
                                    String ct = resp.getHeader("Content-Type");
                                    assertNotNull(ct, "Content-Type must be set");
                                    assertTrue(
                                            ct.contains("text/plain"), "Content-Type must be text/plain, got: " + ct);
                                    // Body must be the constant "metrics unavailable" — not exception text
                                    assertEquals("metrics unavailable", body.toString(), "body must be constant");
                                });
                                return resp;
                            }))
                    .onSuccess(ignored -> ctx.completeNow())
                    .onFailure(ctx::failNow);
        }
    }

    // --- Helpers ---

    /**
     * Publishes a minimal {@link PrometheusMeterRegistry} to {@link PrometheusBackend} for tests
     * that need the backend to be present but don't care about the registry contents.
     */
    private static void publishFakeRegistry() {
        PrometheusMeterRegistry r = newRegistry();
        PrometheusBackend.publish(r, new DeferredSpanContext());
    }

    /**
     * Creates a fresh {@link PrometheusMeterRegistry} with default settings.
     *
     * @return a new registry; never {@code null}
     */
    private static PrometheusMeterRegistry newRegistry() {
        return new PrometheusMeterRegistry(
                PrometheusConfig.DEFAULT,
                new PrometheusRegistry(),
                io.micrometer.core.instrument.Clock.SYSTEM,
                new DeferredSpanContext());
    }

    /**
     * Creates a fake {@link SpanContext} that returns fixed trace and span IDs.
     *
     * @param traceId the trace ID to return
     * @param spanId  the span ID to return
     * @return a fake span context
     */
    private static SpanContext fakeSpanContext(String traceId, String spanId) {
        return new SpanContext() {
            @Override
            public String getCurrentTraceId() {
                return traceId;
            }

            @Override
            public String getCurrentSpanId() {
                return spanId;
            }

            @Override
            public boolean isCurrentSpanSampled() {
                return true;
            }

            @Override
            public void markCurrentSpanAsExemplar() {}
        };
    }
}
