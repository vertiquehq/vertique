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
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.ext.web.handler.HttpException;
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
import java.util.UUID;
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
 *
 * <p>Requests are issued through a {@link WebClient} rather than a raw {@code HttpClient} deliberately:
 * a raw {@code HttpClientResponse} discards body buffers that arrive before a body handler is attached,
 * so under load {@code body()} can succeed with zero bytes while the status code is correct (issue
 * #167). Several cases here decode the {@code problem+json} body — the sanitized 401 and the
 * problem-status assertion both read it — so a silently emptied body would fail them for a reason
 * unrelated to failure-status handling. A {@link WebClient} aggregates the body into its
 * {@code HttpResponse} before completing the send.
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
    private static final String MODE_HTTP_EXCEPTION_BELOW_400 = "http-exception-200-no-cause";

    /**
     * The message the 401 cause carries. It stands in for whatever a claims validator, a
     * tenant-binding check, or any other application exception happens to throw — it must never
     * reach the client.
     */
    private static final String LEAKED_MESSAGE = "bad token";

    private static final long ASYNC_TIMEOUT_SECONDS = 10;

    /**
     * Response header stamped on every answer this class's own server writes, and the value it
     * carries. It exists to make one specific question answerable from a CI log alone: did our
     * server answer this request at all? An empty body under a correct-looking status has two
     * unrelated causes — our pipeline writing no entity, or some other process answering on the
     * port — and without a marker the failure text cannot distinguish them.
     */
    private static final String SERVER_MARKER_HEADER = "X-Vertique-Test-Server";

    private static final String SERVER_ID = UUID.randomUUID().toString();

    /**
     * The Vert.x failure-status hint observed as each failure entered the error pipeline, keyed by
     * fail mode. The value is {@link Optional#empty()} when the hint was absent, so "no hint" is
     * distinguishable from "the pipeline never ran" (a missing map entry).
     */
    private static final Map<String, Optional<Object>> OBSERVED_HINTS = new ConcurrentHashMap<>();

    private static HttpServer server;
    private static WebClient client;

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount mount = buildFactory().create("/*", "openapi.json", Set.of(new ProbeResource()));

        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    // Stamp the marker before the router sees the request, so it rides every
                    // response this server writes — including ones produced by the failure handler
                    // under test — without taking part in routing or middleware ordering.
                    return vertx.createHttpServer()
                            .requestHandler(request -> {
                                request.response().putHeader(SERVER_MARKER_HEADER, SERVER_ID);
                                root.handle(request);
                            })
                            .listen(0, "127.0.0.1");
                })
                .onComplete(ctx.succeeding(listeningServer -> {
                    server = listeningServer;
                    // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
                    client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
                    ctx.completeNow();
                }));
    }

    /**
     * Closes the {@link WebClient} and then the server.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is nothing to join here and the server
     * close alone carries the completion.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    // --- The two preservation cases ---

    @Test
    @DisplayName("A Vert.x 400 set alongside a non-HttpException cause reaches the client as 400")
    void vertxFourHundredWithCausePreserved() throws Exception {
        HttpResult result = get(MODE_VERTX_400);

        assertEquals(
                400,
                result.statusCode(),
                "the status Vert.x deliberately set must survive to the response; hint=" + describeHint(MODE_VERTX_400)
                        + "; " + result.diagnostic());
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
                        + describeHint(MODE_VERTX_401) + "; " + result.diagnostic());
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
                "an application-contributed mapper is the top of the precedence chain, above a Vert.x 4xx; "
                        + result.diagnostic());
    }

    @Test
    @DisplayName("A bare ctx.fail(Throwable) keeps the mapped status rather than the synthesised 500")
    void bareThrowableKeepsMapperStatus() throws Exception {
        HttpResult result = get(MODE_BARE_THROWABLE);

        assertEquals(
                503,
                result.statusCode(),
                "ctx.fail(Throwable) synthesises a 500 that must never override the framework mapping; "
                        + result.diagnostic());
    }

    @Test
    @DisplayName("An explicit ctx.fail(500, cause) never overrides the framework mapping")
    void explicitFiveHundredNeverOverrides() throws Exception {
        HttpResult result = get(MODE_EXPLICIT_500);

        assertEquals(
                400,
                result.statusCode(),
                "a 5xx failure status is indistinguishable from fail(Throwable)'s synthesised 500, so the mapper wins; "
                        + result.diagnostic());
    }

    @Test
    @DisplayName("A failure status below 400 is not captured as a Vert.x hint")
    void belowFourHundredIsNotCaptured() throws Exception {
        HttpResult result = get(MODE_BELOW_400);

        assertEquals(
                500,
                result.statusCode(),
                "a sub-400 failure status is not a client-error decision and must not reach the response; "
                        + result.diagnostic());
        assertEquals(
                Optional.empty(),
                observedHint(MODE_BELOW_400),
                "only a 4xx is an authoritative Vert.x client-error decision");
    }

    @Test
    @DisplayName("A cause-less HttpException below 400 answers 500, not the non-error status it carried")
    void causelessHttpExceptionBelowFourHundredAnswersServerError() throws Exception {
        HttpResult result = get(MODE_HTTP_EXCEPTION_BELOW_400);

        assertEquals(
                500,
                result.statusCode(),
                "refusing to record the hint is not enough — the cause synthesised from the same status "
                        + "would answer the failure '200 OK' with a problem document, hint was "
                        + describeHint(MODE_HTTP_EXCEPTION_BELOW_400) + "; " + result.diagnostic());
        assertEquals(
                Optional.empty(),
                observedHint(MODE_HTTP_EXCEPTION_BELOW_400),
                "a sub-400 HttpException status is not an authoritative client-error decision");
        assertEquals(
                500,
                result.problem().getInteger("status"),
                "the problem body must describe the status actually sent; " + result.diagnostic());
    }

    // --- Harness ---

    private static HttpResult get(String failMode) throws Exception {
        // The WebClient response future resolves only once the whole body has been aggregated. A raw
        // client discards body buffers delivered before a handler is attached, so a read attached after
        // the send loses the entire body whenever this (worker) thread is descheduled in between,
        // reporting success with zero bytes under a correct status. See HttpClientBodyReadRaceIT.
        return client.get(server.actualPort(), "127.0.0.1", "/probe")
                .putHeader(FAIL_MODE_HEADER, failMode)
                .send()
                .map(response -> new HttpResult(
                        response.statusCode(),
                        // Copied: the response's headers are not guaranteed to stay
                        // readable once the exchange is recycled.
                        MultiMap.caseInsensitiveMultiMap().addAll(response.headers()),
                        response.version(),
                        // A body-less response arrives as a null buffer, where the raw client
                        // reported a zero-length one; normalised so problem()/bodyText()/diagnostic()
                        // read exactly as they did before.
                        response.body() == null ? Buffer.buffer() : response.body()))
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

    /**
     * One observed HTTP response, captured as evidence rather than as a bare assertion subject.
     *
     * @param statusCode the response status code
     * @param headers    a copy of the response headers, safe to read after the exchange is recycled
     * @param version    the HTTP version the response was framed with
     * @param body       the complete response body
     */
    private record HttpResult(int statusCode, MultiMap headers, HttpVersion version, Buffer body) {

        JsonObject problem() {
            try {
                return body.toJsonObject();
            } catch (RuntimeException undecodable) {
                // A bare DecodeException reports only "no content to map", which says nothing about
                // WHY the body is missing. Fail with the wire evidence attached instead — see
                // diagnostic() for what it distinguishes.
                throw new AssertionError("the response body is not decodable JSON. " + diagnostic(), undecodable);
            }
        }

        String bodyText() {
            return body.toString();
        }

        /**
         * Describes the response as it came off the wire, for failures whose cause is not local.
         *
         * <p>The decisive field is {@code marker}: this class's server stamps
         * {@link #SERVER_MARKER_HEADER} on every response it writes, so {@code marker=ABSENT} means
         * the answer did not come from our server at all (something else held the port), while a
         * matching marker with an empty body means our own pipeline wrote no entity. Those two have
         * nothing in common as defects, and the failure text has so far been unable to tell them
         * apart — which is why this exists. Content-Length and the HTTP version separate a body that
         * was never written from one that was announced and then lost.
         *
         * @return a single-line description of status, marker, framing headers and body length
         */
        String diagnostic() {
            String marker = headers.get(SERVER_MARKER_HEADER);
            return "status=" + statusCode
                    + " marker=" + (marker == null ? "ABSENT" : (SERVER_ID.equals(marker) ? "ours" : marker))
                    + " httpVersion=" + version
                    + " contentLength=" + headers.get("Content-Length")
                    + " transferEncoding=" + headers.get("Transfer-Encoding")
                    + " connection=" + headers.get("Connection")
                    + " contentType=" + headers.get("Content-Type")
                    + " bodyLength=" + body.length()
                    + " headers=" + headers.entries();
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
                case MODE_HTTP_EXCEPTION_BELOW_400 -> ctx.fail(new HttpException(200));
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
