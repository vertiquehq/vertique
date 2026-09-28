// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.micrometer.rest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.rest.core.events.RequestCompletionRecorder;
import dev.vertique.rest.core.events.RestRequestCompletionEmitter;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.routing.RestOperationDescriptor;
import dev.vertique.rest.core.routing.SecurityRequirementSet;
import dev.vertique.rest.core.security.SecurityPolicy;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusConfig;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.lang.annotation.Annotation;
import java.util.List;
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
 *   <li>A root handler, ordered after the emitter, that rejects {@code GET /limited} with
 *       {@code ctx.fail(429)} before any route matches and calls {@code rc.next()} for every other
 *       path — a stand-in for a ROOT middleware such as rate limiting; a 429 error handler ends
 *       the response</li>
 * </ol>
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET /ok} — first installs the {@code ok} operation's identity handler
 *       ({@link RequestCompletionRecorder#operationRouteHandler}), then responds 200</li>
 *   <li>{@code GET /boom} — first installs the {@code boom} operation's identity handler, then calls
 *       {@code ctx.fail(500, ex)}; failureCode is populated as the exception's simple class name;
 *       note: the error pipeline is absent in this harness, so the failure handler writes 500
 *       directly</li>
 *   <li>{@code GET /limited} — rejected at ROOT with 429; no operation route claims it</li>
 *   <li>(no route for {@code GET /nope}) — 404; no operation route claims it</li>
 * </ul>
 *
 * <p>Only a request an operation route claimed yields a {@code RestRequestCompletedEvent}, so only
 * {@code /ok} and {@code /boom} are timed; {@code /limited} and {@code /nope} yield an
 * {@code HttpRequestCompletedEvent}, which {@link RestServerRequestMetricsListener} never receives.
 *
 * <p>Requests are issued through a {@link WebClient} rather than a raw {@code HttpClient}: a raw
 * {@code HttpClientResponse} discards body buffers that arrive before a body handler is attached, so
 * under load {@code body()} can succeed with zero bytes while the status code is correct (issue
 * #167). Nothing here asserts on a body today, but the racy idiom would become a live race the
 * moment someone added a body assertion, with no diff to explain why. No route answers 3xx, so
 * {@link WebClient}'s follow-redirects default (a raw {@code HttpClient} follows none) never
 * engages.
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

    /** Test-local descriptor for {@code GET /ok}, operation {@code ok}. */
    private static final RestOperationDescriptor OK_OPERATION = new TestOperation("ok", "GET", "/ok");

    /** Test-local descriptor for {@code GET /boom}, operation {@code boom}. */
    private static final RestOperationDescriptor BOOM_OPERATION = new TestOperation("boom", "GET", "/boom");

    /** The path the root handler rejects with 429 before any route matches. */
    private static final String LIMITED_PATH = "/limited";

    /** The fallback tag value for a missing route or operation; no series may carry it. */
    private static final String UNKNOWN = "UNKNOWN";

    // --- Shared server/client state (per test) ---

    private HttpServer server;
    private WebClient client;

    /**
     * Closes the {@link WebClient} and then the server started by the test that just ran.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns
     * once the underlying client has been asked to close, so there is no future to join here and the
     * server close alone carries the completion.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
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

        // 4. ROOT rejection: a root handler ordered after the emitter (so the emitter's end handler is
        //    registered) that fails GET /limited with 429 before any operation route matches — a
        //    stand-in for a ROOT middleware such as rate limiting. Every other path continues.
        router.route().order(emitter.priority() + 20).handler(rc -> {
            if (LIMITED_PATH.equals(rc.normalizedPath())) {
                rc.fail(429);
            } else {
                rc.next();
            }
        });

        // --- Application routes: each installs its operation's identity handler first ---
        route(router, "/ok", OK_OPERATION)
                .handler(rc -> rc.response().setStatusCode(200).end());
        route(router, "/boom", BOOM_OPERATION).handler(rc -> rc.fail(500, new IllegalStateException("simulated boom")));

        // Failure handlers: map ctx.fail() to an HTTP response so the response end handler fires
        router.errorHandler(500, rc -> {
            if (!rc.response().ended()) {
                rc.response().setStatusCode(500).end();
            }
        });
        router.errorHandler(429, rc -> {
            if (!rc.response().ended()) {
                rc.response().setStatusCode(429).end();
            }
        });

        return vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .map(s -> {
                    this.server = s;
                    // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
                    this.client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
                    return s.actualPort();
                });
    }

    /**
     * Adds a route at {@code path} for {@code operation}'s HTTP method whose first handler is
     * {@link RequestCompletionRecorder#operationRouteHandler(RestOperationDescriptor)}, the handler
     * that records the operation's identity for the completion event.
     *
     * @param router    the router to add the route to
     * @param path      the Vert.x route path
     * @param operation the operation the route serves
     * @return the route, ready for its application handler
     */
    private static Route route(Router router, String path, RestOperationDescriptor operation) {
        return router.route(HttpMethod.valueOf(operation.httpMethod()), path)
                .handler(RequestCompletionRecorder.operationRouteHandler(operation));
    }

    /**
     * Sends a single GET request to the given path and returns the status code.
     *
     * @param port the server port
     * @param path the request path
     * @return a future resolving to the HTTP status code
     */
    private Future<Integer> get(int port, String path) {
        return client.get(port, "127.0.0.1", path).send().map(resp -> resp.statusCode());
    }

    /**
     * Sums the counts of every {@value RestServerRequestMetricsListener#METER_NAME} series.
     *
     * @param registry the registry to read
     * @return the total number of recorded requests across all series of the meter
     */
    private static long totalCount(PrometheusMeterRegistry registry) {
        return registry.find(RestServerRequestMetricsListener.METER_NAME).timers().stream()
                .mapToLong(Timer::count)
                .sum();
    }

    /**
     * Renders every {@value RestServerRequestMetricsListener#METER_NAME} series as its tags and
     * count, for assertion messages.
     *
     * @param registry the registry to read
     * @return one {@code [key=value, …] count=n} entry per series
     */
    private static String series(PrometheusMeterRegistry registry) {
        return registry.find(RestServerRequestMetricsListener.METER_NAME).timers().stream()
                .map(t -> t.getId().getTags().stream()
                                .map(tag -> tag.getKey() + "=" + tag.getValue())
                                .toList()
                        + " count=" + t.count())
                .toList()
                .toString();
    }

    // =========================================================================
    // Test 15 — timer tags by outcome bucket
    // =========================================================================

    @Test
    @DisplayName("Test 15: GET /ok (200→SUCCESS) and GET /boom (500→SERVER_ERROR) each produce a timer with "
            + "the correct route and outcome tag; the unmatched GET /nope (404) produces none")
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

                        // /nope → no operation route claims it, so it yields no REST event and no series
                        assertNull(
                                registry.find(RestServerRequestMetricsListener.METER_NAME)
                                        .tag(RestServerRequestMetricsListener.TAG_OUTCOME, "CLIENT_ERROR")
                                        .meter(),
                                "the unmatched GET /nope must record no series; series: " + series(registry));
                    });
                    ctx.completeNow();
                }));
    }

    // =========================================================================
    // TP-012 — a ROOT rejection and an unmatched request are not timed
    // =========================================================================

    /**
     * TP-012: {@value RestServerRequestMetricsListener#METER_NAME} records only requests an operation
     * route claimed. {@code GET /limited} is rejected with 429 by a root handler before any route
     * matches and {@code GET /nope} matches no route; neither is claimed, so each yields an
     * {@code HttpRequestCompletedEvent} and no series, while {@code GET /ok} is timed under its
     * operation's route and operation id.
     *
     * @param vertx the Vert.x instance
     * @param ctx   the test context
     */
    @Test
    @DisplayName("TP-012: GET /ok (200) is timed under route=/ok; the ROOT-rejected GET /limited (429) and the "
            + "unmatched GET /nope (404) record no series")
    void rootRejectedAndUnmatchedRequestsAreNotTimed(Vertx vertx, VertxTestContext ctx) {
        PrometheusMeterRegistry[] registryHolder = new PrometheusMeterRegistry[1];

        startServer(vertx, registryHolder)
                .compose(port -> get(port, "/ok").compose(ok -> get(port, LIMITED_PATH)
                        .compose(limited -> get(port, "/nope").map(nope -> List.of(ok, limited, nope)))))
                // Allow end-handlers to fire
                .compose(statuses -> Future.<List<Integer>>future(p -> vertx.setTimer(100, id -> p.complete(statuses))))
                .onComplete(ctx.succeeding(statuses -> {
                    ctx.verify(() -> {
                        PrometheusMeterRegistry registry = registryHolder[0];
                        String series = series(registry);

                        assertEquals(List.of(200, 429, 404), statuses, "statuses of GET /ok, /limited and /nope");

                        // Only the claimed GET /ok is timed
                        assertEquals(1, totalCount(registry), "only GET /ok must be timed; series: " + series);
                        Timer okTimer = registry.find(RestServerRequestMetricsListener.METER_NAME)
                                .tag(RestServerRequestMetricsListener.TAG_ROUTE, "/ok")
                                .tag(RestServerRequestMetricsListener.TAG_OPERATION, OK_OPERATION.operationId())
                                .timer();
                        assertNotNull(
                                okTimer,
                                "GET /ok must be timed under route=/ok and its operation id; series: " + series);
                        assertEquals(1, okTimer.count(), "count must be 1 for GET /ok; series: " + series);

                        // Neither the ROOT rejection nor the unmatched request has a series
                        assertNull(
                                registry.find(RestServerRequestMetricsListener.METER_NAME)
                                        .tag(RestServerRequestMetricsListener.TAG_STATUS, "429")
                                        .meter(),
                                "the ROOT-rejected GET /limited must record no series; series: " + series);
                        assertNull(
                                registry.find(RestServerRequestMetricsListener.METER_NAME)
                                        .tag(RestServerRequestMetricsListener.TAG_STATUS, "404")
                                        .meter(),
                                "the unmatched GET /nope must record no series; series: " + series);

                        // No series falls back to an UNKNOWN route or operation
                        assertNull(
                                registry.find(RestServerRequestMetricsListener.METER_NAME)
                                        .tag(RestServerRequestMetricsListener.TAG_ROUTE, UNKNOWN)
                                        .meter(),
                                "no series may carry route=UNKNOWN; series: " + series);
                        assertNull(
                                registry.find(RestServerRequestMetricsListener.METER_NAME)
                                        .tag(RestServerRequestMetricsListener.TAG_OPERATION, UNKNOWN)
                                        .meter(),
                                "no series may carry operation=UNKNOWN; series: " + series);
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

    // --- Test doubles ---

    /**
     * Test-local {@link RestOperationDescriptor} for an application route. Only the identity fields
     * carry values: no route here is secured or negotiates content, so the security policy is
     * {@link SecurityPolicy.None} and every collection is empty.
     *
     * @param operationId   the operation identifier
     * @param httpMethod    the HTTP method
     * @param routeTemplate the route template
     */
    private record TestOperation(String operationId, String httpMethod, String routeTemplate)
            implements RestOperationDescriptor {

        @Override
        public List<String> consumes() {
            return List.of();
        }

        @Override
        public List<String> produces() {
            return List.of();
        }

        @Override
        public SecurityPolicy securityPolicy() {
            return new SecurityPolicy.None();
        }

        @Override
        public List<SecurityRequirementSet> securityRequirementSets() {
            return List.of();
        }

        @Override
        public List<Annotation> methodAnnotations() {
            return List.of();
        }

        @Override
        public List<Annotation> classAnnotations() {
            return List.of();
        }

        @Override
        public <A extends Annotation> Optional<A> findAnnotation(Class<A> type) {
            return Optional.empty();
        }
    }
}
