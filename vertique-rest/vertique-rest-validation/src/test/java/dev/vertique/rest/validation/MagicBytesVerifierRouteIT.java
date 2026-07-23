// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dagger.BindsInstance;
import dagger.Component;
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
import dev.vertique.rest.jaxrs.runtime.MagicBytesVerifierModule;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
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
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** End-to-end proof that the opt-in magic-bytes module participates in the validation gate. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class MagicBytesVerifierRouteIT {

    private static final long ASYNC_TIMEOUT_SECONDS = 5;
    private static final byte[] GENUINE_PNG = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    private static Vertx vertx;
    private static HttpClient client;
    private static Set<FileContentVerifier> verifiers;

    private HttpServer server;
    private java.nio.file.Path uploadsDirectory;

    @BeforeAll
    static void setUpClient(Vertx injectedVertx) {
        vertx = injectedVertx;
        client = vertx.createHttpClient();
        verifiers = DaggerMagicBytesVerifierRouteIT_MagicBytesVerifierComponent.factory()
                .create(vertx)
                .fileContentVerifiers();
    }

    @AfterAll
    static void tearDownClient(VertxTestContext ctx) {
        Future<?> close = client != null ? client.close() : Future.succeededFuture();
        close.onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        deleteRecursively(uploadsDirectory);
    }

    @Test
    @DisplayName("A PNG-declared non-PNG upload is rejected with the frozen verifier detail")
    void pngDeclaredNonPngRejected(VertxTestContext ctx) {
        uploadsDirectory = uniqueUploadsDirectory("pngDeclaredNonPngRejected");
        UploadResource resource = new UploadResource();

        startServer(resource)
                .compose(listeningServer -> {
                    server = listeningServer;
                    return postMultipart(new byte[] {'n', 'o', 't', '-', 'a', '-', 'p', 'n', 'g'});
                })
                .onComplete(ctx.succeeding(result -> {
                    ctx.verify(() -> {
                        assertEquals(400, result.statusCode());
                        assertTrue(result.contentType().contains("application/problem+json"));
                        JsonArray errors = result.body().toJsonObject().getJsonArray("errors");
                        assertEquals(1, errors.size());
                        assertEquals(
                                new ValidationErrorDetail(
                                        "upload",
                                        "file content does not match the declared content type",
                                        "file",
                                        "fileSignatureMismatch",
                                        null),
                                errors.getJsonObject(0).mapTo(ValidationErrorDetail.class));
                        assertFalse(resource.invoked.get(), "a signature rejection must prevent dispatch");
                    });
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("A genuine PNG signature passes the opt-in verifier and reaches the resource")
    void genuinePngPasses(VertxTestContext ctx) {
        uploadsDirectory = uniqueUploadsDirectory("genuinePngPasses");
        UploadResource resource = new UploadResource();

        startServer(resource)
                .compose(listeningServer -> {
                    server = listeningServer;
                    return postMultipart(GENUINE_PNG);
                })
                .onComplete(ctx.succeeding(result -> {
                    ctx.verify(() -> {
                        assertEquals(200, result.statusCode());
                        assertEquals("accepted", result.body().toString());
                        assertTrue(resource.invoked.get(), "an accepted signature must reach dispatch");
                    });
                    ctx.completeNow();
                }));
    }

    private Future<HttpServer> startServer(UploadResource resource) {
        JaxRsRouterMount mount = buildFactory().create("/*", "openapi.json", Set.of(resource));
        return mount.createRouter(vertx).compose(apiRouter -> {
            Router root = Router.router(vertx);
            root.route("/*").subRouter(apiRouter);
            return vertx.createHttpServer().requestHandler(root).listen(0);
        });
    }

    private Future<HttpResult> postMultipart(byte[] content) {
        Buffer body = MultipartBodies.singleFile("upload", "payload.png", "image/png", content);
        return client.request(HttpMethod.POST, server.actualPort(), "localhost", "/files")
                .compose(request -> request.putHeader("Content-Type", MultipartBodies.contentType())
                        .send(body))
                .compose(response -> {
                    int statusCode = response.statusCode();
                    String contentType = response.getHeader("Content-Type");
                    return response.body().map(responseBody -> new HttpResult(statusCode, contentType, responseBody));
                });
    }

    private JaxRsRouterMount.Factory buildFactory() {
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
        HttpConfig httpConfig = HttpConfig.builder()
                .uploadsDirectory(uploadsDirectory.toString())
                .build();
        JaxRsConfig jaxRsConfig = JaxRsConfig.builder()
                .validationStrategy(WebValidationStrategy.ID)
                .build();
        RequestValidationStrategy webValidation = new WebValidationStrategy(
                jaxRsConfig, dev.vertique.rest.jaxrs.convert.ConversionContexts.defaultResolver(), verifiers);
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
                verifiers,
                Set.of(webValidation),
                Optional.of(schemaSource));
    }

    private static java.nio.file.Path uniqueUploadsDirectory(String testName) {
        return java.nio.file.Path.of(
                "target", "file-uploads", "MagicBytesVerifierRouteIT", testName + "-" + UUID.randomUUID());
    }

    private static void deleteRecursively(java.nio.file.Path directory) throws IOException {
        if (directory == null || Files.notExists(directory)) {
            return;
        }
        try (var paths = Files.walk(directory)) {
            for (java.nio.file.Path path :
                    paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record HttpResult(int statusCode, String contentType, Buffer body) {}

    /** Resource accepting only PNG-declared uploads before the magic-bytes check. */
    @Path("/files")
    public static class UploadResource {

        private final AtomicBoolean invoked = new AtomicBoolean();

        @POST
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "verifyPngMagicBytes")
        public String upload(@FormParam("upload") @FilePart(allowedTypes = {"image/png"}) FileUpload upload) {
            invoked.set(true);
            return "accepted";
        }
    }

    /** Test graph proving opt-in through the public module without naming its package-private verifier. */
    @Singleton
    @Component(modules = MagicBytesVerifierModule.class)
    interface MagicBytesVerifierComponent {

        Set<FileContentVerifier> fileContentVerifiers();

        @Component.Factory
        interface Factory {
            MagicBytesVerifierComponent create(@BindsInstance Vertx vertx);
        }
    }

    /** Minimal String response encoder. */
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

    /** Minimal validation-problem JSON encoder. */
    private static final class JsonEncoder implements ResponseBodyEncoder {
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
