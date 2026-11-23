// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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
 * End-to-end check that a secured static route is not shadowed by an open regex route whose
 * constraint contains a slash, or by an open route with a mixed {@code {name}.txt} segment. The
 * regex resource methods are declared first so registration in source order would favour them.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
class FilesRouteShadowingIT {

    private HttpServer server;

    /** Open regex route declared before the secured static route; the constraint contains a slash. */
    @Path("/files")
    public static class RegexFirstResource {

        /**
         * Open regex route.
         *
         * @param name the bound name
         * @return the bound name echoed back
         */
        @GET
        @Path("/{name:[^/]+}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "filesRegex")
        public String regex(@PathParam("name") String name) {
            return "regex=" + name;
        }

        /**
         * Secured static route.
         *
         * @return a constant body when authentication passes
         */
        @GET
        @Path("/static")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirement(name = "bearerAuth")
        @Operation(operationId = "filesStatic")
        public String staticFile() {
            return "static";
        }
    }

    /** Open mixed-segment route declared before the secured static route. */
    @Path("/mixed")
    public static class MixedFirstResource {

        /**
         * Open mixed-segment route.
         *
         * @param name the bound name
         * @return the bound name echoed back
         */
        @GET
        @Path("/{name}.txt")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "mixedOpen")
        public String open(@PathParam("name") String name) {
            return "open=" + name;
        }

        /**
         * Secured static route.
         *
         * @return a constant body when authentication passes
         */
        @GET
        @Path("/static.txt")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirement(name = "bearerAuth")
        @Operation(operationId = "mixedStatic")
        public String staticFile() {
            return "static";
        }
    }

    @AfterEach
    void tearDown(Vertx vertx, VertxTestContext ctx) {
        if (server == null) {
            ctx.completeNow();
            return;
        }
        server.close().onComplete(ctx.succeedingThenComplete());
    }

    private static io.vertx.core.Future<Integer> status(WebClient client, int port, String path) {
        return client.get(port, "127.0.0.1", path).send().map(r -> r.statusCode());
    }

    private static io.vertx.core.Future<String> body(WebClient client, int port, String path) {
        return client.get(port, "127.0.0.1", path).send().map(r -> r.bodyAsString());
    }

    private void run(
            Vertx vertx,
            VertxTestContext ctx,
            Object resource,
            java.util.function.Function<Integer, io.vertx.core.Future<?>> checks) {
        JaxRsRouterMount.Factory factory = TestFactories.builder()
                .securitySchemeHandlers(
                        Set.of(new AnnotationDrivenRoutingIT.RejectingAuthenticationHandler("bearerAuth")))
                .build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(resource));
        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1");
                })
                .compose(s -> {
                    server = s;
                    return checks.apply(s.actualPort());
                })
                .onComplete(ctx.succeedingThenComplete());
    }

    @Test
    @DisplayName("A secured /files/static is not shadowed by an open /files/{name:[^/]+}")
    void staticNotShadowedByRegexWithSlash(Vertx vertx, VertxTestContext ctx) {
        WebClient client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
        run(vertx, ctx, new RegexFirstResource(), port -> status(client, port, "/files/static")
                .compose(code -> {
                    ctx.verify(() -> assertEquals(401, code, "anonymous /files/static must hit the auth handler"));
                    return body(client, port, "/files/other");
                })
                .andThen(r -> ctx.verify(() -> assertEquals("regex=other", r.result()))));
    }

    @Test
    @DisplayName("A secured /mixed/static.txt is not shadowed by an open /mixed/{name}.txt")
    void staticNotShadowedByMixedSegment(Vertx vertx, VertxTestContext ctx) {
        WebClient client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
        run(vertx, ctx, new MixedFirstResource(), port -> status(client, port, "/mixed/static.txt")
                .compose(code -> {
                    ctx.verify(() -> assertEquals(401, code, "anonymous /mixed/static.txt must hit the auth handler"));
                    return body(client, port, "/mixed/other.txt");
                })
                .andThen(r -> ctx.verify(() -> assertEquals("open=other", r.result()))));
    }
}
