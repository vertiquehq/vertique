// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.junit.jupiter.api.AfterEach;
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
 * and the broad {@code ContentTypeValidationMiddleware} must count a body the body handler already
 * read.
 *
 * <p>The client is a raw Vert.x {@code HttpClient}, because {@code WebClient} always declares a
 * length for a buffer; the response continuation is attached before the request ends, so no body
 * buffer can arrive unobserved.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ConsumesEnforcementHttp2IT {

    private HttpServer server;
    private HttpClient client;

    /**
     * Closes the HTTP/2 client and then the server started by the test that just ran.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<Void> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future.join(clientClose, serverClose).onComplete(ar -> ctx.completeNow());
    }

    @Test
    @DisplayName("Http2RequestWithoutContentLengthIsGated — h2c POST, no length, 'text/xml' on a JSON route → 415")
    void routeGateSeesAnHttp2BodyWithoutContentLength(Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, ctx, Set.of(new ConsumesEnforcementIT.JsonOnlyResource()), Set.of(), (port, c) -> {
            postChunked(c, port, "/echo", "text/xml").onComplete(ctx.succeeding(status -> {
                ctx.verify(() -> assertEquals(415, status, "an HTTP/2 body without a length header must be gated"));
                ctx.completeNow();
            }));
        });
    }

    @Test
    @DisplayName("Http2RequestWithoutContentLengthIsValidated — h2c POST, no length, 'image/png' → 415 by middleware")
    void broadMiddlewareSeesAnHttp2BodyWithoutContentLength(Vertx vertx, VertxTestContext ctx) {
        deploy(
                vertx,
                ctx,
                Set.of(new ConsumesEnforcementIT.NoConsumesResource()),
                Set.of(new ContentTypeValidationMiddleware()),
                (port, c) -> postChunked(c, port, "/open", "image/png").onComplete(ctx.succeeding(status -> {
                    ctx.verify(() ->
                            assertEquals(415, status, "an HTTP/2 body without a length header must be validated"));
                    ctx.completeNow();
                })));
    }

    /**
     * Sends a small body over HTTP/2 without a declared length and returns the response status.
     * The response is observed before the request ends, so the continuation is attached first.
     */
    private static Future<Integer> postChunked(HttpClient c, int port, String path, String contentType) {
        return c.request(HttpMethod.POST, port, "127.0.0.1", path).compose(req -> {
            req.putHeader("Content-Type", contentType);
            req.setChunked(true);
            Future<Integer> status = responseStatus(req);
            req.write(Buffer.buffer("body"));
            req.end();
            return status;
        });
    }

    private static Future<Integer> responseStatus(HttpClientRequest req) {
        Future<HttpClientResponse> response = req.response();
        return response.compose(resp -> resp.body().map(body -> resp.statusCode()));
    }

    private void deploy(
            Vertx vertx,
            VertxTestContext ctx,
            Set<Object> resources,
            Set<Middleware> middlewares,
            java.util.function.BiConsumer<Integer, HttpClient> afterListen) {
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
                    client = vertx.createHttpClient(new HttpClientOptions()
                            .setProtocolVersion(HttpVersion.HTTP_2)
                            .setHttp2ClearTextUpgrade(false));
                    afterListen.accept(s.actualPort(), client);
                }));
    }
}
