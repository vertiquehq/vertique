// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.request.FilePart;
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
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end proof that a named {@link FilePart} constraint is projected into the web-validation
 * gate, rejects a disallowed declared type as a complete problem detail, and preserves binding for a
 * conforming raw multipart request.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class MultipartFilePartValidationIT {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final AtomicBoolean INVOKED = new AtomicBoolean();

    private static HttpServer server;
    private static HttpClient client;

    /** Resource binding the constrained multipart part and exposing enough data to prove binding. */
    @Path("/files")
    public static class FileResource {

        @POST
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "uploadAvatar")
        public String upload(
                @FormParam("avatar") @FilePart(allowedTypes = {"image/png"}) FileUpload avatar,
                @FormParam("caption") @Size(min = 3) String caption) {
            INVOKED.set(true);
            if (avatar == null) {
                return "missing";
            }
            return avatar.name() + ":" + avatar.contentType() + ":" + avatar.size();
        }
    }

    @BeforeAll
    static void setUp(Vertx vertx, VertxTestContext ctx) {
        JaxRsRouterMount mount = buildWebValidationFactory().create("/*", "openapi.json", Set.of(new FileResource()));

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

    @BeforeEach
    void resetInvocationProbe() {
        INVOKED.set(false);
    }

    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        Future<?> serverClose = server != null ? server.close() : Future.succeededFuture();
        Future<?> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future.join(serverClose, clientClose).onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @Test
    @DisplayName("A disallowed declared file type returns 400 problem+json and does not invoke the resource")
    void disallowedDeclaredTypeRejected400(VertxTestContext ctx) {
        Buffer body =
                MultipartBodies.singleFile("avatar", "payload.exe", "application/x-msdownload", new byte[] {'M', 'Z'});

        postMultipart(body).onComplete(ctx.succeeding(result -> {
            ctx.verify(() -> {
                assertEquals(400, result.statusCode());
                assertTrue(
                        result.contentType().contains("application/problem+json"),
                        "file validation failure must use application/problem+json");

                JsonObject problem = result.body().toJsonObject();
                JsonArray errors = problem.getJsonArray("errors");
                assertEquals(1, errors.size(), "the request has exactly one file-part violation");
                ValidationErrorDetail actual = errors.getJsonObject(0).mapTo(ValidationErrorDetail.class);
                assertEquals(
                        new ValidationErrorDetail(
                                "avatar",
                                "file part content type is not allowed",
                                "file",
                                "fileContentTypeNotAllowed",
                                Map.of("allowedTypes", List.of("image/png"))),
                        actual);
                assertFalse(INVOKED.get(), "the resource must not run after file-part validation fails");
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("A malformed multipart file Content-Type returns the frozen 400 detail")
    void malformedDeclaredTypeRejected400(VertxTestContext ctx) {
        Buffer body = MultipartBodies.singleFile("avatar", "pixel.png", "image / png", PNG_SIGNATURE);

        postMultipart(body).onComplete(ctx.succeeding(result -> {
            ctx.verify(() -> {
                assertEquals(400, result.statusCode());
                JsonArray errors = result.body().toJsonObject().getJsonArray("errors");
                assertEquals(1, errors.size());
                ValidationErrorDetail actual = errors.getJsonObject(0).mapTo(ValidationErrorDetail.class);
                assertEquals(
                        new ValidationErrorDetail(
                                "avatar",
                                "file part declares a malformed or wildcard content type",
                                "file",
                                "fileContentTypeMalformed",
                                Map.of("allowedTypes", List.of("image/png"))),
                        actual);
                assertFalse(INVOKED.get(), "the resource must not run after malformed file metadata");
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("A PNG-declared multipart file is bound and invokes the resource")
    void conformingMultipartInvokesResource(VertxTestContext ctx) {
        Buffer body = MultipartBodies.singleFile("avatar", "pixel.png", "image/png", PNG_SIGNATURE);

        postMultipart(body).onComplete(ctx.succeeding(result -> {
            ctx.verify(() -> {
                assertEquals(200, result.statusCode());
                assertEquals("avatar:image/png:8", result.body().toString());
                assertTrue(INVOKED.get(), "a conforming file part must reach the resource");
            });
            ctx.completeNow();
        }));
    }

    @Test
    @DisplayName("An invalid text form field in multipart is rejected before resource invocation")
    void invalidTextFormFieldRejectedUnderMultipart(VertxTestContext ctx) {
        Buffer body =
                MultipartBodies.fileAndTextField("avatar", "pixel.png", "image/png", PNG_SIGNATURE, "caption", "x");

        postMultipart(body).onComplete(ctx.succeeding(result -> {
            ctx.verify(() -> {
                assertEquals(400, result.statusCode());
                assertTrue(
                        result.contentType().contains("application/problem+json"),
                        "form validation failure must use application/problem+json");

                JsonArray errors = result.body().toJsonObject().getJsonArray("errors");
                assertEquals(1, errors.size(), "the request has exactly one text-form violation");
                ValidationErrorDetail actual = errors.getJsonObject(0).mapTo(ValidationErrorDetail.class);
                assertEquals(
                        new ValidationErrorDetail(
                                "caption",
                                "must have a minimum length of 3",
                                "form",
                                "minLength",
                                Map.of("minLength", 3)),
                        actual);
                assertFalse(INVOKED.get(), "the resource must not run after text-form validation fails");
            });
            ctx.completeNow();
        }));
    }

    private static Future<HttpResult> postMultipart(Buffer body) {
        return client.request(HttpMethod.POST, server.actualPort(), "localhost", "/files")
                .compose(request -> request.putHeader("Content-Type", MultipartBodies.contentType())
                        .send(body))
                .compose(response -> {
                    int statusCode = response.statusCode();
                    String contentType = response.getHeader("Content-Type");
                    return response.body().map(responseBody -> new HttpResult(statusCode, contentType, responseBody));
                });
    }

    private record HttpResult(int statusCode, String contentType, Buffer body) {}

    private static JaxRsRouterMount.Factory buildWebValidationFactory() {
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
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(),
                restExceptionMapper,
                registry,
                Set.of(),
                responseSerializer,
                restContextResolution,
                dev.vertique.rest.jaxrs.convert.ConversionContexts.defaultResolver(),
                null,
                Optional.empty(),
                List.of(),
                encoders,
                httpConfig,
                jaxRsConfig,
                new DefaultJsonMapperProfileRegistry(Set.of()),
                JsonConfig.defaults(),
                Optional.empty(),
                Optional.empty(),
                Set.of(),
                Optional.empty(),
                Optional.empty(),
                Set.of(),
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

    /** Minimal public-SPI JSON encoder for the validation problem body. */
    static final class JsonEncoder implements ResponseBodyEncoder {
        @Override
        public boolean canEncode(Class<?> entityType, String contentType) {
            return contentType == null || contentType.contains("json");
        }

        @Override
        public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
            return new BufferedBody(Buffer.buffer(Json.encode(entity)), "application/json", null);
        }

        @Override
        public int priority() {
            return 1100;
        }
    }
}
