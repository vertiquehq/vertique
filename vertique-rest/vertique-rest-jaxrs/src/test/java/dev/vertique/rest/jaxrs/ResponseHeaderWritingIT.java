// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.rest.core.interceptor.RequestInterceptor;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.HttpResponse;
import io.vertx.ext.web.client.WebClient;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * How the response writer puts a response's headers on the wire: the first value of a header name
 * replaces what an earlier handler wrote, and every further value is sent too. A header that holds
 * two values therefore goes out as two header lines, singleton headers such as {@code Content-Type}
 * included; the tests pin that as the contract so a transform hook that re-adds a header with
 * {@code header(...)} instead of replacing it is visible.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ResponseHeaderWritingIT {

    private HttpServer server;
    private WebClient client;

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ar -> ctx.completeNow());
    }

    /** Resource whose responses the tests rewrite or observe. */
    @Path("/headers")
    @PermitAll
    public static class HeaderResource {

        /**
         * @return a response with a header an earlier handler already wrote
         */
        @GET
        @Path("/early")
        @Operation(operationId = "early")
        public Response early() {
            return Response.ok("body")
                    .type(MediaType.TEXT_PLAIN)
                    .header("X-Early", "fresh")
                    .build();
        }

        /**
         * @return a text response the transform hooks rewrite
         */
        @GET
        @Path("/text")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "text")
        public String text() {
            return "text";
        }

        /**
         * @return an entity whose response already says it is JSON
         */
        @GET
        @Path("/json")
        @Produces("application/json")
        @Operation(operationId = "json")
        public Response json() {
            return Response.ok("{}").type("application/json").build();
        }

        /**
         * @return a negotiated response
         */
        @GET
        @Path("/negotiated")
        @Produces("application/json")
        @Operation(operationId = "negotiated")
        public String negotiated() {
            return "negotiated";
        }
    }

    /** Re-adds the Content-Type with {@code header(...)}, which appends. */
    private static final RequestInterceptor APPENDS_CONTENT_TYPE = new RequestInterceptor() {
        @Override
        public Future<Response> transformResponse(RoutingContext rc, Response response) {
            return Future.succeededFuture(Response.fromResponse(response)
                    .header("Content-Type", "application/json")
                    .build());
        }
    };

    /** Replaces the Content-Type with {@code type(...)}. */
    private static final RequestInterceptor REPLACES_CONTENT_TYPE = new RequestInterceptor() {
        @Override
        public Future<Response> transformResponse(RoutingContext rc, Response response) {
            return Future.succeededFuture(Response.fromResponse(response)
                    .type("application/json")
                    .entity(Buffer.buffer("{}"))
                    .build());
        }
    };

    private Future<HttpResponse<Buffer>> get(
            Vertx vertx, Set<RequestInterceptor> interceptors, String path, String earlyHeader) {
        JaxRsRouterMount mount = TestFactories.builder()
                .requestInterceptors(interceptors)
                .build()
                .create("/*", "openapi.json", Set.of(new HeaderResource()));
        return mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    if (earlyHeader != null) {
                        root.route().handler(rc -> {
                            rc.response().headers().add("X-Early", earlyHeader);
                            rc.next();
                        });
                    }
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1");
                })
                .compose(s -> {
                    server = s;
                    client = WebClient.create(vertx);
                    return client.get(s.actualPort(), "127.0.0.1", path).send();
                });
    }

    @Test
    @DisplayName("the first value of a header replaces what an earlier handler wrote under that name")
    void firstValueReplacesAnEarlierHandlersValue(Vertx vertx, VertxTestContext ctx) {
        get(vertx, Set.of(), "/headers/early", "stale").onComplete(ctx.succeeding(resp -> {
            ctx.verify(() -> assertEquals(List.of("fresh"), resp.headers().getAll("X-Early")));
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("a header the response does not carry is left as the earlier handler wrote it")
    void headerTheResponseDoesNotCarryIsKept(Vertx vertx, VertxTestContext ctx) {
        get(vertx, Set.of(), "/headers/text", "earlier").onComplete(ctx.succeeding(resp -> {
            ctx.verify(() -> assertEquals(List.of("earlier"), resp.headers().getAll("X-Early")));
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("Vary: Accept from negotiation is sent once next to the response's own headers")
    void negotiationVarySentOnce(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount mount =
                TestFactories.builder().build().create("/*", "openapi.json", Set.of(new HeaderResource()));
        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1");
                })
                .compose(s -> {
                    server = s;
                    client = WebClient.create(vertx);
                    return client.get(s.actualPort(), "127.0.0.1", "/headers/negotiated")
                            .putHeader("Accept", "application/json")
                            .send();
                })
                .onComplete(ctx.succeeding(resp -> {
                    ctx.verify(
                            () -> assertEquals(List.of("Accept"), resp.headers().getAll("Vary")));
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("a transform hook that re-adds Content-Type with header(...) sends the header once per value")
    void appendedContentTypeIsSentOncePerValue(Vertx vertx, VertxTestContext ctx) {
        get(vertx, Set.of(APPENDS_CONTENT_TYPE), "/headers/text", null).onComplete(ctx.succeeding(resp -> {
            ctx.verify(() -> assertEquals(
                    List.of("text/plain", "application/json"), resp.headers().getAll("Content-Type")));
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("re-adding the Content-Type a response already has sends that line twice")
    void readdedSameContentTypeIsSentTwice(Vertx vertx, VertxTestContext ctx) {
        get(vertx, Set.of(APPENDS_CONTENT_TYPE), "/headers/json", null).onComplete(ctx.succeeding(resp -> {
            ctx.verify(() -> assertEquals(
                    List.of("application/json", "application/json"),
                    resp.headers().getAll("Content-Type")));
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("a transform hook that replaces Content-Type with type(...) sends it once")
    void replacedContentTypeIsSentOnce(Vertx vertx, VertxTestContext ctx) {
        get(vertx, Set.of(REPLACES_CONTENT_TYPE), "/headers/text", null).onComplete(ctx.succeeding(resp -> {
            ctx.verify(() ->
                    assertEquals(List.of("application/json"), resp.headers().getAll("Content-Type")));
            ctx.completeNow();
        }));
    }
}
