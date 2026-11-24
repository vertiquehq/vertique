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
    @DisplayName("a carried response that has an entity is returned unchanged")
    void carriedEntityIsReturnedUnchanged() {
        Response carried =
                Response.status(409).entity("taken").type(MediaType.TEXT_PLAIN).build();

        Response mapped = RestModule.defaultExceptionMapper().toResponse(new WebApplicationException(carried));

        assertSame(carried, mapped, "an entity-bearing carried response must pass through as-is");
    }
}
