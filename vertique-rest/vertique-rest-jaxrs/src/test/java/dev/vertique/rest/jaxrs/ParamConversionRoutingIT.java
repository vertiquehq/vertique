// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end inbound param-conversion tests for the plain-{@code Router} {@link JaxRsRouterMount}
 * (PRD-REST-018 slice 1.3). These drive the real {@code createRouter()} path under the {@code none}
 * validation strategy and prove that non-scalar JAX-RS parameter types
 * ({@link UUID}/{@link Instant}/{@link BigDecimal}/enum/collection-of-{@code UUID}) are converted to
 * their declared Java types before reaching the resource method.
 *
 * <p><b>RED rationale (behavior-RED, compiles against current code):</b> today
 * {@code DefaultBoundRequest}/{@code ParameterExtractor} only coerce scalars via
 * {@code ScalarCoercion}; a {@code UUID}/{@code Instant}/{@code BigDecimal}/enum value reaches
 * {@code Method.invoke} as a raw {@link String}, which throws {@link IllegalArgumentException}
 * (mapped to 400 by {@code RestModule.defaultExceptionMapper()}). So the resource never executes and
 * the success cases below — which assert {@code 200} with the parsed typed value echoed — fail until
 * the {@code ParamConversionResolver} is wired into the inbound binding path. The malformed-value
 * cases assert {@code 400}; today the same opaque {@code IllegalArgumentException} happens to surface
 * as 400, so the load-bearing RED signal is the success-case typed echo, not the 400s.
 *
 * <p>This class mirrors {@link AnnotationDrivenRoutingIT} exactly (Vert.x {@link VertxExtension},
 * {@code listen(0)}, a per-test {@link HttpClient}, and {@code status|body} projections) for
 * determinism and uses JUnit 5 assertions because AssertJ is not on this module's test classpath.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ParamConversionRoutingIT {

    private HttpServer server;
    private HttpClient client;

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<?> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(ar -> ctx.completeNow());
    }

    // --- Fixtures ---

    /** A small enum exercised by the {@code @HeaderParam} conversion test. */
    public enum Mode {
        /** First alternative. */
        VALUE_A,
        /** Second alternative. */
        VALUE_B
    }

    /** Resource declaring non-scalar typed parameters that the resolver must convert. */
    @Path("/convert")
    public static class ConversionResource {

        /**
         * Echoes the converted {@link UUID} path id, proving the path value reaches the method as a
         * real {@link UUID} (not a raw string).
         *
         * @param id the UUID path param
         * @return the runtime class and value of the bound argument
         */
        @GET
        @Path("/uuid/{id}")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "convUuid")
        public String uuid(@PathParam("id") UUID id) {
            return id.getClass().getSimpleName() + "=" + id;
        }

        /**
         * Echoes the converted {@link Instant} query param.
         *
         * @param ts the Instant query param
         * @return the runtime class and value of the bound argument
         */
        @GET
        @Path("/instant")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "convInstant")
        public String instant(@QueryParam("ts") Instant ts) {
            return ts.getClass().getSimpleName() + "=" + ts;
        }

        /**
         * Echoes the converted {@link BigDecimal} query param.
         *
         * @param amount the BigDecimal query param
         * @return the runtime class and value of the bound argument
         */
        @GET
        @Path("/amount")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "convAmount")
        public String amount(@QueryParam("amount") BigDecimal amount) {
            return amount.getClass().getSimpleName() + "=" + amount.toPlainString();
        }

        /**
         * Echoes the converted enum header param.
         *
         * @param mode the enum header param
         * @return the runtime class and value of the bound argument
         */
        @GET
        @Path("/mode")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "convMode")
        public String mode(@HeaderParam("X-Mode") Mode mode) {
            return mode.getClass().getSimpleName() + "=" + mode.name();
        }

        /**
         * Echoes the converted collection-of-{@link UUID} query param, proving each element is
         * converted to a real {@link UUID} (not a raw string).
         *
         * @param ids the repeated UUID query values
         * @return the element runtime class plus the count
         */
        @GET
        @Path("/uuids")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "convUuids")
        public String uuids(@QueryParam("ids") List<UUID> ids) {
            String elementType = ids.isEmpty() ? "empty" : ids.get(0).getClass().getSimpleName();
            return elementType + ",size=" + ids.size();
        }

        /**
         * Existing scalar case retained to prove the resolver does not regress scalar binding.
         *
         * @param count the int query param
         * @return the bound int echoed back
         */
        @GET
        @Path("/count")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "convCount")
        public String count(@QueryParam("count") int count) {
            return "count=" + count;
        }
    }

    // --- Success cases (behavior-RED: typed echo proves conversion ran) ---

    @Test
    @DisplayName("A valid @PathParam UUID converts to a UUID and the resource returns 200")
    void uuidPathParamConverts(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        get(vertx, ctx, "/convert/uuid/" + id, statusAndBody -> assertEquals("200|UUID=" + id, statusAndBody));
    }

    @Test
    @DisplayName("A valid @QueryParam Instant converts to an Instant and the resource returns 200")
    void instantQueryParamConverts(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
        get(
                vertx,
                ctx,
                "/convert/instant?ts=2024-01-15T10:30:00Z",
                statusAndBody -> assertEquals("200|Instant=2024-01-15T10:30:00Z", statusAndBody));
    }

    @Test
    @DisplayName("A valid @QueryParam BigDecimal converts to a BigDecimal and the resource returns 200")
    void bigDecimalQueryParamConverts(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
        get(
                vertx,
                ctx,
                "/convert/amount?amount=9.99",
                statusAndBody -> assertEquals("200|BigDecimal=9.99", statusAndBody));
    }

    @Test
    @DisplayName("A valid @HeaderParam enum converts to the enum constant and the resource returns 200")
    void enumHeaderParamConverts(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
        deploy(vertx, ctx, port -> client.request(HttpMethod.GET, port, "localhost", "/convert/mode")
                .compose(req -> req.putHeader("X-Mode", "VALUE_A").send())
                .compose(resp -> {
                    int status = resp.statusCode();
                    return resp.body().map(b -> status + "|" + b.toString());
                })
                .onComplete(ctx.succeeding(statusAndBody -> {
                    ctx.verify(() -> assertEquals("200|Mode=VALUE_A", statusAndBody));
                    ctx.completeNow();
                })));
    }

    @Test
    @DisplayName("A repeated @QueryParam List<UUID> converts each element to a UUID and returns 200")
    void collectionUuidElementsConvert(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
        UUID a = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID b = UUID.fromString("22222222-2222-2222-2222-222222222222");
        get(
                vertx,
                ctx,
                "/convert/uuids?ids=" + a + "&ids=" + b,
                statusAndBody -> assertEquals("200|UUID,size=2", statusAndBody));
    }

    @Test
    @DisplayName("An existing @QueryParam int still binds and returns 200 (no scalar regression)")
    void intParamUnchanged(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
        get(vertx, ctx, "/convert/count?count=5", statusAndBody -> assertEquals("200|count=5", statusAndBody));
    }

    // --- Malformed-value cases (must be 400, never 500) ---

    @Test
    @DisplayName("A malformed @PathParam UUID returns 400, not 500")
    void badUuidValueReturns400(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
        get(
                vertx,
                ctx,
                "/convert/uuid/not-a-uuid",
                statusAndBody -> assertEquals("400", statusAndBody.substring(0, statusAndBody.indexOf('|'))));
    }

    @Test
    @DisplayName("A malformed @QueryParam List<UUID> element returns 400 (fail-closed)")
    void collectionElementBadValueReturns400(io.vertx.core.Vertx vertx, VertxTestContext ctx) {
        UUID a = UUID.fromString("11111111-1111-1111-1111-111111111111");
        get(
                vertx,
                ctx,
                "/convert/uuids?ids=" + a + "&ids=not-a-uuid",
                statusAndBody -> assertEquals("400", statusAndBody.substring(0, statusAndBody.indexOf('|'))));
    }

    // --- HTTP helpers (mirroring AnnotationDrivenRoutingIT) ---

    /**
     * Deploys the {@link ConversionResource} under the {@code none} strategy, issues a {@code GET}, and
     * asserts on a {@code status|body} projection.
     *
     * @param vertx     the Vert.x instance
     * @param ctx       the test context
     * @param path      the request path
     * @param assertion the assertion on the {@code status|body} projection
     */
    private void get(
            io.vertx.core.Vertx vertx,
            VertxTestContext ctx,
            String path,
            java.util.function.Consumer<String> assertion) {
        deploy(vertx, ctx, port -> client.request(HttpMethod.GET, port, "localhost", path)
                .compose(req -> req.send())
                .compose(resp -> {
                    int status = resp.statusCode();
                    return resp.body().map(b -> status + "|" + b.toString());
                })
                .onComplete(ctx.succeeding(statusAndBody -> {
                    ctx.verify(() -> assertion.accept(statusAndBody));
                    ctx.completeNow();
                })));
    }

    /**
     * Deploys the {@link ConversionResource} under the {@code none} strategy and invokes
     * {@code afterListen} with the bound port once the server is up.
     *
     * @param vertx       the Vert.x instance
     * @param ctx         the test context
     * @param afterListen the callback invoked with the server port
     */
    private void deploy(io.vertx.core.Vertx vertx, VertxTestContext ctx, java.util.function.IntConsumer afterListen) {
        JaxRsRouterMount.Factory factory = TestFactories.builder().build();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new ConversionResource()));
        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0);
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    client = vertx.createHttpClient();
                    afterListen.accept(s.actualPort());
                }));
    }
}
