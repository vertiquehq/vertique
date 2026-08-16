// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.auth.jwt;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.middleware.Middleware;
import dev.vertique.rest.core.middleware.MiddlewareScope;
import dev.vertique.rest.core.response.BufferedBody;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.SerializedBody;
import dev.vertique.rest.jaxrs.DefaultResponseSerializer;
import dev.vertique.rest.jaxrs.ExceptionMapperRegistry;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.RestExceptionMapper;
import dev.vertique.rest.jaxrs.RestModule;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import dev.vertique.rest.security.CredentialRejectionReporter;
import dev.vertique.security.AuthMethod;
import dev.vertique.security.verification.VerificationSource;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.auth.User;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.impl.UserContextInternal;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Direct, mount-based proof of what a {@link JwtClaimsValidator} rejection actually answers on the
 * wire. {@link JwtClaimsValidatorContributor} rejects with {@code ctx.fail(401, e)} — an explicit
 * Vert.x status carrying an <em>arbitrary application exception</em> — and this test pins what the
 * client sees for that shape.
 *
 * <h3>Why the mount is load-bearing</h3>
 * {@code JaxRsRouterMount.handleFailure} is the router-level failure handler that decides how a
 * Vert.x failure status and its cause are reconciled. The module's existing
 * {@link ActionOnlyRouteClaimsValidatorIT} builds its router through the OpenAPI
 * {@code RouterBuilder} and therefore never enters {@code handleFailure} — it can prove the
 * validator <em>runs</em>, but not what the framework renders when it rejects. This test mounts the
 * real {@link JaxRsRouterMount} instead, so the rejection travels the shipped failure path.
 *
 * <h3>Why the framework's real exception defaults are load-bearing</h3>
 * The mount is wired against {@link RestModule#defaultExceptionMapper()}. A hand-built stand-in
 * carrying one or two hand-picked mappings would let these assertions pass while staying blind to
 * what the shipped configuration does with the validator's exception type — and the shipped
 * configuration is precisely what decides the status and whether the message is published.
 *
 * <h3>What is asserted</h3>
 * A claims rejection is an authentication outcome, so the client must observe {@code 401} with a
 * coherent RFC 9457 body. For <em>this</em> rejection shape the validator's own message — which may
 * name a tenant, a subject, or an internal policy — is also absent from that body, because the
 * framework's {@code 401} overrides the {@code 400} the default mapping gives
 * {@link IllegalArgumentException} and a body rebuilt for the overriding status carries no detail.
 * That is the scope of the guarantee proven here, not a blanket promise that a validator message is
 * never published: a validator whose exception already maps to {@code 401} has its status preserved
 * rather than overridden, so the detail its mapper authored survives. A validator throwing
 * {@link IllegalArgumentException} is not an exotic choice: it is the ordinary way an application
 * signals "this claim value is not acceptable", and it is the shape whose framework default mapping
 * decides whether the message reaches the client.
 *
 * <p>The resource method carries no {@code @Operation}: the operationId falls back to the method
 * name, and operationId derivation is not part of the seam under test.
 *
 * <p>Requests go through a {@link WebClient} rather than a raw {@code HttpClient} deliberately: a
 * raw {@code HttpClientResponse} discards body buffers that arrive before a body handler is
 * attached, so under load {@code body()} can succeed with zero bytes while the status code is
 * correct (issue #167). Every assertion here reads the rendered problem body, so a silently
 * emptied body would decode-fail on one test and let the no-leak test pass for the wrong reason. A
 * {@link WebClient} aggregates the body into its {@code HttpResponse} before completing the send.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class JwtClaimsRejectionStatusIT {

    /**
     * The message the claims validator throws. It stands in for whatever a tenant-binding, token
     * revocation, or custom-claim check happens to say — and because the exception carrying it maps
     * to {@code 400}, which the framework's {@code 401} then overrides, it does not reach the client
     * on this path.
     */
    private static final String REJECTION_MESSAGE = "tenant 4711 is not permitted";

    /** Bearer token value; the stub middleware accepts any non-blank value as an authenticated user. */
    private static final String VALID_TOKEN = "alice";

    private static final long ASYNC_TIMEOUT_SECONDS = 10;

    /**
     * Reason codes reported through {@link CredentialRejectionReporter}, in arrival order. Asserting
     * that {@link JwtClaimsValidatorContributor#REASON_CODE} was reported proves the response under
     * test was produced by the claims-validator rejection path and not by some other 401 source.
     */
    private static final ConcurrentLinkedQueue<String> REPORTED_REASON_CODES = new ConcurrentLinkedQueue<>();

    private static HttpServer server;
    private static WebClient client;

    /**
     * Mounts the JAX-RS router with the claims-validator contributor wired in and starts the shared
     * server and {@link WebClient}. The client is bound to a static field so {@link #tearDown} can
     * close it; an unbound client can never be closed at all.
     *
     * @param vertx the Vert.x instance injected by {@link VertxExtension}
     * @param ctx   the test context used for async startup assertion
     */
    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount mount = buildFactory().create("/*", "openapi.json", Set.of(new SecureResource()));

        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0, "127.0.0.1");
                })
                .onComplete(ctx.succeeding(listeningServer -> {
                    server = listeningServer;
                    client = WebClient.create(vertx);
                    ctx.completeNow();
                }));
    }

    /**
     * Closes the shared {@link WebClient} and then the shared server, before the extension-owned
     * {@link Vertx} instance is closed.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns
     * once the underlying client has been asked to close, so there is no future to join here.
     *
     * @param ctx the test context used for async teardown assertion
     */
    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    // --- Tests ---

    @Test
    @DisplayName("A JwtClaimsValidator rejection on an otherwise-valid token answers 401")
    void claimsRejectionAnswersUnauthorized() throws Exception {
        HttpResult result = get();

        assertTrue(
                REPORTED_REASON_CODES.contains(JwtClaimsValidatorContributor.REASON_CODE),
                "the response under test must be the claims-validator rejection: reported reason codes were "
                        + REPORTED_REASON_CODES);
        assertEquals(
                401,
                result.statusCode(),
                "a claims rejection is an authentication outcome and must reach the client as 401; body was "
                        + result.bodyText());
    }

    @Test
    @DisplayName("A JwtClaimsValidator rejection renders a coherent problem+json body")
    void claimsRejectionRendersUnauthorizedProblemDetail() throws Exception {
        HttpResult result = get();

        JsonObject problem = result.problem();
        // assertAll, not sequential asserts: media type, status and title are independent properties
        // of the same body, and a report of this slice needs to know which of them hold today rather
        // than only the first one that does not.
        assertAll(
                () -> assertEquals(
                        "application/problem+json",
                        result.contentType(),
                        "an error body must be served as RFC 9457 problem+json"),
                () -> assertEquals(
                        401,
                        problem.getInteger("status"),
                        "the problem body must carry the same status as the response; body was " + result.bodyText()),
                () -> assertEquals(
                        "Unauthorized",
                        problem.getString("title"),
                        "the title must be derived from the final status, not from whatever mapping produced the "
                                + "body; body was " + result.bodyText()));
    }

    @Test
    @DisplayName("A JwtClaimsValidator rejection does not publish the validator's message")
    void claimsRejectionDoesNotLeakValidatorMessage() throws Exception {
        HttpResult result = get();

        assertFalse(
                result.bodyText().contains(REJECTION_MESSAGE),
                "the claims validator's message is arbitrary application text and must not appear anywhere in the "
                        + "response body: " + result.bodyText());
    }

    // --- Harness ---

    /**
     * Issues {@code GET /secure} with a bearer token the stub authenticator accepts and the claims
     * validator rejects.
     *
     * @return the response status, content type and body
     * @throws Exception if the request does not complete within {@link #ASYNC_TIMEOUT_SECONDS}
     */
    private static HttpResult get() throws Exception {
        return client.get(server.actualPort(), "127.0.0.1", "/secure")
                .putHeader("Authorization", "Bearer " + VALID_TOKEN)
                .send()
                .map(response -> new HttpResult(
                        response.statusCode(), response.getHeader("Content-Type"), response.bodyAsString()))
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * One observed HTTP response.
     *
     * <p>The body is captured as text through {@code bodyAsString()} rather than as a
     * {@link Buffer}: a {@link WebClient} reports an empty body as {@code null} where the raw
     * client reported a zero-length buffer, and every assertion here reads a rendered problem
     * body, so an empty one is a failure to surface rather than a value to decode.
     *
     * @param statusCode  the response status code
     * @param contentType the raw {@code Content-Type} header, or {@code null} when absent
     * @param body        the raw response body as text, or {@code null} when the response had none
     */
    private record HttpResult(int statusCode, String contentType, String body) {

        JsonObject problem() {
            return new JsonObject(body);
        }

        String bodyText() {
            return String.valueOf(body);
        }
    }

    /** Minimal resource behind the claims validator; it must never run for a rejected token. */
    @Path("/secure")
    public static class SecureResource {

        /**
         * Handles {@code GET /secure}.
         *
         * @return a constant success payload
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String secure() {
            return "ok";
        }
    }

    /**
     * API-scoped middleware standing in for JWT bearer authentication: it sets an authenticated
     * Vert.x {@link User} whose principal carries the claims the validator inspects. It runs at
     * router level, ahead of every per-operation handler, so the claims-validator handler
     * contributed by {@link JwtClaimsValidatorContributor} sees a populated {@code ctx.user()} —
     * the same precondition the real bearer scheme handler establishes.
     */
    private static final class StubBearerAuthMiddleware implements Middleware {

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
            String authorization = ctx.request().getHeader("Authorization");
            if (authorization == null || !authorization.startsWith("Bearer ")) {
                ctx.next();
                return;
            }
            String subject = authorization.substring("Bearer ".length()).trim();
            JsonObject principal = new JsonObject().put("sub", subject).put("tenant", "4711");
            ((UserContextInternal) ctx.userContext()).setUser(User.create(principal));
            ctx.next();
        }
    }

    /**
     * Recording no-op {@link CredentialRejectionReporter}. The real reporter emits a security event
     * and never touches the response, so recording the reason code is enough to prove which path
     * produced the response while keeping this test focused on the failure rendering.
     */
    private static final class RecordingRejectionReporter implements CredentialRejectionReporter {

        @Override
        public void report(
                RoutingContext ctx,
                AuthMethod attemptedMethod,
                Optional<String> credentialId,
                Optional<VerificationSource> verificationSource,
                String reasonCode,
                Map<String, Object> safeAttributes) {
            REPORTED_REASON_CODES.add(reasonCode);
        }
    }

    /** Minimal {@code String} response encoder so the success path has a body encoder. */
    private static final class StringEncoder implements ResponseBodyEncoder {

        @Override
        public boolean canEncode(Class<?> entityType, String contentType) {
            return entityType == String.class;
        }

        @Override
        public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
            return new BufferedBody(Buffer.buffer(String.valueOf(entity)), "text/plain", null);
        }

        @Override
        public int priority() {
            return 1000;
        }
    }

    /** Minimal JSON encoder for the problem body. */
    private static final class ProblemJsonEncoder implements ResponseBodyEncoder {

        @Override
        public boolean canEncode(Class<?> entityType, String contentType) {
            return contentType == null || contentType.contains("json");
        }

        @Override
        public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
            return new BufferedBody(Buffer.buffer(Json.encode(entity)), "application/problem+json", null);
        }

        @Override
        public int priority() {
            return 2000;
        }
    }

    /**
     * Builds the mount factory under test: the framework's real exception defaults
     * ({@link RestModule#defaultExceptionMapper()}), the stub bearer authenticator, and the real
     * {@link JwtClaimsValidatorContributor} carrying a validator that rejects the token.
     *
     * @return a factory producing the mount this test posts against
     */
    private static JaxRsRouterMount.Factory buildFactory() {
        JwtClaimsValidator claimsValidator = claims -> {
            throw new IllegalArgumentException(REJECTION_MESSAGE);
        };
        JwtClaimsValidatorContributor claimsContributor = new JwtClaimsValidatorContributor(
                claimsValidator,
                new RecordingRejectionReporter(),
                JwtValidationConfig.builder().build());

        ExceptionMapperRegistry registry = new ExceptionMapperRegistry(RestModule.defaultExceptionMapper(), Set.of());
        List<ResponseBodyEncoder> encoders = List.of(new StringEncoder(), new ProblemJsonEncoder());
        HttpConfig httpConfig = HttpConfig.builder().build();
        JaxRsConfig jaxRsConfig = JaxRsConfig.builder()
                .validationStrategy(NoneValidationStrategy.ID)
                .build();

        return new JaxRsRouterMount.Factory(
                Set.of(), // routerLifecycleHooks
                Set.of(), // operationInterceptors
                Set.of(), // errorInterceptors
                Set.of(new StubBearerAuthMiddleware()), // middlewares — authenticates the caller
                Set.of(claimsContributor), // operationHandlerContributors — the rejection under test
                Set.of(), // securitySchemeHandlers
                Set.of(), // requestInterceptors
                new RestExceptionMapper(),
                registry,
                Set.of(), // responseProducerBindings
                new DefaultResponseSerializer(List.of(), encoders),
                new RestContextResolution(Set.of()),
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
