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
 * End-to-end check that a secured route is not shadowed by an open route that is broader in the
 * JAX-RS ranking sense: an open regex route whose constraint contains a slash, a catch-all, a mixed
 * {@code {name}.txt} segment, or an alternation. The open resource methods are declared first so
 * registration in source order would favour them. Also pins one reversal of the literal-character
 * ranking, so a change to it shows up here.
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

    /**
     * Open mixed-segment route beside a secured static route. The static route has more literal
     * characters (11 against 4 plus the extension), so it ranks first whatever the names.
     */
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

    /** Open two-variable route beside a secured route whose constraint spans a path separator. */
    @Path("/repos")
    public static class SpanningConstraintResource {

        /**
         * Open two-variable route.
         *
         * @param owner the bound owner
         * @param name the bound name
         * @return the bound values echoed back
         */
        @GET
        @Path("/{owner:\\w+}/{name}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "reposOpen")
        public String open(@PathParam("owner") String owner, @PathParam("name") String name) {
            return "open=" + owner + "/" + name;
        }

        /**
         * Secured route whose constraint spans a path separator and ends in a literal segment.
         *
         * @param slug the bound slug
         * @return a constant body when authentication passes
         */
        @GET
        @Path("/{slug:[^/]+/admin}")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirement(name = "bearerAuth")
        @Operation(operationId = "reposAdmin")
        public String admin(@PathParam("slug") String slug) {
            return "admin";
        }
    }

    /**
     * Open route whose constraint has a top-level alternation beside a secured one-segment regex. The
     * pair {@code /alt/a} is an equal-rank pair: the order between the two routes is decided by
     * variable name today, and the equal-rank rule gives it no guarantee. The case is kept as a
     * regression check of that outcome, not as a contract.
     */
    @Path("/alt")
    public static class AlternationResource {

        /**
         * Open route matching either two segments or the single segment {@code a}.
         *
         * @param p the bound value
         * @return the bound value echoed back
         */
        @GET
        @Path("/{p:b/c|a}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "altOpen")
        public String open(@PathParam("p") String p) {
            return "open=" + p;
        }

        /**
         * Secured one-segment regex route.
         *
         * @param a the bound value
         * @return a constant body when authentication passes
         */
        @GET
        @Path("/{a:\\w+}")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirement(name = "bearerAuth")
        @Operation(operationId = "altSecured")
        public String secured(@PathParam("a") String a) {
            return "secured";
        }
    }

    /** Open catch-all declared before a secured route with a literal tail. */
    @Path("/ca")
    public static class CatchAllResource {

        /**
         * Open catch-all.
         *
         * @param p the bound value
         * @return the bound value echoed back
         */
        @GET
        @Path("/{p:.+}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "caOpen")
        public String open(@PathParam("p") String p) {
            return "open=" + p;
        }

        /**
         * Secured route with a literal tail.
         *
         * @param id the bound value
         * @return a constant body when authentication passes
         */
        @GET
        @Path("/{id}/secret")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirement(name = "bearerAuth")
        @Operation(operationId = "caSecret")
        public String secret(@PathParam("id") String id) {
            return "secret";
        }
    }

    /** Open mixed-segment route beside a secured mixed-segment route with a longer literal. */
    @Path("/mx")
    public static class MixedLiteralResource {

        /**
         * Open mixed-segment route.
         *
         * @param name the bound name
         * @return the bound name echoed back
         */
        @GET
        @Path("/{name}.txt")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "mxOpen")
        public String open(@PathParam("name") String name) {
            return "open=" + name;
        }

        /**
         * Secured route whose literal is longer than the open route's.
         *
         * @return a constant body when authentication passes
         */
        @GET
        @Path("/~admin.txt")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirement(name = "bearerAuth")
        @Operation(operationId = "mxAdmin")
        public String admin() {
            return "admin";
        }
    }

    /** Open two-variable route beside a secured alternation whose second alternative spans a separator. */
    @Path("/alt2")
    public static class AlternationVariantResource {

        /**
         * Open two-variable route.
         *
         * @param a the first bound value
         * @param b the second bound value
         * @return the bound values echoed back
         */
        @GET
        @Path("/{a:\\w+}/{b}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "alt2Open")
        public String open(@PathParam("a") String a, @PathParam("b") String b) {
            return "open=" + a + "/" + b;
        }

        /**
         * Secured alternation: the single segment {@code a}, or {@code b/admin}.
         *
         * @param p the bound value
         * @return a constant body when authentication passes
         */
        @GET
        @Path("/{p:a|b/admin}")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirement(name = "bearerAuth")
        @Operation(operationId = "alt2Secured")
        public String secured(@PathParam("p") String p) {
            return "secured";
        }
    }

    /** Open alternation with a weak alternative beside a secured static sibling. */
    @Path("/wk")
    public static class WeakAlternativeResource {

        /**
         * Open alternation: {@code b/admin}, or any single segment.
         *
         * @param p the bound value
         * @return the bound value echoed back
         */
        @GET
        @Path("/{p:b/admin|[^/]+}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "wkOpen")
        public String open(@PathParam("p") String p) {
            return "open=" + p;
        }

        /**
         * Secured static sibling.
         *
         * @return a constant body when authentication passes
         */
        @GET
        @Path("/secret")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirement(name = "bearerAuth")
        @Operation(operationId = "wkSecret")
        public String secret() {
            return "secret";
        }
    }

    /**
     * Pins a reversal of the literal-character ranking. {@code /u/{name}/profile} has 11 literal
     * characters against 4 for the secured {@code /u/{id:\\d+}/{action}}, so the open route answers
     * {@code /u/42/profile} although the secured route has the earlier static segment.
     */
    @Path("/u")
    public static class ReversalResource {

        /**
         * Open route with a literal tail.
         *
         * @param name the bound name
         * @return the bound name echoed back
         */
        @GET
        @Path("/{name}/profile")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "uOpen")
        public String open(@PathParam("name") String name) {
            return "open=" + name;
        }

        /**
         * Secured route with a regex variable and a plain variable.
         *
         * @param id the bound id
         * @param action the bound action
         * @return a constant body when authentication passes
         */
        @GET
        @Path("/{id:\\d+}/{action}")
        @Produces(MediaType.TEXT_PLAIN)
        @SecurityRequirement(name = "bearerAuth")
        @Operation(operationId = "uSecured")
        public String secured(@PathParam("id") String id, @PathParam("action") String action) {
            return "secured";
        }
    }

    /** Open alternation beside a longer open route that would otherwise answer first. */
    @Path("/q")
    public static class OpenAlternationResource {

        /**
         * Open two-variable route.
         *
         * @param a the first bound value
         * @param b the second bound value
         * @return the bound values echoed back
         */
        @GET
        @Path("/{a:\\w+}/{b}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "qLong")
        public String longer(@PathParam("a") String a, @PathParam("b") String b) {
            return "long=" + a + "/" + b;
        }

        /**
         * Open alternation: the single segment {@code a}, or {@code b/x}.
         *
         * @param p the bound value
         * @return the bound value echoed back
         */
        @GET
        @Path("/{p:a|b/x}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "qAlt")
        public String alt(@PathParam("p") String p) {
            return "alt=" + p;
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

    @Test
    @DisplayName("A secured /repos/{slug:[^/]+/admin} is not shadowed by an open /repos/{owner}/{name}")
    void spanningConstraintWithLiteralTailNotShadowed(Vertx vertx, VertxTestContext ctx) {
        WebClient client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
        run(vertx, ctx, new SpanningConstraintResource(), port -> status(client, port, "/repos/x/admin")
                .compose(code -> {
                    ctx.verify(() -> assertEquals(401, code, "anonymous /repos/x/admin must hit the auth handler"));
                    return body(client, port, "/repos/x/other");
                })
                .andThen(r -> ctx.verify(() -> assertEquals("open=x/other", r.result()))));
    }

    @Test
    @DisplayName("A secured /alt/{a:\\w+} is not shadowed by an open /alt/{p:b/c|a}")
    void alternationConstraintNotRankedAsTwoSegments(Vertx vertx, VertxTestContext ctx) {
        WebClient client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
        run(vertx, ctx, new AlternationResource(), port -> status(client, port, "/alt/a")
                .compose(code -> {
                    ctx.verify(() -> assertEquals(401, code, "anonymous /alt/a must hit the auth handler"));
                    return body(client, port, "/alt/b/c");
                })
                .andThen(r -> ctx.verify(() -> assertEquals("open=b/c", r.result()))));
    }

    private static io.vertx.core.Future<Void> expect(WebClient client, int port, String path, int expected) {
        return status(client, port, path).map(code -> {
            if (code != expected) {
                throw new AssertionError("GET " + path + " answered " + code + ", expected " + expected);
            }
            return null;
        });
    }

    @Test
    @DisplayName("A secured /ca/{id}/secret is not shadowed by an open catch-all /ca/{p:.+}")
    void literalTailNotShadowedByCatchAll(Vertx vertx, VertxTestContext ctx) {
        WebClient client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
        run(vertx, ctx, new CatchAllResource(), port -> expect(client, port, "/ca/x/secret", 401)
                .compose(v -> expect(client, port, "/ca/x/y", 200)));
    }

    @Test
    @DisplayName("A secured /mx/~admin.txt is not shadowed by an open /mx/{name}.txt")
    void longerLiteralInMixedSegmentNotShadowed(Vertx vertx, VertxTestContext ctx) {
        WebClient client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
        run(vertx, ctx, new MixedLiteralResource(), port -> expect(client, port, "/mx/~admin.txt", 401)
                .compose(v -> expect(client, port, "/mx/other.txt", 200)));
    }

    @Test
    @DisplayName("A secured /alt2/{p:a|b/admin} answers /alt2/b/admin and /alt2/a ahead of /alt2/{a:\\w+}/{b}")
    void alternationVariantsRankedSeparately(Vertx vertx, VertxTestContext ctx) {
        WebClient client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
        run(vertx, ctx, new AlternationVariantResource(), port -> expect(client, port, "/alt2/b/admin", 401)
                .compose(v -> expect(client, port, "/alt2/a", 401))
                .compose(v -> expect(client, port, "/alt2/x/y", 200)));
    }

    @Test
    @DisplayName("A weak alternative of /wk/{p:b/admin|[^/]+} does not shadow a secured /wk/secret")
    void weakAlternativeDoesNotShadowStaticSibling(Vertx vertx, VertxTestContext ctx) {
        WebClient client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
        run(vertx, ctx, new WeakAlternativeResource(), port -> expect(client, port, "/wk/secret", 401)
                .compose(v -> expect(client, port, "/wk/b/admin", 200))
                .compose(v -> expect(client, port, "/wk/other", 200)));
    }

    @Test
    @DisplayName("Reversal pinned: /u/{name}/profile answers /u/42/profile ahead of /u/{id:\\d+}/{action}")
    void literalTailOutranksEarlierRegexVariable(Vertx vertx, VertxTestContext ctx) {
        WebClient client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
        run(vertx, ctx, new ReversalResource(), port -> expect(client, port, "/u/42/profile", 200)
                .compose(v -> expect(client, port, "/u/42/edit", 401)));
    }

    @Test
    @DisplayName("An open alternation variant /q/{p:a|b/x} answers /q/b/x ahead of /q/{a:\\w+}/{b}")
    void variantRouteBindsParameterLikeFullTemplate(Vertx vertx, VertxTestContext ctx) {
        WebClient client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
        run(vertx, ctx, new OpenAlternationResource(), port -> body(client, port, "/q/b/x")
                .compose(first -> {
                    ctx.verify(() -> assertEquals("alt=b/x", first));
                    return body(client, port, "/q/c/d");
                })
                .andThen(r -> ctx.verify(() -> assertEquals("long=c/d", r.result()))));
    }
}
