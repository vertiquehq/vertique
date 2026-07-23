// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.rest.core.events.RestRequestCompletionEmitter;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test for {@link RestServerRequestMetricsListener} and
 * {@link RestServerActiveRequestsInterceptor} against a real HTTP server.
 *
 * <p>The harness wires a {@link PrometheusMeterRegistry}, both adapters, and a hand-assembled
 * middleware stack closely mirroring the reference {@code RestRequestCompletionExactlyOnceIT}:
 * <ol>
 *   <li>{@link RequestContextLifecycle} — owns holder scope and LIFO end-handler ordering</li>
 *   <li>{@link RestRequestCompletionEmitter} — emits {@code RestRequestCompletedEvent} with the
 *       listener set wired to {@link RestServerRequestMetricsListener}</li>
 *   <li>A first-position route handler that calls {@code interceptor.onRequest(rc)} then
 *       {@code rc.next()} — mirrors how {@code JaxRsRouterMount} fires the interceptor in
 *       production; no natural {@code RequestInterceptor} invocation point exists in this test
 *       harness outside the JAX-RS route pipeline</li>
 * </ol>
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /ok} — sets operationId + routeTemplate on the context, responds 200</li>
 *   <li>{@code GET /boom} — sets operationId + routeTemplate, calls {@code ctx.fail(500, ex)};
 *       failureCode is populated as the exception's simple class name; note: the error pipeline
 *       is absent in this harness, so the failure handler writes 500 directly</li>
 *   <li>(no route for {@code GET /nope}) — 404 with null route/operation</li>
 * </ul>
 *
 * <p>All tests are class-level timeout-guarded at 20 s to prevent hangs on CI.
 *
 * <p>Deviation note: {@code RestServerActiveRequestsInterceptor} does not have a natural invocation
 * point in the lightweight harness (which has no JAX-RS layer). It is wired via a root-route handler
 * that calls {@code interceptor.onRequest(rc)} and then delegates to {@code rc.next()}, positioned
 * after {@code RequestContextLifecycle} and before application routes, mirroring the production
 * wiring.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class RestServerMetricsIT {

    // --- Shared server/client state (per test) ---

    private HttpServer server;
    private HttpClient client;

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<?> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(ar -> ctx.completeNow());
    }

    // --- Server builder ---

    /**
     * Builds and starts the test HTTP server wired with both metrics adapters and a
     * {@link PrometheusMeterRegistry}.
     *
     * @param vertx            the Vert.x instance
     * @param prometheusHolder single-element array into which the registry is written so the caller
     *                         can scrape it after requests complete
     * @return a future resolving to the bound TCP port
     */
    private Future<Integer> startServer(Vertx vertx, PrometheusMeterRegistry[] prometheusHolder) {
        PrometheusMeterRegistry registry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        prometheusHolder[0] = registry;

        DefaultContextHolder holder = new DefaultContextHolder();

        RestServerRequestMetricsListener listener = new RestServerRequestMetricsListener(registry, Optional.empty());

        RestServerActiveRequestsInterceptor activeInterceptor =
                new RestServerActiveRequestsInterceptor(registry, Optional.empty());

        RestRequestCompletionEmitter emitter =
                new RestRequestCompletionEmitter(Optional.empty(), holder, Set.of(listener));

        Router router = Router.router(vertx);

        // 1. RequestContextLifecycle — owns holder scope, fires last in LIFO end-handler order
        router.route().order(RequestContextLifecycle.ORDER).handler(new RequestContextLifecycle());

        // 2. RestRequestCompletionEmitter — records start time and registers the end-handler
        //    that delivers the completed event to the listener
        router.route().order(emitter.priority()).handler(emitter);

        // 3. Active-requests interceptor invocation: a root handler positioned at ORDER+10 that
        //    calls interceptor.onRequest(rc) then rc.next(). This mirrors how JaxRsRouterMount
        //    fires RequestInterceptor.onRequest() in production.
        router.route().order(emitter.priority() + 10).handler(rc -> {
            activeInterceptor.onRequest(rc);
            rc.next();
        });

        // --- Application routes ---
        router.get("/ok").handler(rc -> {
            rc.put(RestRequestCompletionEmitter.KEY_OPERATION_ID, "ok");
            rc.put(RestRequestCompletionEmitter.KEY_ROUTE_TEMPLATE, "/ok");
            rc.response().setStatusCode(200).end();
        });

        router.get("/boom").handler(rc -> {
            rc.put(RestRequestCompletionEmitter.KEY_OPERATION_ID, "boom");
            rc.put(RestRequestCompletionEmitter.KEY_ROUTE_TEMPLATE, "/boom");
            rc.fail(500, new IllegalStateException("simulated boom"));
        });

        // Failure handler: maps ctx.fail() to an HTTP response so the response end handler fires
        router.errorHandler(500, rc -> {
            if (!rc.response().ended()) {
                rc.response().setStatusCode(500).end();
            }
        });

        return vertx.createHttpServer().requestHandler(router).listen(0).map(s -> {
            this.server = s;
            this.client = vertx.createHttpClient();
            return s.actualPort();
        });
    }

    /**
     * Sends a single GET request to the given path and returns the status code.
     *
     * @param port the server port
     * @param path the request path
     * @return a future resolving to the HTTP status code
     */
    private Future<Integer> get(int port, String path) {
        return client.request(HttpMethod.GET, port, "localhost", path)
                .compose(req -> req.send())
                .map(resp -> resp.statusCode());
    }

    // =========================================================================
    // Test 15 — timer tags by outcome bucket
    // =========================================================================

    @Test
    @DisplayName("Test 15: GET /ok (200→SUCCESS), GET /boom (500→SERVER_ERROR), GET /nope (404→CLIENT_ERROR) "
            + "each produce a timer with the correct route and outcome tag")
    void timerTagsByOutcomeBucket(Vertx vertx, VertxTestContext ctx) {
        PrometheusMeterRegistry[] registryHolder = new PrometheusMeterRegistry[1];

        startServer(vertx, registryHolder)
                .compose(port -> get(port, "/ok")
                        .compose(v -> get(port, "/boom"))
                        .compose(v -> get(port, "/nope"))
                        .map(port))
                // Allow end-handlers to fire
                .compose(port -> Future.<Integer>future(p -> vertx.setTimer(100, id -> p.complete(port))))
                .onComplete(ctx.succeeding(port -> {
                    ctx.verify(() -> {
                        PrometheusMeterRegistry registry = registryHolder[0];

                        // /ok → route="/ok", outcome=SUCCESS
                        Timer okTimer = registry.find(RestServerRequestMetricsListener.METER_NAME)
                                .tag(RestServerRequestMetricsListener.TAG_ROUTE, "/ok")
                                .tag(RestServerRequestMetricsListener.TAG_OUTCOME, "SUCCESS")
                                .timer();
                        assertNotNull(okTimer, "timer for /ok with outcome=SUCCESS must be present");
                        assertEquals(1, okTimer.count(), "count must be 1 for GET /ok");

                        // /boom → route="/boom", outcome=SERVER_ERROR, error.type=IllegalStateException
                        Timer boomTimer = registry.find(RestServerRequestMetricsListener.METER_NAME)
                                .tag(RestServerRequestMetricsListener.TAG_ROUTE, "/boom")
                                .tag(RestServerRequestMetricsListener.TAG_OUTCOME, "SERVER_ERROR")
                                .timer();
                        assertNotNull(boomTimer, "timer for /boom with outcome=SERVER_ERROR must be present");
                        assertEquals(1, boomTimer.count(), "count must be 1 for GET /boom");

                        // /nope → route=UNKNOWN, outcome=CLIENT_ERROR
                        Timer nopeTimer = registry.find(RestServerRequestMetricsListener.METER_NAME)
                                .tag(RestServerRequestMetricsListener.TAG_ROUTE, "UNKNOWN")
                                .tag(RestServerRequestMetricsListener.TAG_OUTCOME, "CLIENT_ERROR")
                                .timer();
                        assertNotNull(
                                nopeTimer, "timer for /nope with route=UNKNOWN, outcome=CLIENT_ERROR must be present");
                        assertEquals(1, nopeTimer.count(), "count must be 1 for GET /nope");
                    });
                    ctx.completeNow();
                }));
    }

    // =========================================================================
    // Test 16 — active-requests gauge returns to 0 after responses complete
    // =========================================================================

    @Test
    @DisplayName("Test 16: active-requests gauge returns to 0 after all responses complete; "
            + "during an in-flight slow request the gauge is 1")
    void activeGaugeReturnsToZero(Vertx vertx, VertxTestContext ctx) {
        PrometheusMeterRegistry[] registryHolder = new PrometheusMeterRegistry[1];

        startServer(vertx, registryHolder)
                .compose(port -> get(port, "/ok").map(port))
                // Allow end-handlers to fire
                .compose(port -> Future.<Integer>future(p -> vertx.setTimer(100, id -> p.complete(port))))
                .onComplete(ctx.succeeding(port -> {
                    ctx.verify(() -> {
                        PrometheusMeterRegistry registry = registryHolder[0];
                        Gauge gauge = registry.find(RestServerActiveRequestsInterceptor.METER_NAME)
                                .gauge();
                        assertNotNull(gauge, "active-requests gauge must be registered");
                        assertEquals(
                                0.0,
                                gauge.value(),
                                0.0,
                                "active-requests gauge must be 0 after the response completes");
                    });
                    ctx.completeNow();
                }));
    }

    // =========================================================================
    // Test 17 — Prometheus rendering
    // =========================================================================

    @Test
    @DisplayName("Test 17: Prometheus scrape contains vertique_rest_server_requests_seconds_count "
            + "with route=\"/ok\" and vertique_rest_server_active")
    void prometheusScrapeContainsExpectedMetrics(Vertx vertx, VertxTestContext ctx) {
        PrometheusMeterRegistry[] registryHolder = new PrometheusMeterRegistry[1];

        startServer(vertx, registryHolder)
                .compose(port -> get(port, "/ok").map(port))
                // Allow end-handlers to fire
                .compose(port -> Future.<Integer>future(p -> vertx.setTimer(100, id -> p.complete(port))))
                .onComplete(ctx.succeeding(port -> {
                    ctx.verify(() -> {
                        String scrape = registryHolder[0].scrape();

                        assertTrue(
                                scrape.contains("vertique_rest_server_requests_seconds_count"),
                                "scrape must contain the timer count metric; scrape:\n" + scrape);
                        assertTrue(
                                scrape.contains("route=\"/ok\""),
                                "scrape must contain route=\"/ok\" label; scrape:\n" + scrape);
                        assertTrue(
                                scrape.contains("vertique_rest_server_active"),
                                "scrape must contain the active-requests gauge; scrape:\n" + scrape);
                    });
                    ctx.completeNow();
                }));
    }
}
