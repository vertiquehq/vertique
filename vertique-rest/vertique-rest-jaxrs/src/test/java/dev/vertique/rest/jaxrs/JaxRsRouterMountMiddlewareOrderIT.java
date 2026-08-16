// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.ArrayList;
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
 * Integration test proving that {@link JaxRsRouterMount#createRouter(Vertx)} mounts
 * API-scoped {@link Middleware} instances in {@link dev.vertique.core.extension.OrderedExtension}
 * sorted order (phase first, then priority), not in raw-priority order.
 *
 * <p>The property under test: a {@code SYSTEM_FIRST} middleware with a <em>high</em> numeric
 * priority (1000) must execute <em>before</em> an {@code APPLICATION} middleware with a
 * <em>low</em> numeric priority (-1000). Under the old code, Vert.x route-order was set from
 * {@code priority()} directly, so the {@code APPLICATION} middleware's lower numeric value would
 * give it an earlier route order — a regression. The fix assigns route-order from the
 * {@link dev.vertique.core.extension.OrderedExtension#comparator()}-sorted index
 * ({@code Integer.MIN_VALUE + 100 + i}), so phase always dominates.
 *
 * <p>The test drives the real {@code JaxRsRouterMount.createRouter()} path:
 * <ol>
 *   <li>A {@link JaxRsRouterMount.Factory} is constructed with minimal stub services and the
 *       two recording test middlewares injected as the {@code Set<Middleware>}.</li>
 *   <li>The factory creates a mount against a minimal OpenAPI fixture
 *       ({@code jaxrs-middleware-order-test-openapi.json}).</li>
 *   <li>{@code createRouter()} loads the contract, runs {@link JaxRsRouteRegistrar}, builds the
 *       API router, and mounts the sorted middlewares with their index-derived route orders.</li>
 *   <li>The API router is attached to an HTTP server on port 0; a {@link WebClient} issues a
 *       {@code GET /ping} request.</li>
 *   <li>Each middleware appends its name to a shared {@code List<String>} when invoked, and then
 *       calls {@code ctx.next()} so both fire before the operation handler ends the response.</li>
 *   <li>The test asserts {@code ["SYSTEM_FIRST", "APPLICATION"]} — SYSTEM_FIRST was executed
 *       first despite its higher numeric priority.</li>
 * </ol>
 *
 * <p>See also {@link dev.vertique.rest.core.MiddlewareOrderTest} which covers the comparator
 * in isolation.
 *
 * <p>The client is a {@link WebClient} rather than a raw {@code HttpClient} deliberately: a raw
 * {@code HttpClientResponse} discards body buffers that arrive before a body handler is attached, so
 * under load a body read can succeed with zero bytes while the status code is correct (issue #167).
 * This test asserts on the server-side {@code executionLog} rather than on the body, so the raw idiom
 * is latent rather than actively broken here — but a {@link WebClient} aggregates the response before
 * completing the send, which removes the trap for whoever next adds a body assertion. The response is
 * consequently projected to its status code alone: the previous body read existed only to complete the
 * exchange, and the aggregation makes it redundant.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class JaxRsRouterMountMiddlewareOrderIT {

    // --- Test state ---

    private HttpServer server;
    private WebClient client;

    // --- Teardown ---

    /**
     * Closes any client and server created during the test.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is no future to join here and the server
     * close alone carries the completion.
     *
     * @param ctx the Vert.x test context used to signal async completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    // --- Minimal JAX-RS resource fixture ---

    /**
     * Minimal JAX-RS resource bound to the {@code getPing} operation in the test OpenAPI fixture.
     * The resource method returns a plain 200 OK, allowing middleware assertions to run cleanly.
     */
    @Path("/ping")
    public static class PingResource {

        /**
         * Handles {@code GET /ping}.
         *
         * @return a 200 OK response with no body
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "getPing")
        public Response ping() {
            return Response.ok("pong").build();
        }
    }

    // --- Recording middleware test doubles ---

    /**
     * A recording {@link Middleware} that appends its name to a shared list on each invocation,
     * then calls {@code ctx.next()} to continue the handler chain.
     */
    static final class RecordingMiddleware implements Middleware {

        private final String name;
        private final int middlewarePriority;
        private final ExtensionPhase middlewarePhase;
        private final List<String> executionLog;

        /**
         * Creates a recording middleware.
         *
         * @param name           label appended to {@code executionLog} on each invocation
         * @param phase          the phase returned by {@link #phase()}
         * @param priority       the priority returned by {@link #priority()}
         * @param executionLog   shared list to which {@code name} is appended on invocation
         */
        RecordingMiddleware(String name, ExtensionPhase phase, int priority, List<String> executionLog) {
            this.name = name;
            this.middlewarePhase = phase;
            this.middlewarePriority = priority;
            this.executionLog = executionLog;
        }

        /** {@inheritDoc} */
        @Override
        public ExtensionPhase phase() {
            return middlewarePhase;
        }

        /** {@inheritDoc} */
        @Override
        public int priority() {
            return middlewarePriority;
        }

        /** {@inheritDoc} */
        @Override
        public MiddlewareScope scope() {
            return MiddlewareScope.API;
        }

        /**
         * Records this middleware's name in the execution log, then delegates to the next handler.
         *
         * @param ctx the routing context
         */
        @Override
        public void handle(RoutingContext ctx) {
            executionLog.add(name);
            ctx.next();
        }

        /** {@inheritDoc} */
        @Override
        public String orderKey() {
            return name;
        }
    }

    // --- Test ---

    /**
     * Verifies that a {@code SYSTEM_FIRST} API middleware executes before an {@code APPLICATION}
     * API middleware even when the {@code APPLICATION} one carries a numerically lower priority
     * (which under the old priority-only route-order assignment would have produced the wrong order).
     *
     * @param vertx the Vert.x instance injected by the extension
     * @param ctx   the Vert.x test context for async assertion
     */
    @Test
    @DisplayName("SYSTEM_FIRST API middleware executes before APPLICATION middleware "
            + "even when APPLICATION has a lower numeric priority")
    void systemFirstMiddlewareExecutesBeforeApplicationMiddleware(Vertx vertx, VertxTestContext ctx) {
        List<String> executionLog = new ArrayList<>();

        // SYSTEM_FIRST with a HIGH numeric priority (1000) — under old code its route order would
        // be Integer.MIN_VALUE + 100 + 1000, placing it AFTER the APPLICATION middleware.
        RecordingMiddleware systemFirst =
                new RecordingMiddleware("SYSTEM_FIRST", ExtensionPhase.SYSTEM_FIRST, 1000, executionLog);

        // APPLICATION with a LOW numeric priority (-1000) — under old code its route order would
        // be Integer.MIN_VALUE + 100 + (-1000), placing it BEFORE the SYSTEM_FIRST middleware.
        RecordingMiddleware application =
                new RecordingMiddleware("APPLICATION", ExtensionPhase.APPLICATION, -1000, executionLog);

        JaxRsRouterMount.Factory factory = buildFactory(Set.of(systemFirst, application));

        JaxRsRouterMount mount =
                factory.create("/*", "jaxrs-middleware-order-test-openapi.json", Set.of(new PingResource()));

        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1");
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    client = WebClient.create(vertx);

                    client.get(s.actualPort(), "127.0.0.1", "/ping")
                            .send()
                            .map(resp -> resp.statusCode())
                            .onComplete(ctx.succeeding(status -> {
                                ctx.verify(() -> {
                                    assertNotNull(executionLog, "Execution log must not be null");
                                    assertEquals(
                                            2,
                                            executionLog.size(),
                                            "Both middlewares must have executed; log=" + executionLog);
                                    assertEquals(
                                            "SYSTEM_FIRST",
                                            executionLog.get(0),
                                            "SYSTEM_FIRST middleware (phase=SYSTEM_FIRST, priority=1000) must "
                                                    + "execute before APPLICATION middleware "
                                                    + "(phase=APPLICATION, priority=-1000); actual order: "
                                                    + executionLog);
                                    assertEquals(
                                            "APPLICATION",
                                            executionLog.get(1),
                                            "APPLICATION middleware must execute second; actual order: "
                                                    + executionLog);
                                });
                                ctx.completeNow();
                            }));
                }));
    }

    // --- Factory construction helpers ---

    /**
     * Builds a minimal {@link JaxRsRouterMount.Factory} with empty or no-op stubs for all
     * services except the provided middleware set.
     *
     * @param middlewares the set of middlewares to inject into the factory
     * @return a fully constructed factory
     */
    private static JaxRsRouterMount.Factory buildFactory(Set<Middleware> middlewares) {
        DefaultExceptionMapper defaultMapper = new DefaultExceptionMapper();
        ExceptionMapperRegistry registry = new ExceptionMapperRegistry(defaultMapper, Set.of());
        RestExceptionMapper restExceptionMapper = new RestExceptionMapper();
        RestContextResolution restContextResolution = new RestContextResolution(Set.of());
        DefaultResponseSerializer responseSerializer =
                new DefaultResponseSerializer(List.of(), List.of(new JsonBodyEncoder()));
        HttpConfig httpConfig = HttpConfig.builder().build();
        JaxRsConfig jaxRsConfig = JaxRsConfig.builder()
                .validationStrategy(NoneValidationStrategy.ID)
                .build();

        return new JaxRsRouterMount.Factory(
                Set.of(), // routerLifecycleHooks
                Set.of(), // operationInterceptors
                Set.of(), // errorInterceptors
                middlewares, // middlewares under test
                Set.of(), // operationHandlerContributors
                Set.of(), // securitySchemeHandlers
                Set.of(), // requestInterceptors
                restExceptionMapper,
                registry,
                Set.of(), // responseProducerBindings
                responseSerializer,
                restContextResolution,
                dev.vertique.rest.jaxrs.convert.ConversionContexts.defaultResolver(), // paramConversionResolver
                null, // securityPolicyValidator (nullable)
                Optional.empty(), // authEnabled
                List.of(), // sortedDecoders
                List.of(new JsonBodyEncoder()), // sortedEncoders — needed for response serialization
                httpConfig,
                jaxRsConfig,
                new dev.vertique.json.DefaultJsonMapperProfileRegistry(Set.of()), // jsonMapperProfileRegistry
                dev.vertique.json.JsonConfig.defaults(), // jsonConfig (global json.jsonProfile default)
                Optional.empty(), // beanValidator
                Optional.empty(), // objectProcessor
                Set.of(), // evidenceCapturers
                Optional.empty(), // actionRegistry
                Optional.empty(), // authorizer
                Set.of(), // fileContentVerifiers
                Set.of(new NoneValidationStrategy()), // validationStrategies
                Optional.empty() // operationSchemaSource
                );
    }
}
