// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.vertique.core.json.JsonMapperProfile;
import dev.vertique.core.json.JsonProfile;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.json.JsonMapperProfiles;
import dev.vertique.json.VertxJsonSupport;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.ResponseSerializer;
import dev.vertique.rest.jaxrs.DefaultExceptionMapper;
import dev.vertique.rest.jaxrs.DefaultResponseSerializer;
import dev.vertique.rest.jaxrs.ExceptionMapperRegistry;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.JsonBodyEncoderTestAccess;
import dev.vertique.rest.jaxrs.RestExceptionMapper;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import io.vertx.ext.web.Router;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * RED integration test for Phase 3 Slice 3.1 (FR-JSON-055/056/057): JAX-RS JSON <em>response</em>
 * serialization must become symmetric with request binding — a resource method's response should
 * serialize through the same effective profile resolved for its request (the per-method mapper the
 * request path already stashes under {@code BoundRequest.KEY_RESOLVED_BODY_MAPPER}), while a method
 * with no profile and no configured default stays byte-for-byte on the {@code vertx} mapper
 * ({@code Json.encode}).
 *
 * <p>The encoder under test is the <strong>production</strong> {@code JsonBodyEncoder}, obtained via
 * {@link JsonBodyEncoderTestAccess} (a same-package test seam over the package-private encoder), so the
 * RED→green transition is genuinely driven by the production encoder's behavior — not a re-implemented
 * stub. Today {@code JsonBodyEncoder.encode} calls {@code Json.encode(entity)} unconditionally and
 * ignores the stash, so the <em>profiled</em> test below FAILS (the opinionated profile's observable
 * behavior never reaches the wire). Slice 3.1 makes the encoder read the stash, turning it green.
 *
 * <p>The opinionated {@code response-profile} test profile's observable difference from
 * {@code Json.encode} is that it omits {@code null} fields (a {@code NON_NULL} mix-in scoped to the
 * response entity type). This difference survives the registry's structural round-trip probe because
 * the {@code NON_NULL} inclusion is scoped to the entity class via a mix-in — the probe serializes a
 * null-bearing {@code JsonObject}, which is unaffected. Null-omission is chosen as the observable
 * because {@code Json.encode} renders it <em>successfully</em> (a 200 response carrying
 * {@code "missing":null}), so the RED signal is a clean body-content assertion rather than a coarse
 * encode failure: a {@code java.time} value would make the bare {@code vertx} mapper throw (it lacks
 * jsr310) and the response would never reach the wire, turning the RED into a hang.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class ProfiledResponseSerializationIT {

    private static final String OPINIONATED_PROFILE = "response-profile";

    private HttpServer server;
    private HttpClient client;

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<?> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(ar -> ctx.completeNow());
    }

    // --- Opinionated profile fixture ---

    /**
     * Builds the {@code response-profile} profile: a Jackson mapper with Vert.x JSON support (required
     * by the registry probe and so {@code JsonObject} round-trips), the jsr310 module with
     * {@code WRITE_DATES_AS_TIMESTAMPS} disabled (the opinionated date policy, retained for parity with
     * the framework's {@code vertique} profile), and a {@code NON_NULL} mix-in scoped to
     * {@link ProfiledEntity} (so the entity's null field is omitted while the structural probe's
     * null-bearing {@code JsonObject} is unaffected). The null-omission is the observable difference
     * from the {@code vertx} mapper's {@code Json.encode}.
     *
     * @return the {@code response-profile} profile
     */
    private static JsonMapperProfile opinionatedResponseProfile() {
        ObjectMapper mapper = JsonMapper.builder()
                .addModule(new JavaTimeModule())
                .addModule(VertxJsonSupport.module())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build();
        mapper.addMixIn(ProfiledEntity.class, NonNullMixin.class);
        return JsonMapperProfiles.of(JsonProfileId.of(OPINIONATED_PROFILE), mapper);
    }

    /** Mix-in applying {@code NON_NULL} inclusion to the response entity only (not the probe samples). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    abstract static class NonNullMixin {}

    // --- Entity fixtures ---

    /**
     * Response entity carrying a present {@code name} and a {@code null} {@code missing} field. Under the
     * opinionated profile (via the {@code NON_NULL} mix-in) the null field is omitted; under
     * {@code Json.encode} the null is included.
     */
    public static class ProfiledEntity {
        public String name;
        public String missing;
    }

    /**
     * Response entity with a present {@code name} and a {@code null} {@code missing} field. Used by the
     * vertx-unchanged invariant test, whose body must equal {@code Json.encode(entity)}.
     */
    public static class PlainEntity {
        public String name;
        public String missing;
    }

    // --- Resource fixtures ---

    /**
     * Resource selecting the opinionated {@code response-profile} at the class level. Its GET returns a
     * {@link ProfiledEntity} (null field) as an {@code application/json} response; the effective response
     * serialization must run through the profile mapper, which omits the null field.
     */
    @Path("/profiled")
    @JsonProfile(OPINIONATED_PROFILE)
    public static class ProfiledResource {

        /**
         * Returns a {@link ProfiledEntity} with a null field.
         *
         * @return a 200 {@code application/json} response wrapping the entity
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(operationId = "profiledResponse")
        public Response get() {
            ProfiledEntity entity = new ProfiledEntity();
            entity.name = "alice";
            entity.missing = null;
            return Response.ok(entity).type(MediaType.APPLICATION_JSON).build();
        }
    }

    /**
     * Resource with NO {@code @JsonProfile} (and no configured default), so the effective response
     * profile is {@code vertx}. Its GET returns a {@link PlainEntity} (null field, no date); the
     * response body must be byte-for-byte identical to today's {@code Json.encode(entity)}.
     */
    @Path("/plain")
    public static class PlainResource {

        /**
         * Returns a {@link PlainEntity} with a null field.
         *
         * @return a 200 {@code application/json} response wrapping the entity
         */
        @GET
        @Produces(MediaType.APPLICATION_JSON)
        @Operation(operationId = "plainResponse")
        public Response get() {
            PlainEntity entity = new PlainEntity();
            entity.name = "alice";
            entity.missing = null;
            return Response.ok(entity).type(MediaType.APPLICATION_JSON).build();
        }
    }

    // --- Test: a profiled method serializes its response via the profile ---

    @Test
    @DisplayName("A @JsonProfile method serializes its response via the profile (omits the null field)")
    void profiledMethod_serializesViaProfile(Vertx vertx, VertxTestContext ctx) {
        // The class-level @JsonProfile resolves the opinionated mapper, which the request path stashes
        // under KEY_RESOLVED_BODY_MAPPER ahead of dispatch. Once JsonBodyEncoder reads that stash, the
        // response body OMITS the null "missing" field. TODAY the encoder uses Json.encode (vertx) and
        // ignores the stash, so the body still carries "missing":null and this assertion FAILS (the RED
        // signal): the profile's omit-nulls behavior never reaches the wire.
        get(vertx, ctx, new ProfiledResource(), "/profiled", body -> {
            assertTrue(body.contains("\"name\":\"alice\""), "the name field must be present; body was: " + body);
            assertFalse(
                    body.contains("\"missing\""),
                    "the opinionated profile must omit the null field; body was: " + body);
        });
    }

    // --- Test: a vertx (default) method's response is byte-for-byte unchanged ---

    @Test
    @DisplayName("A method with no @JsonProfile serializes its response byte-for-byte via Json.encode (vertx)")
    void vertxMethod_byteForByteUnchanged(Vertx vertx, VertxTestContext ctx) {
        // No @JsonProfile and no configured default => the effective profile is vertx => no mapper is
        // stashed => JsonBodyEncoder must keep calling Json.encode(entity) exactly. The body must equal
        // the test-computed Json.encode of the same entity (null field PRESENT), proving the vertx path
        // is byte-for-byte unchanged (FR-JSON-057). This PASSES today and guards the invariant.
        PlainEntity expected = new PlainEntity();
        expected.name = "alice";
        expected.missing = null;
        String expectedBody = Json.encode(expected);

        get(
                vertx,
                ctx,
                new PlainResource(),
                "/plain",
                body -> assertEquals(
                        expectedBody, body, "the vertx (default) response must be byte-for-byte Json.encode output"));
    }

    // --- Helpers ---

    /**
     * Deploys {@code resource}, issues a {@code GET} to {@code path}, and runs {@code assertion} on the
     * response body string.
     *
     * @param vertx the Vert.x instance
     * @param ctx the test context
     * @param resource the JAX-RS resource to mount
     * @param path the request path
     * @param assertion the assertion on the response body
     */
    private void get(
            Vertx vertx,
            VertxTestContext ctx,
            Object resource,
            String path,
            java.util.function.Consumer<String> assertion) {
        JaxRsRouterMount.Factory factory = buildProfiledResponseFactory();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(resource));
        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0);
                })
                .compose(s -> {
                    server = s;
                    client = vertx.createHttpClient();
                    return client.request(HttpMethod.GET, s.actualPort(), "localhost", path)
                            .compose(req -> req.send())
                            .compose(resp -> resp.body().map(b -> b.toString()));
                })
                .onComplete(ctx.succeeding(body -> {
                    ctx.verify(() -> assertion.accept(body));
                    ctx.completeNow();
                }));
    }

    /**
     * Builds a {@link JaxRsRouterMount.Factory} wired with the real {@code web-validation} strategy, the
     * victools {@link AnnotationSchemaSource}, a {@link DefaultJsonMapperProfileRegistry} carrying the
     * opinionated {@code response-profile}, and — crucially — the <strong>production</strong>
     * {@code JsonBodyEncoder} (via {@link JsonBodyEncoderTestAccess}) as the JSON response encoder, so
     * the response path exercises the real encoder under test. Mirrors
     * {@code ProfiledBodyParseUnderGateIT}'s factory but for the response leg.
     *
     * @return a factory with the opinionated profile registered and the production JSON encoder wired
     */
    private static JaxRsRouterMount.Factory buildProfiledResponseFactory() {
        DefaultExceptionMapper defaultMapper = new DefaultExceptionMapper()
                .on(dev.vertique.rest.core.RestValidationException.class, ex -> Response.status(400)
                        .entity(dev.vertique.rest.core.ValidationProblemDetail.of(ex.getMessage(), ex.errors()))
                        .type("application/problem+json")
                        .build())
                .on(dev.vertique.core.exception.ValidationException.class, ex -> Response.status(400)
                        .entity(dev.vertique.rest.core.ValidationProblemDetail.of(ex.getMessage(), java.util.List.of()))
                        .type("application/problem+json")
                        .build());
        ExceptionMapperRegistry registry = new ExceptionMapperRegistry(defaultMapper, Set.of());
        RestExceptionMapper restExceptionMapper = new RestExceptionMapper();
        RestContextResolution restContextResolution = new RestContextResolution(Set.of());
        // The PRODUCTION JsonBodyEncoder (priority 1100) is the encoder under test; a String encoder
        // (priority 1000) is included for completeness even though these resources return JSON entities.
        ResponseBodyEncoder jsonBodyEncoder = JsonBodyEncoderTestAccess.create();
        List<ResponseBodyEncoder> encoders = List.of(new StringResponseEncoder(), jsonBodyEncoder);
        ResponseSerializer responseSerializer = new DefaultResponseSerializer(List.of(), encoders);
        HttpConfig httpConfig = HttpConfig.builder().build();
        JaxRsConfig jaxRsConfig = JaxRsConfig.builder()
                .validationStrategy(WebValidationStrategy.ID)
                .build();

        RequestValidationStrategy webValidation = new WebValidationStrategy(jaxRsConfig);
        OperationSchemaSource schemaSource = new AnnotationSchemaSource();

        return new JaxRsRouterMount.Factory(
                Set.of(), // routerLifecycleHooks
                Set.of(), // operationInterceptors
                Set.of(), // errorInterceptors
                Set.of(), // middlewares
                Set.of(), // operationHandlerContributors
                Set.of(), // securitySchemeHandlers
                Set.of(), // requestInterceptors
                restExceptionMapper,
                registry,
                Set.of(), // responseProducerBindings
                responseSerializer,
                restContextResolution,
                dev.vertique.rest.jaxrs.convert.ConversionContexts.defaultResolver(), // paramConversionResolver
                null, // securityPolicyValidator
                Optional.empty(), // authEnforcementCapability
                List.of(), // sortedDecoders (no request body on these GETs)
                encoders, // sortedEncoders
                httpConfig,
                jaxRsConfig,
                new DefaultJsonMapperProfileRegistry(Set.of(opinionatedResponseProfile())), // jsonMapperProfileRegistry
                JsonConfig.defaults(), // jsonConfig
                Optional.empty(), // beanValidator
                Optional.empty(), // objectProcessor
                Set.of(), // evidenceCapturers
                Optional.empty(), // actionRegistry
                Optional.empty(), // authorizer
                Set.of(), // fileContentVerifiers
                Set.of(webValidation),
                Optional.of(schemaSource));
    }

    /** Minimal public-SPI String response encoder producing {@code text/plain}. */
    static final class StringResponseEncoder implements ResponseBodyEncoder {
        @Override
        public boolean canEncode(Class<?> entityType, String contentType) {
            return entityType == String.class;
        }

        @Override
        public dev.vertique.rest.core.response.SerializedBody encode(
                io.vertx.ext.web.RoutingContext ctx, Response response, Object entity) {
            return new dev.vertique.rest.core.response.BufferedBody(
                    io.vertx.core.buffer.Buffer.buffer(String.valueOf(entity)), "text/plain", null);
        }

        @Override
        public int priority() {
            return 1000;
        }
    }
}
