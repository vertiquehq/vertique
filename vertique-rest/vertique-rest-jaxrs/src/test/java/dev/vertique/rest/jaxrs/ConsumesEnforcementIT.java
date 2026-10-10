// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
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
 *   <li>POST with mismatched Content-Type → the per-route handler's authored {@code detail} (actual and
 *       expected content types) survives the error pipeline.</li>
 *   <li>POST with an unaccepted Content-Type against the broad
 *       {@link dev.vertique.rest.core.middleware.ContentTypeValidationMiddleware} → its authored
 *       {@code detail} survives the error pipeline.</li>
 * </ol>
 *
 * <p>The last two cases pin the <em>equal-status</em> arm of the Vert.x failure-status fallback. Both 415
 * producers call {@code ctx.fail(415, new NotSupportedException(authoredResponse))}, so the Vert.x
 * failure status and the mapped status agree. Equal-status sanitization drops a {@code detail}
 * synthesized from {@code ex.getMessage()}, but an entity already present on the
 * {@link jakarta.ws.rs.WebApplicationException}'s {@link jakarta.ws.rs.core.Response} is deliberate
 * client output and must survive — that is how these producers keep naming the offending content type.
 *
 * <p>Requests are issued through a {@link WebClient} rather than a raw {@code HttpClient} deliberately:
 * a raw {@code HttpClientResponse} discards body buffers that arrive before a body handler is attached,
 * so under load {@code body()} can succeed with zero bytes while the status code is correct (issue
 * #167). Every 415 case here asserts on the {@code problem+json} body the error pipeline produced — a
 * silently emptied body would fail them for a reason unrelated to {@code @Consumes} enforcement. A
 * {@link WebClient} aggregates the body into its {@code HttpResponse} before completing the send.
 *
 * <p>Media types stay exactly what each case intends: every request body goes out via
 * {@code sendBuffer}, which — unlike {@code sendJson}/{@code sendForm} — sets no {@code Content-Type} of
 * its own, so the deliberately Content-Type-less case below still reaches the server with no
 * {@code Content-Type} header at all.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ConsumesEnforcementIT {

    private HttpServer server;
    private WebClient client;

    // --- Teardown ---

    /**
     * Closes the {@link WebClient} and then the server started by the test that just ran.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is no future to join here and the server
     * close alone carries the completion.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
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

    /** Body type for a route that declares no {@code @Consumes}. */
    public static class Payload {
        public String name;
    }

    /** Resource with a body parameter and NO {@code @Consumes}: the decoder lookup decides. */
    @Path("/pojo")
    public static class PojoBodyResource {

        /**
         * Accepts a decoded body and echoes {@code ok}.
         *
         * @param payload the decoded body
         * @return the literal string {@code ok}
         */
        @POST
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "pojoPost")
        public String post(Payload payload) {
            return "ok";
        }
    }

    /** Resource whose only declared type is not a media type, so it can match nothing. */
    @Path("/typo")
    public static class MalformedConsumesResource {

        /**
         * Declares a wildcard type with a concrete subtype.
         *
         * @return the literal string {@code ok}
         */
        @POST
        @Consumes("*/json")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "typoPost")
        public String post() {
            return "ok";
        }
    }

    /** Resource declaring a wildcard subtype, which stays legal on the declared side. */
    @Path("/wild")
    public static class ApplicationWildcardResource {

        /**
         * Accepts any {@code application/*} body and echoes {@code ok}.
         *
         * @return the literal string {@code ok}
         */
        @POST
        @Consumes("application/*")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "wildcardEcho")
        public String echo() {
            return "ok";
        }
    }

    // --- Test 1: Mismatched Content-Type against @Consumes operation returns 415 ---

    @Test
    @DisplayName("PostWithMismatchedContentTypeReturns415 — @Consumes('application/json'), request 'text/xml' → 415")
    void postWithMismatchedContentTypeReturns415(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, ctx, Set.of(new JsonOnlyResource()), (port, c) -> {
            c.post(port, "127.0.0.1", "/echo")
                    .putHeader("Content-Type", "text/xml")
                    .sendBuffer(Buffer.buffer("hello"))
                    .onComplete(ctx.succeeding(resp -> {
                        String body = String.valueOf(resp.bodyAsString());
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
                                    body.contains("415"),
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
            c.post(port, "127.0.0.1", "/echo")
                    .putHeader("Content-Type", "application/json")
                    .sendBuffer(Buffer.buffer("{}"))
                    .map(resp -> resp.statusCode() + "|" + String.valueOf(resp.bodyAsString()))
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
            c.post(port, "127.0.0.1", "/open")
                    .putHeader("Content-Type", "application/cbor")
                    .sendBuffer(Buffer.buffer("{}"))
                    .map(resp -> resp.statusCode() + "|" + String.valueOf(resp.bodyAsString()))
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
            c.post(port, "127.0.0.1", "/echo")
                    // No Content-Type header set — sendBuffer adds none of its own (unlike sendJson),
                    // so the request still reaches the server with no Content-Type at all.
                    .sendBuffer(Buffer.buffer("hello"))
                    .onComplete(ctx.succeeding(resp -> {
                        String body = String.valueOf(resp.bodyAsString());
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
                                    body.contains("415"),
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
        // No middleware is mounted here, so this proves only that the per-route handler adds no 415
        // of its own for an operation without @Consumes when the request has no Content-Type.
        deploy(vertx, ctx, Set.of(new NoConsumesResource()), (port, c) -> {
            c.post(port, "127.0.0.1", "/open")
                    // No Content-Type header and no body
                    .send()
                    .map(resp -> resp.statusCode() + "|" + String.valueOf(resp.bodyAsString()))
                    .onComplete(ctx.succeeding(result -> {
                        ctx.verify(() ->
                                assertEquals("200|ok", result, "no-consumes op must not 415 via per-route check"));
                        ctx.completeNow();
                    }));
        });
    }

    // --- Wildcard request Content-Type never satisfies a declared @Consumes ---

    @Test
    @DisplayName("WildcardRequestContentTypeIsRejected — a full-wildcard Content-Type does not satisfy @Consumes → 415")
    void fullWildcardRequestContentTypeIsRejected(Vertx vertx, VertxTestContext ctx) {
        assertPostStatus(vertx, ctx, new JsonOnlyResource(), "/echo", "*/*", 415);
    }

    @Test
    @DisplayName("WildcardSubtypeRequestContentTypeIsRejected — 'application/*' as Content-Type → 415")
    void wildcardSubtypeRequestContentTypeIsRejected(Vertx vertx, VertxTestContext ctx) {
        assertPostStatus(vertx, ctx, new JsonOnlyResource(), "/echo", "application/*", 415);
    }

    @Test
    @DisplayName("WildcardTypeConcreteSubtypeRequestContentTypeIsRejected — '*/json' as Content-Type → 415")
    void wildcardTypeConcreteSubtypeRequestContentTypeIsRejected(Vertx vertx, VertxTestContext ctx) {
        assertPostStatus(vertx, ctx, new JsonOnlyResource(), "/echo", "*/json", 415);
    }

    @Test
    @DisplayName("DeclaredWildcardAcceptsConcreteContentType — @Consumes('application/*'), request JSON → 200")
    void declaredWildcardAcceptsConcreteContentType(Vertx vertx, VertxTestContext ctx) {
        assertPostStatus(vertx, ctx, new ApplicationWildcardResource(), "/wild", "application/json", 200);
    }

    @Test
    @DisplayName("DeclaredWildcardRejectsOtherType — @Consumes('application/*'), request 'text/plain' → 415")
    void declaredWildcardRejectsOtherType(Vertx vertx, VertxTestContext ctx) {
        assertPostStatus(vertx, ctx, new ApplicationWildcardResource(), "/wild", "text/plain", 415);
    }

    @Test
    @DisplayName("DeclaredWildcardRejectsWildcardRequest — @Consumes('application/*'), request 'application/*' → 415")
    void declaredWildcardRejectsWildcardRequest(Vertx vertx, VertxTestContext ctx) {
        assertPostStatus(vertx, ctx, new ApplicationWildcardResource(), "/wild", "application/*", 415);
    }

    // --- No request bytes in a 415 raised after the gate ---

    @Test
    @DisplayName("NoDecoderFor415DoesNotEchoTheContentType — no @Consumes, unknown type → 415 without the header value")
    void noDecoderMatchRejectionDoesNotEchoTheContentType(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, ctx, Set.of(new PojoBodyResource()), (port, c) -> c.post(port, "127.0.0.1", "/pojo")
                .putHeader("Content-Type", "application/x-q; note=leaky-marker")
                .sendBuffer(Buffer.buffer("{}"))
                .onComplete(ctx.succeeding(resp -> {
                    ctx.verify(() -> {
                        assertEquals(415, resp.statusCode(), "no decoder claims the type: " + resp.bodyAsString());
                        assertFalse(
                                String.valueOf(resp.bodyAsString()).contains("leaky-marker"),
                                "the 415 body must not echo the Content-Type: " + resp.bodyAsString());
                    });
                    ctx.completeNow();
                })));
    }

    // --- A declared type that is not a media type is diagnosed, not silently dropped ---

    @Test
    @DisplayName("MalformedDeclaredConsumesIsWarnedAndRejectsEveryBody — '*/json' declared → WARN at startup, 415")
    void malformedDeclaredConsumesIsWarnedAndRejectsEveryBody(Vertx vertx, VertxTestContext ctx) {
        ch.qos.logback.classic.Logger registrarLog =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(JaxRsRouteRegistrar.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        registrarLog.addAppender(appender);
        deploy(vertx, ctx, Set.of(new MalformedConsumesResource()), (port, c) -> {
            registrarLog.detachAppender(appender);
            c.post(port, "127.0.0.1", "/typo")
                    .putHeader("Content-Type", "application/json")
                    .sendBuffer(Buffer.buffer("{}"))
                    .onComplete(ctx.succeeding(resp -> {
                        ctx.verify(() -> {
                            assertTrue(
                                    appender.list.stream()
                                            .anyMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.WARN
                                                    && e.getFormattedMessage().contains("typoPost")
                                                    && e.getFormattedMessage().contains("*/json")),
                                    "a declared @Consumes that is not a media type must be warned about at startup");
                            assertEquals(
                                    415, resp.statusCode(), "a route that declares nothing usable matches nothing");
                        });
                        ctx.completeNow();
                    }));
        });
    }

    // --- HTTP/2 request without a declared length is still gated ---

    @Test
    @DisplayName("Http2RequestWithoutContentLengthIsGated — h2c POST with no length and '*/*' Content-Type → 415")
    void http2RequestWithoutContentLengthIsStillGated(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, ctx, Set.of(new JsonOnlyResource()), (port, c) -> {
            io.vertx.core.http.HttpClient h2 = vertx.createHttpClient(new io.vertx.core.http.HttpClientOptions()
                    .setProtocolVersion(io.vertx.core.http.HttpVersion.HTTP_2)
                    .setHttp2ClearTextUpgrade(false));
            h2.request(io.vertx.core.http.HttpMethod.POST, port, "127.0.0.1", "/echo")
                    .compose(req -> {
                        req.putHeader("Content-Type", "text/xml");
                        req.setChunked(true);
                        return req.send(Buffer.buffer("<a/>"));
                    })
                    .onComplete(ctx.succeeding(resp -> {
                        ctx.verify(() -> assertEquals(
                                415, resp.statusCode(), "an HTTP/2 body without a length header must be gated"));
                        h2.close();
                        ctx.completeNow();
                    }));
        });
    }

    @Test
    @DisplayName("Http2RequestWithoutContentLengthIsGatedByMiddleware — h2c POST, no length, 'image/png' → 415")
    void http2RequestWithoutContentLengthIsGatedByTheBroadMiddleware(Vertx vertx, VertxTestContext ctx) {
        deploy(
                vertx,
                ctx,
                Set.of(new NoConsumesResource()),
                Set.of(new dev.vertique.rest.core.middleware.ContentTypeValidationMiddleware()),
                (port, c) -> {
                    io.vertx.core.http.HttpClient h2 = vertx.createHttpClient(new io.vertx.core.http.HttpClientOptions()
                            .setProtocolVersion(io.vertx.core.http.HttpVersion.HTTP_2)
                            .setHttp2ClearTextUpgrade(false));
                    h2.request(io.vertx.core.http.HttpMethod.POST, port, "127.0.0.1", "/open")
                            .compose(req -> {
                                req.putHeader("Content-Type", "image/png");
                                req.setChunked(true);
                                return req.send(Buffer.buffer("png"));
                            })
                            .onComplete(ctx.succeeding(resp -> {
                                ctx.verify(() -> assertEquals(
                                        415,
                                        resp.statusCode(),
                                        "an HTTP/2 body without a length header must be validated"));
                                h2.close();
                                ctx.completeNow();
                            }));
                });
    }

    // --- Test 6 & 7: a 415 the framework authored keeps its own detail ---

    @Test
    @DisplayName(
            "PerRoute415KeepsItsAuthoredDetail — the Vert.x failure status equals the mapped status → detail kept, no echo")
    void perRoute415KeepsItsAuthoredDetail(Vertx vertx, VertxTestContext ctx) {
        // ctx.fail(415, new NotSupportedException(authoredResponse)) stores 415 as the Vert.x failure
        // status AND maps to 415. Equal-status sanitization would drop a detail synthesized from
        // ex.getMessage(); the per-route handler authors the ProblemDetail on the JAX-RS Response so
        // the diagnostic survives.
        deploy(vertx, ctx, Set.of(new JsonOnlyResource()), (port, c) -> {
            c.post(port, "127.0.0.1", "/echo")
                    .putHeader("Content-Type", "text/xml; note=leaky-marker")
                    .sendBuffer(Buffer.buffer("hello"))
                    .map(resp -> new Object[] {resp.statusCode(), String.valueOf(resp.bodyAsString())})
                    .onComplete(ctx.succeeding(pair -> {
                        String body = (String) pair[1];
                        ctx.verify(() -> {
                            assertEquals(415, (Integer) pair[0], "mismatched Content-Type must be rejected with 415");
                            String detail = new io.vertx.core.json.JsonObject(body).getString("detail");
                            assertNotNull(detail, "the per-route 415's authored detail must not be cleared: " + body);
                            assertFalse(
                                    body.contains("leaky-marker") || detail.contains("text/xml"),
                                    "the detail must not echo the request Content-Type; got: " + body);
                            assertTrue(
                                    detail.contains(MediaType.APPLICATION_JSON),
                                    "the detail must still name the expected content type; got: " + detail);
                        });
                        ctx.completeNow();
                    }));
        });
    }

    @Test
    @DisplayName("Middleware415KeepsItsAuthoredDetail — ContentTypeValidationMiddleware's detail survives the pipeline")
    void middleware415KeepsItsAuthoredDetail(Vertx vertx, VertxTestContext ctx) {
        // The same equal-status shape from the other 415 producer: the broad router-level middleware.
        deploy(
                vertx,
                ctx,
                Set.of(new NoConsumesResource()),
                Set.of(new dev.vertique.rest.core.middleware.ContentTypeValidationMiddleware()),
                (port, c) -> {
                    c.post(port, "127.0.0.1", "/open")
                            .putHeader("Content-Type", "image/png")
                            .sendBuffer(Buffer.buffer("hello"))
                            .map(resp -> new Object[] {resp.statusCode(), String.valueOf(resp.bodyAsString())})
                            .onComplete(ctx.succeeding(pair -> {
                                String body = (String) pair[1];
                                ctx.verify(() -> {
                                    assertEquals(
                                            415,
                                            (Integer) pair[0],
                                            "an unaccepted Content-Type must be rejected with 415");
                                    assertEquals(
                                            "Unsupported Content-Type",
                                            new io.vertx.core.json.JsonObject(body).getString("detail"),
                                            "the middleware's authored detail must survive to the client: " + body);
                                });
                                ctx.completeNow();
                            }));
                });
    }

    // --- Helper ---

    /**
     * Deploys {@code resource}, posts a small body with {@code contentType}, and asserts the status.
     *
     * @param vertx          the Vert.x instance
     * @param ctx            the test context
     * @param resource       the JAX-RS resource to mount
     * @param path           the request path
     * @param contentType    the {@code Content-Type} header value to send
     * @param expectedStatus the expected response status
     */
    private void assertPostStatus(
            Vertx vertx, VertxTestContext ctx, Object resource, String path, String contentType, int expectedStatus) {
        deploy(vertx, ctx, Set.of(resource), (port, c) -> c.post(port, "127.0.0.1", path)
                .putHeader("Content-Type", contentType)
                .sendBuffer(Buffer.buffer("{}"))
                .onComplete(ctx.succeeding(resp -> {
                    ctx.verify(() -> assertEquals(
                            expectedStatus, resp.statusCode(), "Content-Type '" + contentType + "' on " + path));
                    ctx.completeNow();
                })));
    }

    /**
     * Deploys the given resources under the default {@code none} validation strategy, starts an
     * HTTP server, and invokes {@code afterListen} with the bound port and the shared
     * {@link WebClient}.
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
            java.util.function.BiConsumer<Integer, WebClient> afterListen) {
        deploy(vertx, ctx, resources, Set.of(), afterListen);
    }

    /**
     * Deploys the given resources and router-level middlewares under the default {@code none}
     * validation strategy, starts an HTTP server, and invokes {@code afterListen} with the bound port
     * and the shared {@link WebClient}.
     *
     * @param vertx       the Vert.x instance
     * @param ctx         the test context
     * @param resources   the JAX-RS resources to mount
     * @param middlewares the router-level middlewares to install on the mount
     * @param afterListen callback invoked with the server port and the shared HTTP client
     */
    private void deploy(
            Vertx vertx,
            VertxTestContext ctx,
            Set<Object> resources,
            Set<dev.vertique.rest.core.middleware.Middleware> middlewares,
            java.util.function.BiConsumer<Integer, WebClient> afterListen) {
        JaxRsRouterMount.Factory factory =
                TestFactories.builder().middlewares(middlewares).build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", resources);
        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1");
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
                    client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
                    afterListen.accept(s.actualPort(), client);
                }));
    }
}
