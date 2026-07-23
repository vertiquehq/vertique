// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests for per-route {@code @Consumes} enforcement (REST-017 Slice 10).
 *
 * <p>Verifies that {@link JaxRsRouteRegistrar} installs a 415-check handler as the first handler
 * on each route whose operation declares a non-empty {@code @Consumes} list, and that routes with
 * no {@code @Consumes} declaration are left unchecked by the per-route handler (the broad
 * router-level {@code ContentTypeValidationMiddleware} still applies to those).
 *
 * <p>The test harness ({@link TestFactories}) wires the framework's default
 * {@link RestModule#defaultExceptionMapper()} so the 415 failures route through the real REST error
 * pipeline — exactly as they would in a production application. The 415 assertions therefore also
 * verify that the response is {@code application/problem+json} with a non-empty body produced by
 * the {@link DefaultExceptionMapper} (mapping {@link jakarta.ws.rs.WebApplicationException}), not a
 * bespoke direct-write path.
 *
 * <p>Test cases:
 * <ol>
 *   <li>POST with mismatched Content-Type against {@code @Consumes("application/json")} → 415
 *       with {@code application/problem+json} body (error pipeline).</li>
 *   <li>POST with matching Content-Type ({@code application/json}) → 200 (chain continues).</li>
 *   <li>POST with no {@code @Consumes} and exotic Content-Type → no per-route 415 (passes).</li>
 *   <li>POST with body but no Content-Type against {@code @Consumes("application/json")} → 415
 *       with {@code application/problem+json} body (error pipeline).</li>
 *   <li>POST with body but no Content-Type against no-{@code @Consumes} operation → passes (no per-route check).</li>
 * </ol>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ConsumesEnforcementIT {

    private HttpServer server;
    private HttpClient client;

    // --- Teardown ---

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<?> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(ar -> ctx.completeNow());
    }

    // --- Resource fixtures ---

    /**
     * Resource declaring {@code @Consumes("application/json")} — used to prove per-route
     * 415 enforcement on mismatch and pass-through on match.
     */
    @Path("/echo")
    public static class JsonOnlyResource {

        /**
         * Accepts a JSON body and echoes {@code ok}.
         *
         * @return the literal string {@code ok}
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "jsonEcho")
        public String echo() {
            return "ok";
        }
    }

    /**
     * Resource with NO {@code @Consumes} declaration — per-route 415 check must NOT be installed
     * for this operation.
     */
    @Path("/open")
    public static class NoConsumesResource {

        /**
         * Accepts any body without content-type restriction.
         *
         * @return the literal string {@code ok}
         */
        @POST
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "openPost")
        public String post() {
            return "ok";
        }
    }

    // --- Test 1: Mismatched Content-Type against @Consumes operation returns 415 ---

    @Test
    @DisplayName("PostWithMismatchedContentTypeReturns415 — @Consumes('application/json'), request 'text/xml' → 415")
    void postWithMismatchedContentTypeReturns415(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, ctx, Set.of(new JsonOnlyResource()), (port, c) -> {
            c.request(HttpMethod.POST, port, "localhost", "/echo")
                    .compose(req -> req.putHeader("Content-Type", "text/xml")
                            .putHeader("Content-Length", "5")
                            .send("hello"))
                    .compose(resp -> resp.body().map(body -> new Object[] {resp, body.toString()}))
                    .onComplete(ctx.succeeding(pair -> {
                        io.vertx.core.http.HttpClientResponse resp = (io.vertx.core.http.HttpClientResponse) pair[0];
                        String body = (String) pair[1];
                        ctx.verify(() -> {
                            assertEquals(415, resp.statusCode(), "mismatched Content-Type must be rejected with 415");
                            // The 415 must be produced by the error pipeline, not a bespoke direct-write path.
                            // Assert canonical application/problem+json shape from DefaultExceptionMapper.
                            String ct = resp.getHeader("Content-Type");
                            assertNotNull(ct, "error response must have Content-Type");
                            assertTrue(
                                    ct.startsWith("application/problem+json"),
                                    "415 Content-Type must be application/problem+json but was: " + ct);
                            assertTrue(
                                    body != null && body.contains("415"),
                                    "415 body must be a non-empty problem+json with status; got: " + body);
                        });
                        ctx.completeNow();
                    }));
        });
    }

    // --- Test 2: Matching Content-Type passes through ---

    @Test
    @DisplayName("PostWithMatchingContentTypePasses — @Consumes('application/json'), request 'application/json' → 200")
    void postWithMatchingContentTypePasses(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, ctx, Set.of(new JsonOnlyResource()), (port, c) -> {
            c.request(HttpMethod.POST, port, "localhost", "/echo")
                    .compose(req -> req.putHeader("Content-Type", "application/json")
                            .putHeader("Content-Length", "2")
                            .send("{}"))
                    .compose(resp -> resp.body().map(b -> resp.statusCode() + "|" + b.toString()))
                    .onComplete(ctx.succeeding(result -> {
                        ctx.verify(() -> assertEquals("200|ok", result, "matching Content-Type must pass through"));
                        ctx.completeNow();
                    }));
        });
    }

    // --- Test 3: No @Consumes — exotic Content-Type passes (no per-route check installed) ---

    @Test
    @DisplayName(
            "OperationWithNoConsumesAcceptsAnything — no @Consumes, Content-Type: application/cbor → 200 (no per-route 415)")
    void operationWithNoConsumesAcceptsAnything(Vertx vertx, VertxTestContext ctx) {
        // Send a JSON-compatible body so DefaultBoundRequest.bindBody() does not throw a parse
        // error on the body content (the body has no param binding, but the body is eagerly parsed).
        // The purpose of this test is solely to assert no per-route 415 is added for no-@Consumes ops.
        deploy(vertx, ctx, Set.of(new NoConsumesResource()), (port, c) -> {
            c.request(HttpMethod.POST, port, "localhost", "/open")
                    .compose(req -> req.putHeader("Content-Type", "application/cbor")
                            .putHeader("Content-Length", "2")
                            .send("{}"))
                    .compose(resp -> resp.body().map(b -> resp.statusCode() + "|" + b.toString()))
                    .onComplete(ctx.succeeding(result -> {
                        ctx.verify(() ->
                                assertEquals("200|ok", result, "no-consumes op must not 415 via per-route check"));
                        ctx.completeNow();
                    }));
        });
    }

    // --- Test 4: Body with no Content-Type against @Consumes operation → 415 ---

    @Test
    @DisplayName(
            "RequestWithBodyAndNoContentTypeAgainstConsumesOperation — @Consumes present, no Content-Type header → 415")
    void requestWithBodyAndNoContentTypeAgainstConsumesOperation(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, ctx, Set.of(new JsonOnlyResource()), (port, c) -> {
            c.request(HttpMethod.POST, port, "localhost", "/echo")
                    .compose(req -> req.putHeader("Content-Length", "5")
                            // No Content-Type header set
                            .send("hello"))
                    .compose(resp -> resp.body().map(body -> new Object[] {resp, body.toString()}))
                    .onComplete(ctx.succeeding(pair -> {
                        io.vertx.core.http.HttpClientResponse resp = (io.vertx.core.http.HttpClientResponse) pair[0];
                        String body = (String) pair[1];
                        ctx.verify(() -> {
                            assertEquals(
                                    415,
                                    resp.statusCode(),
                                    "missing Content-Type with @Consumes operation must be 415");
                            // The 415 must be produced by the error pipeline — canonical problem+json shape.
                            String ct = resp.getHeader("Content-Type");
                            assertNotNull(ct, "error response must have Content-Type");
                            assertTrue(
                                    ct.startsWith("application/problem+json"),
                                    "415 Content-Type must be application/problem+json but was: " + ct);
                            assertTrue(
                                    body != null && body.contains("415"),
                                    "415 body must be a non-empty problem+json with status; got: " + body);
                        });
                        ctx.completeNow();
                    }));
        });
    }

    // --- Test 5: Body with no Content-Type against no-@Consumes operation passes ---

    @Test
    @DisplayName(
            "RequestWithBodyAndNoContentTypeAgainstNoConsumesOperation — no @Consumes, no Content-Type → passes (no per-route 415)")
    void requestWithBodyAndNoContentTypeAgainstNoConsumesOperation(Vertx vertx, VertxTestContext ctx) {
        // The broad ContentTypeValidationMiddleware will 415 if the request has a body and no
        // Content-Type — but that is the middleware's job, not the per-route handler's. We pass
        // Content-Length: 0 to have no body, so the middleware also skips validation. The test
        // proves no additional per-route 415 is added for the no-consumes operation.
        deploy(vertx, ctx, Set.of(new NoConsumesResource()), (port, c) -> {
            c.request(HttpMethod.POST, port, "localhost", "/open")
                    .compose(req -> req.putHeader("Content-Length", "0")
                            // No Content-Type header; empty body → no middleware 415 either
                            .send())
                    .compose(resp -> resp.body().map(b -> resp.statusCode() + "|" + b.toString()))
                    .onComplete(ctx.succeeding(result -> {
                        ctx.verify(() ->
                                assertEquals("200|ok", result, "no-consumes op must not 415 via per-route check"));
                        ctx.completeNow();
                    }));
        });
    }

    // --- Helper ---

    /**
     * Deploys the given resources under the default {@code none} validation strategy, starts an
     * HTTP server, and invokes {@code afterListen} with the bound port and the shared
     * {@link HttpClient}.
     *
     * @param vertx       the Vert.x instance
     * @param ctx         the test context
     * @param resources   the JAX-RS resources to mount
     * @param afterListen callback invoked with the server port and the shared HTTP client
     */
    private void deploy(
            Vertx vertx,
            VertxTestContext ctx,
            Set<Object> resources,
            java.util.function.BiConsumer<Integer, HttpClient> afterListen) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", resources);
        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0);
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    client = vertx.createHttpClient();
                    afterListen.accept(s.actualPort(), client);
                }));
    }
}
