// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.middleware.ContentTypeValidationMiddleware;
import dev.vertique.rest.core.middleware.Middleware;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpVersion;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration tests that the content-type checks see a request body on HTTP/2.
 *
 * <p>An HTTP/2 request may carry its body in DATA frames with neither {@code Content-Length} nor
 * {@code Transfer-Encoding}. A check that decides "has a body" from those two headers alone skips
 * such a request entirely, whatever its {@code Content-Type}. The per-route {@code @Consumes} gate
 * and the broad {@code ContentTypeValidationMiddleware} must count a body the body handler read,
 * and a multipart or form body the body handler decodes instead of buffering, and must let a
 * request that carries no body at all through.
 *
 * <p>The client is a raw Vert.x {@code HttpClient}, because {@code WebClient} always declares a
 * length for a buffer; the response continuation is attached before the request ends, so no body
 * buffer can arrive unobserved. One client serves the whole class.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ConsumesEnforcementHttp2IT {

    private static HttpClient client;

    private HttpServer server;

    /** The status and body of one response. */
    private record Reply(int status, String body) {}

    @BeforeAll
    static void setUpClass(Vertx vertx) {
        client = vertx.createHttpClient(
                new HttpClientOptions().setProtocolVersion(HttpVersion.HTTP_2).setHttp2ClearTextUpgrade(false));
    }

    @AfterAll
    static void tearDownClass(VertxTestContext ctx) {
        Future<Void> closed = client != null ? client.close() : Future.succeededFuture();
        closed.onComplete(ar -> ctx.completeNow());
    }

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    @Test
    @DisplayName("Http2RequestWithoutContentLengthIsGated — h2c POST, no length, 'text/xml' on a JSON route → 415")
    void routeGateSeesAnHttp2BodyWithoutContentLength(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, ctx, Set.of(new ConsumesEnforcementIT.JsonOnlyResource()), Set.of(), port -> post(
                        port, "/echo", "text/xml", "<a/>")
                .onComplete(ctx.succeeding(reply -> {
                    ctx.verify(() -> {
                        assertEquals(415, reply.status(), "an HTTP/2 body without a length must be gated");
                        assertTrue(
                                reply.body().contains("expected one of [application/json]"),
                                "the route gate must have answered: " + reply.body());
                    });
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Http2MultipartWithoutContentLengthIsGated — h2c multipart, no length, on a JSON route → 415")
    void routeGateSeesAnHttp2MultipartBodyWithoutContentLength(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, ctx, Set.of(new ConsumesEnforcementIT.JsonOnlyResource()), Set.of(), port -> post(
                        port,
                        "/echo",
                        "multipart/form-data; boundary=x",
                        "--x\r\nContent-Disposition: form-data; name=\"a\"\r\n\r\n1\r\n--x--\r\n")
                .onComplete(ctx.succeeding(reply -> {
                    ctx.verify(() -> {
                        assertEquals(
                                415,
                                reply.status(),
                                "a multipart body the body handler decodes still counts as a body");
                        assertTrue(
                                reply.body().contains("expected one of [application/json]"),
                                "the route gate must have answered: " + reply.body());
                    });
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Http2RequestWithoutContentLengthIsValidated — h2c POST, no length, 'image/png' → 415 by middleware")
    void broadMiddlewareSeesAnHttp2BodyWithoutContentLength(Vertx vertx, VertxTestContext ctx) {
        deploy(
                vertx,
                ctx,
                Set.of(new ConsumesEnforcementIT.NoConsumesResource()),
                Set.of(new ContentTypeValidationMiddleware()),
                port -> post(port, "/open", "image/png", "png").onComplete(ctx.succeeding(reply -> {
                    ctx.verify(() -> {
                        assertEquals(415, reply.status(), "an HTTP/2 body without a length must be validated");
                        assertTrue(
                                reply.body().contains("Unsupported Content-Type"),
                                "the middleware must have answered: " + reply.body());
                    });
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Http2RequestWithoutBodyPasses — h2c POST, no body and no Content-Type on a JSON route → 200")
    void routeGateLetsAnHttp2RequestWithoutABodyThrough(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, ctx, Set.of(new ConsumesEnforcementIT.JsonOnlyResource()), Set.of(), port -> post(
                        port, "/echo", null, null)
                .onComplete(ctx.succeeding(reply -> {
                    ctx.verify(() -> {
                        assertEquals(200, reply.status(), "a request with no body is not gated: " + reply.body());
                        assertEquals("ok", reply.body());
                    });
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Http2RequestWithoutBodyPassesMiddleware — h2c POST, no body and no Content-Type → 200")
    void broadMiddlewareLetsAnHttp2RequestWithoutABodyThrough(Vertx vertx, VertxTestContext ctx) {
        deploy(
                vertx,
                ctx,
                Set.of(new ConsumesEnforcementIT.NoConsumesResource()),
                Set.of(new ContentTypeValidationMiddleware()),
                port -> post(port, "/open", null, null).onComplete(ctx.succeeding(reply -> {
                    ctx.verify(() -> {
                        assertEquals(200, reply.status(), "a request with no body is not validated: " + reply.body());
                        assertEquals("ok", reply.body());
                    });
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("Http2MatchingBodyPasses — h2c POST, no length, 'application/json' on a JSON route → 200")
    void routeGateLetsAMatchingHttp2BodyThrough(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, ctx, Set.of(new ConsumesEnforcementIT.JsonOnlyResource()), Set.of(), port -> post(
                        port, "/echo", "application/json", "{}")
                .onComplete(ctx.succeeding(reply -> {
                    ctx.verify(() -> {
                        assertEquals(200, reply.status(), "a matching body must pass: " + reply.body());
                        assertEquals("ok", reply.body());
                    });
                    ctx.completeNow();
                })));
    }

    /**
     * Sends a POST over HTTP/2. A non-null {@code body} is written without a declared length; a
     * {@code null} body sends no entity at all. The response is observed before the request ends,
     * so the continuation is attached first.
     */
    private static Future<Reply> post(int port, String path, String contentType, String body) {
        return client.request(HttpMethod.POST, port, "127.0.0.1", path).compose(req -> {
            if (contentType != null) {
                req.putHeader("Content-Type", contentType);
            }
            Future<Reply> reply = reply(req);
            if (body == null) {
                req.end();
            } else {
                req.setChunked(true);
                req.write(Buffer.buffer(body));
                req.end();
            }
            return reply;
        });
    }

    private static Future<Reply> reply(HttpClientRequest req) {
        Future<HttpClientResponse> response = req.response();
        return response.compose(resp -> resp.body().map(buffer -> new Reply(resp.statusCode(), buffer.toString())));
    }

    private void deploy(
            Vertx vertx,
            VertxTestContext ctx,
            Set<Object> resources,
            Set<Middleware> middlewares,
            java.util.function.IntConsumer afterListen) {
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
                    afterListen.accept(s.actualPort());
                }));
    }
}
