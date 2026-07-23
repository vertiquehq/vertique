// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.response.BufferedBody;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.ResponseSerializer;
import dev.vertique.rest.core.response.SerializedBody;
import dev.vertique.rest.jaxrs.DefaultExceptionMapper;
import dev.vertique.rest.jaxrs.DefaultResponseSerializer;
import dev.vertique.rest.jaxrs.ExceptionMapperRegistry;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.RestExceptionMapper;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end tests for the default {@code web-validation} gate wired into a plain-{@link Router}
 * {@link JaxRsRouterMount} (PRD-REST-017 slice 9). These build a real mount with the
 * {@link WebValidationStrategy} (selected by id {@code "web-validation"}) and the victools-backed
 * {@link AnnotationSchemaSource}, then drive HTTP requests:
 *
 * <ul>
 *   <li>a body violating a {@code minLength} constraint yields a 400 {@code application/problem+json}
 *       and the resource method is never invoked;</li>
 *   <li>repeated header values for a {@code List<String>} header param bind end-to-end under the gate
 *       (the all-values rule), proving binding still runs after the gate.</li>
 * </ul>
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class WebValidationGateIT {

    private HttpServer server;
    private HttpClient client;

    @AfterEach
    void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<?> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(ar -> ctx.completeNow());
    }

    // --- Test 4: web-validation gate rejects a body violating minLength ---

    /** Body bean whose {@code name} carries a {@code minLength: 3} schema constraint. */
    public static class CreateRequest {
        @Schema(minLength = 3)
        public String name;
    }

    /** Resource whose POST records whether it was invoked (to prove the gate blocks it). */
    @Path("/create")
    public static class CreateResource {
        private final AtomicBoolean invoked;

        CreateResource(AtomicBoolean invoked) {
            this.invoked = invoked;
        }

        /**
         * Records invocation and echoes the name. Should NOT run for an invalid body.
         *
         * @param request the request body bean
         * @return the echoed name
         */
        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "create")
        public String create(CreateRequest request) {
            invoked.set(true);
            return "name=" + request.name;
        }
    }

    @Test
    @DisplayName("web-validation gate returns 400 problem+json for a minLength violation; resource not invoked")
    void webValidationDefaultGateIsInstalled(Vertx vertx, VertxTestContext ctx) {
        AtomicBoolean invoked = new AtomicBoolean(false);
        JaxRsRouterMount.Factory factory = buildWebValidationFactory();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new CreateResource(invoked)));

        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0);
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    client = vertx.createHttpClient();
                    client.request(HttpMethod.POST, s.actualPort(), "localhost", "/create")
                            .compose(req -> req.putHeader("Content-Type", "application/json")
                                    .send("{\"name\":\"AB\"}"))
                            .compose(resp -> resp.body().map(b ->
                                    new Object[] {resp.statusCode(), resp.getHeader("Content-Type"), b.toString()}))
                            .onComplete(ctx.succeeding(arr -> {
                                ctx.verify(() -> {
                                    assertEquals(400, arr[0], "minLength violation must be a 400");
                                    assertTrue(
                                            ((String) arr[1]).contains("application/problem+json"),
                                            "error body must be problem+json; was " + arr[1]);
                                    assertFalse(
                                            invoked.get(), "resource method must not be invoked when the gate fails");
                                });
                                ctx.completeNow();
                            }));
                }));
    }

    // --- Test 14: repeated header values bind under the web-validation gate ---

    /** Resource binding a repeated header into a {@code List<String>} under the gate. */
    @Path("/tags")
    public static class TagsResource {

        /**
         * Echoes the bound list of repeated {@code X-Tag} headers.
         *
         * @param tags the repeated header values
         * @return the bound list echoed back
         */
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "tags")
        public String tags(@HeaderParam("X-Tag") List<String> tags) {
            return "tags=" + tags;
        }
    }

    @Test
    @DisplayName("Repeated header values bind to a List end-to-end under the web-validation gate")
    void repeatedHeaderValuesCollectionParamUnderWebValidation(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount.Factory factory = buildWebValidationFactory();
        JaxRsRouterMount mount = factory.create("/*", "openapi.json", Set.of(new TagsResource()));

        mount.createRouter(vertx)
                .compose(apiRouter -> {
                    Router root = Router.router(vertx);
                    root.route("/*").subRouter(apiRouter);
                    return vertx.createHttpServer().requestHandler(root).listen(0);
                })
                .onComplete(ctx.succeeding(s -> {
                    server = s;
                    client = vertx.createHttpClient();
                    client.request(HttpMethod.GET, s.actualPort(), "localhost", "/tags")
                            .compose(req -> {
                                req.headers().add("X-Tag", "a");
                                req.headers().add("X-Tag", "b");
                                return req.send();
                            })
                            .compose(resp -> resp.body())
                            .onComplete(ctx.succeeding(body -> {
                                ctx.verify(() -> assertEquals("tags=[a, b]", body.toString()));
                                ctx.completeNow();
                            }));
                }));
    }

    // --- Factory construction (web-validation strategy + victools schema source) ---

    /**
     * Builds a {@link JaxRsRouterMount.Factory} configured with the {@code web-validation} strategy and
     * the victools {@link AnnotationSchemaSource}, plus minimal public-SPI encoder/decoder test
     * doubles (the rest-jaxrs defaults are package-private and not visible here).
     *
     * @return a factory selecting the web-validation strategy by id
     */
    private static JaxRsRouterMount.Factory buildWebValidationFactory() {
        // Register the framework's RestValidationException -> 400 problem+json mapping (the production
        // RestModule wires this into its DefaultExceptionMapper; mirror just the rule the gate needs).
        DefaultExceptionMapper defaultMapper = new DefaultExceptionMapper()
                .on(dev.vertique.rest.core.RestValidationException.class, ex -> Response.status(400)
                        .entity(dev.vertique.rest.core.ValidationProblemDetail.of(ex.getMessage(), ex.errors()))
                        .type("application/problem+json")
                        .build());
        ExceptionMapperRegistry registry = new ExceptionMapperRegistry(defaultMapper, Set.of());
        RestExceptionMapper restExceptionMapper = new RestExceptionMapper();
        RestContextResolution restContextResolution = new RestContextResolution(Set.of());
        List<ResponseBodyEncoder> encoders = List.of(new StringEncoder(), new JsonEncoder());
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
                List.of(new JsonDecoder()), // sortedDecoders
                encoders, // sortedEncoders
                httpConfig,
                jaxRsConfig,
                new DefaultJsonMapperProfileRegistry(Set.of()), // jsonMapperProfileRegistry
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
    static final class StringEncoder implements ResponseBodyEncoder {
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

    /**
     * Minimal public-SPI JSON encoder for non-String entities (e.g. the error pipeline's
     * {@code ProblemDetail}), mirroring the framework JsonBodyEncoder's {@code json} content-type match.
     */
    static final class JsonEncoder implements ResponseBodyEncoder {
        @Override
        public boolean canEncode(Class<?> entityType, String contentType) {
            return contentType == null || contentType.contains("json");
        }

        @Override
        public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
            return new BufferedBody(Buffer.buffer(io.vertx.core.json.Json.encode(entity)), "application/json", null);
        }

        @Override
        public int priority() {
            return 1100;
        }
    }

    /** Minimal public-SPI JSON request decoder materialising the body bean via Jackson. */
    static final class JsonDecoder implements RequestBodyDecoder {
        @Override
        public boolean canDecode(Class<?> targetType, String contentType) {
            return contentType != null && contentType.contains("json");
        }

        @Override
        public Object decode(
                RoutingContext ctx, dev.vertique.rest.core.request.RequestValue body, Class<?> targetType) {
            JsonObject json = body.getJsonObject();
            return json != null ? json.mapTo(targetType) : null;
        }

        @Override
        public Object decode(
                RoutingContext ctx,
                dev.vertique.rest.core.request.RequestValue body,
                Class<?> targetType,
                Type genericType) {
            return decode(ctx, body, targetType);
        }

        @Override
        public int priority() {
            return 1000;
        }
    }
}
