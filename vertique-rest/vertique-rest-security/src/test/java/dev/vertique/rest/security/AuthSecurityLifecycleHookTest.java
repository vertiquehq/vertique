// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.security;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.context.ContextValues;
import dev.vertique.core.context.ContextHolder;
import dev.vertique.core.context.ContextValue;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.security.SecurityRuntime;
import dev.vertique.security.AuthenticationEvidence;
import dev.vertique.security.DefaultAuthMethod;
import dev.vertique.security.SecurityContext;
import dev.vertique.security.SecurityIdentity;
import dev.vertique.security.runtime.events.SecurityEventEmitter;
import dev.vertique.security.verification.CustomVerificationSource;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.impl.UserContextInternal;
import io.vertx.ext.web.openapi.router.OpenAPIRoute;
import io.vertx.ext.web.openapi.router.RequestExtractor;
import io.vertx.ext.web.openapi.router.RouterBuilder;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.openapi.contract.OpenAPIContract;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Tests the integration of {@link IdentityResolutionMiddleware} with the OpenAPI router handler
 * chain, replacing the deleted {@code SecurityContextMiddleware} assertions.
 *
 * <p>Verifies that when {@link IdentityResolutionMiddleware} is added to routes via
 * {@link OpenAPIRoute#addHandler}, the middleware runs AFTER auth handlers but BEFORE the
 * operation handler.
 *
 * <p>Tests demonstrate:
 * <ul>
 *   <li>Middleware added via {@code route.addHandler()} runs before operation handlers and binds
 *       the resolved {@link SecurityIdentity}.</li>
 *   <li>SecurityContext is null without middleware in the handler chain.</li>
 *   <li>API-scoped middleware on {@code apiRouter.route("/*")} does NOT run before operations.</li>
 * </ul>
 *
 * <p>{@link RequestContextLifecycle} is installed on the main router so that
 * {@link IdentityResolutionMiddleware} can register its scope closes via the lifecycle handle.
 *
 * <p>A single {@link HttpClient} is shared across all test methods via {@code @BeforeAll} to
 * avoid netty channel-pool churn under full-reactor load. Each test still creates its own
 * {@link HttpServer} (torn down in {@code @AfterEach}) because server wiring differs per test.
 */
@ExtendWith(VertxExtension.class)
class AuthSecurityLifecycleHookTest {

    // --- Class-scoped resources (shared across all @Test methods) ---

    private static Vertx vertx;
    private static HttpClient client;

    // --- Per-test resources ---

    private HttpServer server;

    /**
     * Minimal {@link SecurityRuntime} backed by an {@link AtomicReference} that mirrors any bound
     * value for convenient in-handler assertion, while delegating actual storage to
     * {@link ContextValues}.
     */
    private static class CapturingSecurityRuntime implements SecurityRuntime {

        private final AtomicReference<SecurityContext> captured = new AtomicReference<>();

        @Override
        public SecurityContext current() {
            return ContextValues.current(SecurityContext.class).orElse(null);
        }

        @Override
        public ContextHolder.Scope bindCurrent(SecurityContext ctx) {
            captured.set(ctx);
            return ContextValues.bind(SecurityContext.class, ctx);
        }

        @Override
        public jakarta.ws.rs.core.SecurityContext toJaxRs(SecurityContext ctx, boolean secure) {
            return null;
        }

        SecurityContext getCaptured() {
            return captured.get();
        }
    }

    /**
     * Creates the class-scoped {@link Vertx} instance and shared {@link HttpClient} once for
     * the entire test class. vertx-junit5 injects a class-scoped {@link Vertx} into
     * {@code @BeforeAll} and keeps it alive for all test methods.
     *
     * @param v   the class-scoped Vert.x instance injected by vertx-junit5
     * @param ctx the test context used to signal setup completion
     */
    @BeforeAll
    static void setUpClass(Vertx v, VertxTestContext ctx) {
        vertx = v;
        client = v.createHttpClient();
        ctx.completeNow();
    }

    /**
     * Closes the per-test {@link HttpServer}. The shared {@link HttpClient} is left open and
     * closed only in {@link #tearDownClass(VertxTestContext)}.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    /**
     * Closes the shared {@link HttpClient} after all tests in the class have run.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        if (client != null) {
            client.close().onComplete(ar -> ctx.completeNow());
        } else {
            ctx.completeNow();
        }
    }

    // --- Helpers ---

    private static ContextHolder emptyHolder() {
        return new ContextHolder() {
            @Override
            public <T> Optional<T> current(Class<T> type) {
                return Optional.empty();
            }

            @Override
            public <T extends ContextValue> Scope bind(Class<T> type, T value) {
                return () -> {};
            }
        };
    }

    private IdentityResolutionMiddleware buildMiddleware(CapturingSecurityRuntime runtime) {
        return new IdentityResolutionMiddleware(
                Set.of(new DefaultSecurityIdentityResolver()),
                Optional.of(new DefaultSecurityClaimMapper()),
                new SecurityEventEmitter(Set.of()),
                runtime,
                emptyHolder());
    }

    // --- Tests ---

    @Test
    @DisplayName("IdentityResolutionMiddleware added via route.addHandler() runs before operation handler")
    void middlewareViaAddHandlerRunsBeforeOperationHandler(VertxTestContext ctx) {
        CapturingSecurityRuntime capturingRuntime = new CapturingSecurityRuntime();

        JsonObject spec = minimalSpec();

        OpenAPIContract.from(vertx, spec)
                .compose(contract -> {
                    RouterBuilder routerBuilder =
                            RouterBuilder.create(vertx, contract, RequestExtractor.withBodyHandler());

                    IdentityResolutionMiddleware middleware = buildMiddleware(capturingRuntime);
                    for (OpenAPIRoute route : routerBuilder.getRoutes()) {
                        route.addHandler(middleware);
                    }

                    OpenAPIRoute route = routerBuilder.getRoute("getTest");
                    route.addHandler(rc -> {
                        SecurityContext sc = capturingRuntime.getCaptured();
                        if (sc != null) {
                            rc.response()
                                    .setStatusCode(200)
                                    .end("userId=" + sc.identity().actor().id());
                        } else {
                            rc.response().setStatusCode(500).end("SecurityContext is null!");
                        }
                    });

                    Router apiRouter = routerBuilder.createRouter();

                    Router mainRouter = Router.router(vertx);
                    mainRouter.route("/*").handler(new RequestContextLifecycle());
                    mainRouter.route("/test").handler(rc -> {
                        // Simulate auth handler stashing evidence with sub attribute
                        AuthenticationEvidence evidence = new AuthenticationEvidence(
                                DefaultAuthMethod.jwt(),
                                Optional.of("testuser"),
                                Instant.now(),
                                Optional.empty(),
                                new CustomVerificationSource("test", Map.of()),
                                Map.of("sub", "testuser"));
                        RestAuthenticationEvidence.append(rc, evidence);
                        ((UserContextInternal) rc.userContext())
                                .setUser(User.create(new JsonObject()
                                        .put("sub", "testuser")
                                        .put("iss", "test-issuer")
                                        .put("scope", "read")));
                        rc.next();
                    });
                    mainRouter.route("/*").subRouter(apiRouter);

                    return vertx.createHttpServer().requestHandler(mainRouter).listen(0);
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    client.request(HttpMethod.GET, s.actualPort(), "localhost", "/test")
                            .compose(req -> req.send())
                            .compose(resp -> {
                                assertEquals(200, resp.statusCode());
                                return resp.body();
                            })
                            .onComplete(ctx.succeeding(body -> {
                                assertEquals("userId=testuser", body.toString());
                                ctx.completeNow();
                            }));
                }));
    }

    @Test
    @DisplayName("SecurityContext should be null if middleware not added via route.addHandler()")
    void securityContextNullWithoutMiddleware(VertxTestContext ctx) {
        CapturingSecurityRuntime capturingRuntime = new CapturingSecurityRuntime();

        JsonObject spec = minimalSpec();

        OpenAPIContract.from(vertx, spec)
                .compose(contract -> {
                    RouterBuilder routerBuilder =
                            RouterBuilder.create(vertx, contract, RequestExtractor.withBodyHandler());

                    OpenAPIRoute route = routerBuilder.getRoute("getTest");
                    route.addHandler(rc -> {
                        SecurityContext sc = capturingRuntime.getCaptured();
                        if (sc == null) {
                            rc.response().setStatusCode(200).end("null-as-expected");
                        } else {
                            rc.response().setStatusCode(200).end("unexpected-non-null");
                        }
                    });

                    Router apiRouter = routerBuilder.createRouter();

                    Router mainRouter = Router.router(vertx);
                    mainRouter.route("/*").handler(new RequestContextLifecycle());
                    mainRouter.route("/test").handler(rc -> {
                        ((UserContextInternal) rc.userContext())
                                .setUser(User.create(
                                        new JsonObject().put("sub", "testuser").put("iss", "test-issuer")));
                        rc.next();
                    });
                    mainRouter.route("/*").subRouter(apiRouter);

                    return vertx.createHttpServer().requestHandler(mainRouter).listen(0);
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    client.request(HttpMethod.GET, s.actualPort(), "localhost", "/test")
                            .compose(req -> req.send())
                            .compose(resp -> resp.body())
                            .onComplete(ctx.succeeding(body -> {
                                assertEquals("null-as-expected", body.toString());
                                ctx.completeNow();
                            }));
                }));
    }

    @Test
    @DisplayName("API-scoped middleware mounted on apiRouter.route() does NOT run before operation handler")
    void apiScopedMiddlewareOnRouterRouteDoesNotRunBeforeOperation(VertxTestContext ctx) {
        CapturingSecurityRuntime capturingRuntime = new CapturingSecurityRuntime();

        JsonObject spec = minimalSpec();

        OpenAPIContract.from(vertx, spec)
                .compose(contract -> {
                    RouterBuilder routerBuilder =
                            RouterBuilder.create(vertx, contract, RequestExtractor.withBodyHandler());

                    OpenAPIRoute route = routerBuilder.getRoute("getTest");
                    route.addHandler(rc -> {
                        SecurityContext sc = capturingRuntime.getCaptured();
                        if (sc == null) {
                            rc.response().setStatusCode(200).end("middleware-did-not-run");
                        } else {
                            rc.response().setStatusCode(200).end("middleware-ran");
                        }
                    });

                    Router apiRouter = routerBuilder.createRouter();

                    // Add middleware on apiRouter AFTER createRouter() — operation routes already registered
                    IdentityResolutionMiddleware middleware = buildMiddleware(capturingRuntime);
                    apiRouter.route("/*").handler(rc -> {
                        AuthenticationEvidence evidence = new AuthenticationEvidence(
                                DefaultAuthMethod.jwt(),
                                Optional.of("testuser"),
                                Instant.now(),
                                Optional.empty(),
                                new CustomVerificationSource("test", Map.of()),
                                Map.of("sub", "testuser"));
                        RestAuthenticationEvidence.append(rc, evidence);
                        ((UserContextInternal) rc.userContext())
                                .setUser(User.create(
                                        new JsonObject().put("sub", "testuser").put("iss", "test-issuer")));
                        rc.next();
                    });
                    apiRouter.route("/*").handler(middleware);

                    Router mainRouter = Router.router(vertx);
                    mainRouter.route("/*").handler(new RequestContextLifecycle());
                    mainRouter.route("/*").subRouter(apiRouter);

                    return vertx.createHttpServer().requestHandler(mainRouter).listen(0);
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    client.request(HttpMethod.GET, s.actualPort(), "localhost", "/test")
                            .compose(req -> req.send())
                            .compose(resp -> resp.body())
                            .onComplete(ctx.succeeding(body -> {
                                assertEquals("middleware-did-not-run", body.toString());
                                ctx.completeNow();
                            }));
                }));
    }

    // --- Helper ---

    /**
     * Builds a minimal OpenAPI spec with a single {@code GET /test} endpoint.
     *
     * @return the spec as a {@link JsonObject}
     */
    private static JsonObject minimalSpec() {
        return new JsonObject()
                .put("openapi", "3.0.0")
                .put("info", new JsonObject().put("title", "Test").put("version", "1.0"))
                .put(
                        "paths",
                        new JsonObject()
                                .put(
                                        "/test",
                                        new JsonObject()
                                                .put(
                                                        "get",
                                                        new JsonObject()
                                                                .put("operationId", "getTest")
                                                                .put(
                                                                        "responses",
                                                                        new JsonObject()
                                                                                .put(
                                                                                        "200",
                                                                                        new JsonObject()
                                                                                                .put(
                                                                                                        "description",
                                                                                                        "OK"))))));
    }
}
