// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.core.exception.UnavailableException;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.rest.core.ProblemDetail;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.interceptor.ErrorInterceptor;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Pins what {@code JaxRsRouterMount.handleFailure} does with the status the Vert.x layer sets when it
 * fails a routing context <em>alongside</em> a cause that is not an {@code HttpException}.
 *
 * <p>Every case is served by one mount whose API-scoped {@link Middleware} picks a
 * {@code RoutingContext.fail(...)} shape from the {@code X-Fail-Mode} request header. Failing on the
 * mount's own router is what makes the failure reach the router-level failure handler under test —
 * a failure raised anywhere else would never enter {@code handleFailure}.
 *
 * <p>The mount is wired against {@link RestModule#defaultExceptionMapper()}, the framework's real
 * defaults. That is load-bearing rather than incidental: a hand-built stand-in carrying one or two
 * hand-picked mappings would let every assertion here pass while the harness stayed blind to a
 * regression that turns a mapped 4xx into a 500. What these tests observe must be the status the
 * shipped configuration produces.
 *
 * <p>Two tests also assert the routing-context hint under {@link VertxFailureStatus#KEY}, observed
 * through an {@link ErrorInterceptor} before mapping. Asserting the status alone would prove only that
 * <em>some</em> branch answered; asserting the hint proves <em>which</em> branch produced it.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class VertxFailureStatusPreservationIT {

    /** Request header selecting which {@code ctx.fail(...)} the middleware performs. */
    private static final String FAIL_MODE_HEADER = "X-Fail-Mode";

    private static final String MODE_VERTX_400 = "vertx-400-with-cause";
    private static final String MODE_VERTX_401 = "vertx-401-with-cause";
    private static final String MODE_USER_MAPPED = "vertx-400-with-user-mapped-cause";
    private static final String MODE_BARE_THROWABLE = "bare-throwable";
    private static final String MODE_EXPLICIT_500 = "vertx-500-with-cause";
    private static final String MODE_BELOW_400 = "vertx-200-with-cause";

    /**
     * The message the 401 cause carries. It stands in for whatever a claims validator, a
     * tenant-binding check, or any other application exception happens to throw — it must never
     * reach the client.
     */
    private static final String LEAKED_MESSAGE = "bad token";

    private static final long ASYNC_TIMEOUT_SECONDS = 10;

    /**
     * The Vert.x failure-status hint observed as each failure entered the error pipeline, keyed by
     * fail mode. The value is {@link Optional#empty()} when the hint was absent, so "no hint" is
     * distinguishable from "the pipeline never ran" (a missing map entry).
     */
    private static final Map<String, Optional<Object>> OBSERVED_HINTS = new ConcurrentHashMap<>();

    private static HttpServer server;
    private static HttpClient client;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount mount = buildFactory().create("/*", "openapi.json", Set.of(new ProbeResource()));

        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0);
                })
                .onComplete(ctx.succeeding(listeningServer -> {
                    server = listeningServer;
                    client = vertx.createHttpClient();
                    ctx.completeNow();
                }));
    }

    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<?> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    // --- The two preservation cases ---

    @Test
    @DisplayName("A Vert.x 400 set alongside a non-HttpException cause reaches the client as 400")
    void vertxFourHundredWithCausePreserved() throws Exception {
        HttpResult result = get(MODE_VERTX_400);

        assertEquals(
                400,
                result.statusCode(),
                "the status Vert.x deliberately set must survive to the response; hint="
                        + describeHint(MODE_VERTX_400));
        assertEquals(
                Optional.of(400),
                observedHint(MODE_VERTX_400),
                "handleFailure must stash the Vert.x 4xx as the authoritative failure status");
    }

    @Test
    @DisplayName("A Vert.x 401 set alongside a non-HttpException cause answers 401 without leaking the cause message")
    void vertxUnauthorizedWithCauseIsSanitized() throws Exception {
        HttpResult result = get(MODE_VERTX_401);

        assertEquals(
                401,
                result.statusCode(),
                "an authentication rejection must not be reported as another status; hint="
                        + describeHint(MODE_VERTX_401));
        assertEquals(
                Optional.of(401),
                observedHint(MODE_VERTX_401),
                "handleFailure must stash the Vert.x 4xx as the authoritative failure status");

        JsonObject problem = result.problem();
        assertEquals("Unauthorized", problem.getString("title"), "the title must be re-derived from the final status");
        assertNull(problem.getString("detail"), "an arbitrary cause message must never be published as detail");
        assertFalse(
                result.bodyText().contains(LEAKED_MESSAGE),
                "the cause message must not appear anywhere in the body: " + result.bodyText());
    }

    // --- Characterization: the precedence the fix must not disturb ---

    @Test
    @DisplayName("An application ExceptionMapper outranks the Vert.x failure status")
    void userMapperOutranksVertxStatus() throws Exception {
        HttpResult result = get(MODE_USER_MAPPED);

        assertEquals(
                422,
                result.statusCode(),
                "an application-contributed mapper is the top of the precedence chain, above a Vert.x 4xx");
    }

    @Test
    @DisplayName("A bare ctx.fail(Throwable) keeps the mapped status rather than the synthesised 500")
    void bareThrowableKeepsMapperStatus() throws Exception {
        HttpResult result = get(MODE_BARE_THROWABLE);

        assertEquals(
                503,
                result.statusCode(),
                "ctx.fail(Throwable) synthesises a 500 that must never override the framework mapping");
    }

    @Test
    @DisplayName("An explicit ctx.fail(500, cause) never overrides the framework mapping")
    void explicitFiveHundredNeverOverrides() throws Exception {
        HttpResult result = get(MODE_EXPLICIT_500);

        assertEquals(
                400,
                result.statusCode(),
                "a 5xx failure status is indistinguishable from fail(Throwable)'s synthesised 500, so the mapper wins");
    }

    @Test
    @DisplayName("A failure status below 400 is not captured as a Vert.x hint")
    void belowFourHundredIsNotCaptured() throws Exception {
        HttpResult result = get(MODE_BELOW_400);

        assertEquals(
                500,
                result.statusCode(),
                "a sub-400 failure status is not a client-error decision and must not reach the response");
        assertEquals(
                Optional.empty(),
                observedHint(MODE_BELOW_400),
                "only a 4xx is an authoritative Vert.x client-error decision");
    }

    // --- Harness ---

    private static HttpResult get(String failMode) throws Exception {
        return client.request(HttpMethod.GET, server.actualPort(), "localhost", "/probe")
                .compose(
                        request -> request.putHeader(FAIL_MODE_HEADER, failMode).send())
                .compose(response -> {
                    int statusCode = response.statusCode();
                    return response.body().map(body -> new HttpResult(statusCode, body));
                })
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static Optional<Object> observedHint(String failMode) {
        Optional<Object> hint = OBSERVED_HINTS.get(failMode);
        assertNotNull(hint, "the error pipeline never observed a failure for mode " + failMode);
        return hint;
    }

    private static String describeHint(String failMode) {
        Optional<Object> hint = OBSERVED_HINTS.get(failMode);
        return hint == null ? "<pipeline never ran>" : hint.map(String::valueOf).orElse("<absent>");
    }

    private record HttpResult(int statusCode, Buffer body) {

        JsonObject problem() {
            return body.toJsonObject();
        }

        String bodyText() {
            return body.toString();
        }
    }

    /** Minimal resource so the mount has something to register; the middleware fails before it runs. */
    @Path("/probe")
    public static class ProbeResource {

        /**
         * Handles {@code GET /probe} for the one request shape that carries no fail mode.
         *
         * @return a constant success payload
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(operationId = "getProbe")
        public String probe() {
            return "ok";
        }
    }

    /** Test-only marker exception with an application-contributed {@link ExceptionMapper}. */
    public static final class Marker extends RuntimeException {

        Marker() {
            super("marker");
        }
    }

    /** Application-contributed mapper proving a specific mapper outranks the Vert.x failure status. */
    public static final class MarkerExceptionMapper implements ExceptionMapper<Marker> {

        /**
         * Maps {@link Marker} to 422, a status no framework default produces for it.
         *
         * @param exception the marker exception
         * @return a 422 problem response
         */
        @Override
        public Response toResponse(Marker exception) {
            return Response.status(422)
                    .entity(ProblemDetail.of(422, exception.getMessage()))
                    .type("application/problem+json")
                    .build();
        }
    }

    /**
     * API-scoped middleware failing the routing context in the shape the {@code X-Fail-Mode} header
     * names. Mounted on the mount's own router, so every failure below enters the router-level
     * failure handler under test.
     */
    private static final class FailModeMiddleware implements Middleware {

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public MiddlewareScope scope() {
            return MiddlewareScope.API;
        }

        @Override
        public void handle(RoutingContext ctx) {
            String mode = ctx.request().getHeader(FAIL_MODE_HEADER);
            if (mode == null) {
                ctx.next();
                return;
            }
            switch (mode) {
                case MODE_VERTX_400 -> ctx.fail(400, new IllegalStateException("x"));
                case MODE_VERTX_401 -> ctx.fail(401, new IllegalArgumentException(LEAKED_MESSAGE));
                case MODE_USER_MAPPED -> ctx.fail(400, new Marker());
                case MODE_BARE_THROWABLE -> ctx.fail(new UnavailableException("down"));
                case MODE_EXPLICIT_500 -> ctx.fail(500, new IllegalArgumentException("y"));
                case MODE_BELOW_400 -> ctx.fail(200, new RuntimeException());
                default -> ctx.next();
            }
        }
    }

    /**
     * Records the Vert.x failure-status hint as each failure enters the error pipeline, keyed by the
     * fail mode that produced it, so a test reads the hint its own request generated regardless of
     * execution order.
     */
    private static final class HintCapture implements ErrorInterceptor {

        @Override
        public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
            String mode = rc.request().getHeader(FAIL_MODE_HEADER);
            if (mode != null) {
                OBSERVED_HINTS.put(mode, Optional.ofNullable(rc.data().get(VertxFailureStatus.KEY)));
            }
            return Future.succeededFuture(throwable);
        }
    }

    /**
     * Builds the mount factory these tests post against, wired to the framework's real exception
     * defaults via {@link RestModule#defaultExceptionMapper()} plus one application-contributed
     * mapper for {@link Marker}.
     *
     * @return a factory producing mounts backed by the framework's real exception defaults
     */
    private static JaxRsRouterMount.Factory buildFactory() {
        ExceptionMapperRegistry registry =
                new ExceptionMapperRegistry(RestModule.defaultExceptionMapper(), Set.of(new MarkerExceptionMapper()));
        RestExceptionMapper restExceptionMapper = new RestExceptionMapper();
        RestContextResolution restContextResolution = new RestContextResolution(Set.of());
        List<dev.vertique.rest.core.response.ResponseBodyEncoder> encoders = List.of(new JsonBodyEncoder());
        DefaultResponseSerializer responseSerializer = new DefaultResponseSerializer(List.of(), encoders);
        HttpConfig httpConfig = HttpConfig.builder().build();
        JaxRsConfig jaxRsConfig = JaxRsConfig.builder()
                .validationStrategy(NoneValidationStrategy.ID)
                .build();

        return new JaxRsRouterMount.Factory(
                Set.of(), // routerLifecycleHooks
                Set.of(), // operationInterceptors
                Set.of(new HintCapture()), // errorInterceptors — observes the Vert.x status hint
                Set.of(new FailModeMiddleware()), // middlewares — raises the failure under test
                Set.of(), // operationHandlerContributors
                Set.of(), // securitySchemeHandlers
                Set.of(), // requestInterceptors
                restExceptionMapper,
                registry,
                Set.of(), // responseProducerBindings
                responseSerializer,
                restContextResolution,
                dev.vertique.rest.jaxrs.convert.ConversionContexts.defaultResolver(),
                null, // securityPolicyValidator (nullable)
                Optional.empty(), // authEnforcementCapability
                List.of(), // sortedDecoders
                encoders,
                httpConfig,
                jaxRsConfig,
                new DefaultJsonMapperProfileRegistry(Set.of()),
                JsonConfig.defaults(),
                Optional.empty(), // beanValidator
                Optional.empty(), // objectProcessor
                Set.of(), // evidenceCapturers
                Optional.empty(), // actionRegistry
                Optional.empty(), // authorizer
                Set.of(), // fileContentVerifiers
                Set.of(new NoneValidationStrategy()),
                Optional.empty() // operationSchemaSource
                );
    }
}
