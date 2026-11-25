// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.vertique.rest.core.ProblemDetail;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.annotation.security.PermitAll;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A {@link WebApplicationException} that carries a response with no entity is answered with a problem
 * response that keeps the headers the carried response declared. Covers the built-in mapping directly
 * and the whole request path to the wire.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WebApplicationExceptionHeadersIT {

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

    /** Resource whose methods throw exceptions carrying header-bearing responses without an entity. */
    @Path("/carried")
    @PermitAll
    public static class CarryingResource {

        /**
         * @return never; redirects
         */
        @GET
        @Path("/redirect")
        @Operation(operationId = "redirect")
        public Future<String> redirect() {
            throw new WebApplicationException(Response.seeOther(URI.create("https://example.test/elsewhere"))
                    .build());
        }

        /**
         * @return never; asks for credentials
         */
        @GET
        @Path("/challenge")
        @Operation(operationId = "challenge")
        public Future<String> challenge() {
            throw new WebApplicationException(Response.status(401)
                    .header("WWW-Authenticate", "Bearer realm=\"api\"")
                    .build());
        }

        /**
         * @return never; the client's copy is current
         */
        @GET
        @Path("/not-modified")
        @Operation(operationId = "notModified")
        public Future<String> notModified() {
            throw new WebApplicationException(
                    Response.status(304).header("ETag", "\"v1\"").build());
        }

        /**
         * @return never; the range cannot be served
         */
        @GET
        @Path("/range")
        @Operation(operationId = "range")
        public Future<String> range() {
            throw new WebApplicationException(
                    Response.status(416).header("Content-Range", "bytes */1234").build());
        }

        /**
         * @return never; carries an ETag on a status that is neither 304 nor 416
         */
        @GET
        @Path("/conflict")
        @Operation(operationId = "conflict")
        public Future<String> conflict() {
            throw new WebApplicationException(Response.status(409)
                    .header("ETag", "\"v1\"")
                    .header("Content-Range", "bytes 0-1/2")
                    .build());
        }

        /**
         * @return never; carries two challenges
         */
        @GET
        @Path("/two-challenges")
        @Operation(operationId = "twoChallenges")
        public Future<String> twoChallenges() {
            throw new WebApplicationException(Response.status(401)
                    .header("WWW-Authenticate", "Bearer realm=\"api\"")
                    .header("WWW-Authenticate", "Basic realm=\"api\"")
                    .build());
        }

        /**
         * @return a response with two cookies and two challenges
         */
        @GET
        @Path("/cookies")
        @Operation(operationId = "cookies")
        public Response cookies() {
            return Response.ok("done")
                    .type(MediaType.TEXT_PLAIN)
                    .header("Set-Cookie", "session=; Max-Age=0; Path=/")
                    .header("Set-Cookie", "token=abc; Path=/; HttpOnly")
                    .header("set-cookie", "theme=dark; Path=/")
                    .header("X-Tag", "one")
                    .header("X-Tag", "two")
                    .build();
        }
    }

    private Future<io.vertx.ext.web.client.HttpResponse<io.vertx.core.buffer.Buffer>> get(Vertx vertx, String path) {
        JaxRsRouterMount mount =
                TestFactories.builder().build().create("/*", "openapi.json", Set.of(new CarryingResource()));
        return mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1");
                })
                .compose(s -> {
                    server = s;
                    client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
                    return client.get(s.actualPort(), "127.0.0.1", path).send();
                });
    }

    @Test
    @DisplayName("a redirect carried by the exception keeps its Location header on the problem response")
    void redirectKeepsLocation(Vertx vertx, VertxTestContext ctx) {
        get(vertx, "/carried/redirect").onComplete(ctx.succeeding(resp -> {
            ctx.verify(() -> {
                assertEquals(303, resp.statusCode());
                assertEquals("https://example.test/elsewhere", resp.getHeader("Location"));
                assertEquals("application/problem+json", resp.getHeader("Content-Type"));
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("WWW-Authenticate on a carried 401 reaches the client")
    void challengeKeepsWwwAuthenticate(Vertx vertx, VertxTestContext ctx) {
        get(vertx, "/carried/challenge").onComplete(ctx.succeeding(resp -> {
            ctx.verify(() -> {
                assertEquals(401, resp.statusCode());
                assertEquals("Bearer realm=\"api\"", resp.getHeader("WWW-Authenticate"));
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("ETag on a carried 304 reaches the client")
    void notModifiedKeepsEtag(Vertx vertx, VertxTestContext ctx) {
        get(vertx, "/carried/not-modified").onComplete(ctx.succeeding(resp -> {
            ctx.verify(() -> {
                assertEquals(304, resp.statusCode());
                assertEquals("\"v1\"", resp.getHeader("ETag"));
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("Content-Range on a carried 416 reaches the client")
    void rangeNotSatisfiableKeepsContentRange(Vertx vertx, VertxTestContext ctx) {
        get(vertx, "/carried/range").onComplete(ctx.succeeding(resp -> {
            ctx.verify(() -> {
                assertEquals(416, resp.statusCode());
                assertEquals("bytes */1234", resp.getHeader("Content-Range"));
                assertEquals("application/problem+json", resp.getHeader("Content-Type"));
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("ETag and Content-Range on any other carried status are still dropped")
    void otherStatusesStillDropThem(Vertx vertx, VertxTestContext ctx) {
        get(vertx, "/carried/conflict").onComplete(ctx.succeeding(resp -> {
            ctx.verify(() -> {
                assertEquals(409, resp.statusCode());
                assertNull(resp.getHeader("ETag"));
                assertNull(resp.getHeader("Content-Range"));
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("every WWW-Authenticate challenge of a carried 401 reaches the client")
    void everyCarriedChallengeReachesTheClient(Vertx vertx, VertxTestContext ctx) {
        get(vertx, "/carried/two-challenges").onComplete(ctx.succeeding(resp -> {
            ctx.verify(() -> {
                assertEquals(401, resp.statusCode());
                assertEquals(
                        List.of("Bearer realm=\"api\"", "Basic realm=\"api\""),
                        resp.headers().getAll("WWW-Authenticate"));
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("every value of a repeated header of a returned response reaches the client, whatever the key's case")
    void everyValueOfARepeatedHeaderReachesTheClient(Vertx vertx, VertxTestContext ctx) {
        get(vertx, "/carried/cookies").onComplete(ctx.succeeding(resp -> {
            ctx.verify(() -> {
                assertEquals(200, resp.statusCode());
                assertEquals(
                        Set.of("session=; Max-Age=0; Path=/", "token=abc; Path=/; HttpOnly", "theme=dark; Path=/"),
                        Set.copyOf(resp.headers().getAll("Set-Cookie")));
                assertEquals(3, resp.headers().getAll("Set-Cookie").size());
                assertEquals(List.of("one", "two"), resp.headers().getAll("X-Tag"));
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("the built-in mapping copies carried headers and drops the ones that describe the carried body")
    void builtInMappingCopiesCarriedHeaders() {
        Response carried = Response.status(503)
                .header("Retry-After", "120")
                .header("Allow", "GET")
                .header("Content-Length", "5")
                .header("Content-Encoding", "gzip")
                .header("ETag", "\"abc\"")
                .type(MediaType.TEXT_PLAIN)
                .build();

        Response mapped = RestModule.defaultExceptionMapper().toResponse(new WebApplicationException(carried));

        assertEquals(503, mapped.getStatus());
        assertEquals("120", mapped.getHeaderString("Retry-After"));
        assertEquals("GET", mapped.getHeaderString("Allow"));
        assertEquals("application/problem+json", mapped.getHeaderString("Content-Type"));
        assertNull(mapped.getHeaderString("Content-Length"));
        assertNull(mapped.getHeaderString("Content-Encoding"));
        assertNull(mapped.getHeaderString("ETag"));
        assertInstanceOf(ProblemDetail.class, mapped.getEntity());
    }

    @Test
    @DisplayName("a status override drops an ETag or Content-Range the superseded response carried")
    void statusOverrideDropsTheKeptHeaders() {
        Response superseded = Response.status(500)
                .header("ETag", "\"v1\"")
                .header("Content-Range", "bytes */9")
                .build();
        for (int status : new int[] {304, 416}) {
            Response rebuilt = ErrorPipeline.rebuildWithHeaders(
                    superseded, Response.status(status).entity(ProblemDetail.of(status, null)), status, true, false);
            assertNull(rebuilt.getHeaderString("ETag"), "ETag on overridden " + status);
            assertNull(rebuilt.getHeaderString("Content-Range"), "Content-Range on overridden " + status);
        }
    }

    @Test
    @DisplayName("a carried response that has an entity is returned unchanged")
    void carriedEntityIsReturnedUnchanged() {
        Response carried =
                Response.status(409).entity("taken").type(MediaType.TEXT_PLAIN).build();

        Response mapped = RestModule.defaultExceptionMapper().toResponse(new WebApplicationException(carried));

        assertSame(carried, mapped, "an entity-bearing carried response must pass through as-is");
    }
}
