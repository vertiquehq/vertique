// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.auth.authentication.AuthenticationProvider;
import io.vertx.ext.web.Route;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.AuthenticationHandler;
import io.vertx.ext.web.handler.BasicAuthHandler;
import io.vertx.ext.web.handler.BodyHandler;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * STEP-1 characterization IT — NOT a behavioral test of the feature. It empirically determines, in
 * Vert.x 5.1.2, the reliable mechanism for a failure handler to recover the matched route's identity
 * for the four error origins the fix cares about:
 *
 * <ol>
 *   <li>(a) a handler that threw mid-dispatch ({@code ctx.fail(Throwable)});</li>
 *   <li>(b) an auth rejection raised by a real {@link AuthenticationHandler} ({@code 401});</li>
 *   <li>(c) a {@code @Consumes}-style 415 rejection raised by a per-route USER handler;</li>
 *   <li>(d) a pre-routing 404 where NO route matched (request to an unmounted path).</li>
 * </ol>
 *
 * <p>It probes TWO mechanisms:
 * <ul>
 *   <li><b>router-level catch-all</b> {@code router.route().failureHandler(...)} reading
 *       {@code ctx.currentRoute()} — the shape the production code uses today;</li>
 *   <li><b>per-route</b> {@code route.failureHandler(...)} reading the route's own metadata — a
 *       handler bound to the SAME matched route.</li>
 * </ul>
 *
 * <p>Each route is tagged at build time with a {@code "routeTag"} metadata value AND installs a
 * per-route failure handler that records the tag it can read from {@code ctx.currentRoute()} when it
 * fires. The router-level catch-all records what IT can recover. The test then reports, per case,
 * which mechanism recovered the tag.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class FailureHandlerRouteIdentityCharacterizationIT {

    private HttpServer server;
    private static HttpClient client;

    /** Per-request record keyed by request path. */
    private final ConcurrentHashMap<String, Observation> observations = new ConcurrentHashMap<>();

    /**
     * What the two failure handlers saw for one request.
     *
     * @param perRouteFired whether the per-route failure handler fired
     * @param perRouteTag the {@code routeTag} the per-route failure handler read via {@code currentRoute()}
     * @param catchAllFired whether the router-level catch-all failure handler fired
     * @param catchAllCurrentRouteNull whether {@code currentRoute()} was null in the catch-all
     * @param catchAllTag the {@code routeTag} the catch-all could read via {@code currentRoute()}
     */
    private record Observation(
            boolean perRouteFired,
            String perRouteTag,
            boolean catchAllFired,
            boolean catchAllCurrentRouteNull,
            String catchAllTag) {}

    /**
     * Creates the {@link HttpClient} shared across every test in this class.
     *
     * @param vertx the Vert.x instance injected by {@link VertxExtension}
     */
    @BeforeAll
    static void setUpClient(Vertx vertx) {
        client = vertx.createHttpClient();
    }

    /**
     * Closes the shared {@link HttpClient}.
     *
     * @param ctx the test context used for async teardown assertion
     */
    @AfterAll
    static void tearDownClient(VertxTestContext ctx) {
        client.close().onComplete(ar -> ctx.completeNow());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    /** An auth provider that always rejects, so the BasicAuthHandler fails the request with 401. */
    private static AuthenticationProvider alwaysReject() {
        return credentials -> Future.failedFuture(new RuntimeException("rejected"));
    }

    private void recordPerRoute(io.vertx.ext.web.RoutingContext rc) {
        String path = rc.request().path();
        Route current = rc.currentRoute();
        String tag = current != null ? (String) current.getMetadata("routeTag") : null;
        observations.merge(
                path,
                new Observation(true, tag, false, false, null),
                (existing, incoming) -> new Observation(
                        incoming.perRouteFired(),
                        incoming.perRouteTag(),
                        existing.catchAllFired(),
                        existing.catchAllCurrentRouteNull(),
                        existing.catchAllTag()));
        if (!rc.response().ended()) {
            int status = rc.statusCode() >= 400 ? rc.statusCode() : 500;
            rc.response().setStatusCode(status).end("failed");
        }
    }

    private Router buildRouter(Vertx vertx) {
        Router router = Router.router(vertx);
        router.route().order(Integer.MIN_VALUE).handler(BodyHandler.create());

        // (a) handler that throws mid-dispatch
        Route throwRoute = router.route(HttpMethod.GET, "/throw");
        throwRoute.putMetadata("routeTag", "throw-route");
        throwRoute.handler(rc -> rc.fail(new RuntimeException("boom")));
        throwRoute.failureHandler(this::recordPerRoute);

        // (b) auth rejection from a real AuthenticationHandler (401)
        Route authRoute = router.route(HttpMethod.GET, "/auth");
        authRoute.putMetadata("routeTag", "auth-route");
        AuthenticationHandler authHandler = BasicAuthHandler.create(alwaysReject());
        authRoute.handler(authHandler);
        authRoute.handler(rc -> rc.response().end("never reached"));
        authRoute.failureHandler(this::recordPerRoute);

        // (c) 415-style rejection from a per-route USER handler
        Route consumesRoute = router.route(HttpMethod.POST, "/consumes");
        consumesRoute.putMetadata("routeTag", "consumes-route");
        consumesRoute.handler(rc -> rc.fail(415, new RuntimeException("unsupported media type")));
        consumesRoute.handler(rc -> rc.response().end("never reached"));
        consumesRoute.failureHandler(this::recordPerRoute);

        // Router-level catch-all failure handler — records what IT can recover. Runs only when no
        // per-route failure handler ended the response.
        router.route().failureHandler(rc -> {
            String path = rc.request().path();
            Route current = rc.currentRoute();
            String tag = current != null ? (String) current.getMetadata("routeTag") : null;
            observations.merge(
                    path,
                    new Observation(false, null, true, current == null, tag),
                    (existing, incoming) -> new Observation(
                            existing.perRouteFired(),
                            existing.perRouteTag(),
                            incoming.catchAllFired(),
                            incoming.catchAllCurrentRouteNull(),
                            incoming.catchAllTag()));
            if (!rc.response().ended()) {
                int status = rc.statusCode() >= 400 ? rc.statusCode() : 500;
                rc.response().setStatusCode(status).end("failed");
            }
        });
        return router;
    }

    private void run(Vertx vertx, VertxTestContext ctx, HttpMethod method, String path, Runnable asserts) {
        server = null;
        Future<HttpServer> listenFuture = vertx.createHttpServer()
                .requestHandler(buildRouter(vertx))
                .listen(0, "127.0.0.1")
                .onSuccess(s -> server = s);
        listenFuture
                .compose(s -> client.request(method, s.actualPort(), "127.0.0.1", path))
                .compose(req -> req.send())
                .compose(resp -> resp.body().map(b -> b.toString()))
                .onComplete(ctx.succeeding(body -> {
                    ctx.verify(asserts::run);
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("(a) handler-throw: per-route failure handler recovers the matched route's metadata tag")
    void handlerThrow_perRoute(Vertx vertx, VertxTestContext ctx) {
        run(vertx, ctx, HttpMethod.GET, "/throw", () -> {
            Observation o = observations.get("/throw");
            System.out.println("[CHAR] /throw -> " + o);
            assertNotNull(o, "no failure handler recorded for /throw; observations=" + observations);
            assertEquals("throw-route", o.perRouteTag(), "per-route handler must recover the throwing route's tag");
        });
    }

    @Test
    @DisplayName("(b) auth rejection (401): per-route failure handler recovers the matched route's metadata tag")
    void authReject_perRoute(Vertx vertx, VertxTestContext ctx) {
        run(vertx, ctx, HttpMethod.GET, "/auth", () -> {
            Observation o = observations.get("/auth");
            System.out.println("[CHAR] /auth -> " + o);
            assertNotNull(o, "no failure handler recorded for /auth; observations=" + observations);
            assertEquals("auth-route", o.perRouteTag(), "per-route handler must recover the auth route's tag");
        });
    }

    @Test
    @DisplayName("(c) 415 rejection: per-route failure handler recovers the matched route's metadata tag")
    void consumes415_perRoute(Vertx vertx, VertxTestContext ctx) {
        run(vertx, ctx, HttpMethod.POST, "/consumes", () -> {
            Observation o = observations.get("/consumes");
            System.out.println("[CHAR] /consumes -> " + o);
            assertNotNull(o, "no failure handler recorded for /consumes; observations=" + observations);
            assertEquals("consumes-route", o.perRouteTag(), "per-route handler must recover the 415 route's tag");
        });
    }

    @Test
    @DisplayName("(d) pre-routing 404 (no route matched): only the catch-all fires, recovers no route tag")
    void noMatch404(Vertx vertx, VertxTestContext ctx) {
        run(vertx, ctx, HttpMethod.GET, "/nonexistent", () -> {
            Observation o = observations.get("/nonexistent");
            System.out.println("[CHAR] /nonexistent -> " + o);
            // The decisive facts: no PER-ROUTE failure handler fires for an unmatched path, and no
            // matched-route tag is recoverable. (o may be null when neither handler recorded anything.)
            assertEquals(
                    false,
                    o != null && o.perRouteFired(),
                    "no per-route failure handler should fire for an unmatched path");
            assertEquals(
                    null, o != null ? o.perRouteTag() : null, "an unmatched path must not recover a matched-route tag");
        });
    }

    @Test
    @DisplayName("(d2) middleware ctx.fail(404) before any op matches: catch-all fires, no matched-route tag")
    void middleware404_catchAll(Vertx vertx, VertxTestContext ctx) {
        // Mirrors the production no-method path: a catch-all /* handler fails the request with 404 before
        // any operation route matches. The catch-all /* handler IS itself a route, so currentRoute() may
        // be non-null and point at the catch-all (NOT an operation route). The key fact: the recovered tag
        // is not any operation route's tag, so the fix must treat "catch-all/no operation route" as
        // no-match => boundary default.
        Router router = Router.router(vertx);
        Route op = router.route(HttpMethod.GET, "/op");
        op.putMetadata("routeTag", "op-route");
        op.handler(rc -> rc.response().end("op"));
        op.failureHandler(this::recordPerRoute);
        // Catch-all /* that fails before any op matches (no routeTag metadata on it).
        router.route("/*").handler(rc -> rc.fail(404, new RuntimeException("no method")));
        router.route().failureHandler(rc -> {
            String path = rc.request().path();
            Route current = rc.currentRoute();
            String tag = current != null ? (String) current.getMetadata("routeTag") : null;
            observations.put(path, new Observation(false, null, true, current == null, tag));
            if (!rc.response().ended()) {
                rc.response().setStatusCode(404).end("failed");
            }
        });
        server = null;
        Future<HttpServer> listenFuture = vertx.createHttpServer()
                .requestHandler(router)
                .listen(0, "127.0.0.1")
                .onSuccess(s -> server = s);
        listenFuture
                .compose(s -> client.request(HttpMethod.GET, s.actualPort(), "127.0.0.1", "/totally-unmapped"))
                .compose(req -> req.send())
                .compose(resp -> resp.body().map(b -> b.toString()))
                .onComplete(ctx.succeeding(body -> {
                    ctx.verify(() -> {
                        Observation o = observations.get("/totally-unmapped");
                        System.out.println("[CHAR] /totally-unmapped (middleware 404) -> " + o);
                        assertEquals(
                                null,
                                o != null ? o.catchAllTag() : null,
                                "a middleware-404 before any op match must not recover an operation route's tag");
                    });
                    ctx.completeNow();
                }));
    }
}
