// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.response.BufferedBody;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.ResponseSerializer;
import dev.vertique.rest.core.response.SerializedBody;
import dev.vertique.rest.jaxrs.DefaultExceptionMapper;
import dev.vertique.rest.jaxrs.DefaultResponseSerializer;
import dev.vertique.rest.jaxrs.ExceptionMapperRegistry;
import dev.vertique.rest.jaxrs.JaxRsRouterMount;
import dev.vertique.rest.jaxrs.RestExceptionMapper;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import dev.vertique.rest.jaxrs.validation.FileVerificationResult;
import dev.vertique.rest.jaxrs.validation.OperationSchemaSource;
import dev.vertique.rest.jaxrs.validation.RequestValidationStrategy;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.Json;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/** Proves asynchronous file verification never blocks the request event loop. */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class FileVerifierEventLoopNonStallIT {

    private static Vertx vertx;
    private static HttpClient client;

    private HttpServer server;

    @BeforeAll
    static void setUpClient() {
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        client = vertx.createHttpClient();
    }

    @AfterEach
    void tearDownServer(VertxTestContext ctx) {
        Future<?> close = server != null ? server.close() : Future.succeededFuture();
        close.onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @AfterAll
    static void tearDownClient(VertxTestContext ctx) {
        Future<?> clientClose = client != null ? client.close() : Future.succeededFuture();
        Future<?> vertxClose = vertx != null ? vertx.close() : Future.succeededFuture();
        Future.join(clientClose, vertxClose).onComplete(ctx.succeeding(v -> ctx.completeNow()));
    }

    @Test
    @DisplayName("A pending file verifier does not stall another request on the same event loop")
    void concurrentRequestNotStalledDuringVerification(VertxTestContext ctx) {
        Checkpoint verifyStarted = ctx.checkpoint();
        Checkpoint pingCompleted = ctx.checkpoint();
        Checkpoint uploadCompleted = ctx.checkpoint();
        GatedVerifier verifier = new GatedVerifier(verifyStarted);
        NonStallResource resource = new NonStallResource();

        startServer(verifier, resource).onComplete(ctx.succeeding(listeningServer -> {
            server = listeningServer;
            Future<HttpResult> uploadRequest = postMultipart();
            uploadRequest.onComplete(ctx.succeeding(result -> ctx.verify(() -> {
                assertEquals(200, result.statusCode());
                assertEquals("uploaded", result.body().toString());
                assertTrue(resource.uploadInvoked.get(), "the upload resource must run after verifier release");
                uploadCompleted.flag();
            })));

            verifier.started().compose(ignored -> getPing()).onComplete(ctx.succeeding(ping -> {
                try {
                    ctx.verify(() -> {
                        assertEquals(200, ping.statusCode());
                        assertEquals("pong", ping.body().toString());
                        assertFalse(uploadRequest.isComplete(), "upload A must still be gated when /ping completes");
                        assertFalse(
                                verifier.isReleased(), "the verifier gate must remain closed until /ping is proven");
                        assertSame(
                                verifier.verifyThread.get(),
                                resource.pingThread.get(),
                                "verification and /ping must execute on the same event-loop thread");
                        pingCompleted.flag();
                    });
                } finally {
                    verifier.release();
                }
            }));
        }));
    }

    private Future<HttpServer> startServer(GatedVerifier verifier, NonStallResource resource) {
        JaxRsRouterMount mount = buildFactory(Set.of(verifier)).create("/*", "openapi.json", Set.of(resource));
        return mount.createRouter(vertx).compose(apiRouter -> {
            Router root = Router.router(vertx);
            root.route("/*").subRouter(apiRouter);
            return vertx.createHttpServer().requestHandler(root).listen(0);
        });
    }

    private Future<HttpResult> postMultipart() {
        Buffer body = MultipartBodies.singleFile(
                "upload", "payload.bin", MediaType.APPLICATION_OCTET_STREAM, new byte[] {1, 2, 3});
        return client.request(HttpMethod.POST, server.actualPort(), "localhost", "/files")
                .compose(request -> request.putHeader("Content-Type", MultipartBodies.contentType())
                        .send(body))
                .compose(response -> {
                    int statusCode = response.statusCode();
                    return response.body().map(responseBody -> new HttpResult(statusCode, responseBody));
                });
    }

    private Future<HttpResult> getPing() {
        return client.request(HttpMethod.GET, server.actualPort(), "localhost", "/ping")
                .compose(request -> request.send())
                .compose(response -> {
                    int statusCode = response.statusCode();
                    return response.body().map(responseBody -> new HttpResult(statusCode, responseBody));
                });
    }

    private record HttpResult(int statusCode, Buffer body) {}

    /** Resource exposing the gated upload and an independent event-loop probe. */
    @Path("/")
    public static class NonStallResource {

        private final AtomicBoolean uploadInvoked = new AtomicBoolean();
        private final AtomicReference<Thread> pingThread = new AtomicReference<>();

        @POST
        @Path("files")
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "verifyUploadWithoutStalling")
        public String upload(@FormParam("upload") FileUpload upload) {
            uploadInvoked.set(true);
            return "uploaded";
        }

        @GET
        @Path("ping")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "pingDuringFileVerification")
        public String ping() {
            pingThread.set(Thread.currentThread());
            return "pong";
        }
    }

    /** Verifier that remains incomplete until the test has proven /ping responsiveness. */
    private static final class GatedVerifier implements FileContentVerifier {

        private final Checkpoint verifyStarted;
        private final Promise<Void> started = Promise.promise();
        private final Promise<Void> release = Promise.promise();
        private final AtomicReference<Thread> verifyThread = new AtomicReference<>();

        private GatedVerifier(Checkpoint verifyStarted) {
            this.verifyStarted = verifyStarted;
        }

        @Override
        public Future<FileVerificationResult> verify(FileUpload part) {
            verifyThread.set(Thread.currentThread());
            verifyStarted.flag();
            started.tryComplete();
            return release.future().map(ignored -> FileVerificationResult.accepted());
        }

        Future<Void> started() {
            return started.future();
        }

        boolean isReleased() {
            return release.future().isComplete();
        }

        void release() {
            release.tryComplete();
        }
    }

    private static JaxRsRouterMount.Factory buildFactory(Set<FileContentVerifier> verifiers) {
        DefaultExceptionMapper defaultMapper = new DefaultExceptionMapper();
        ExceptionMapperRegistry registry = new ExceptionMapperRegistry(defaultMapper, Set.of());
        RestExceptionMapper restExceptionMapper = new RestExceptionMapper();
        RestContextResolution restContextResolution = new RestContextResolution(Set.of());
        List<ResponseBodyEncoder> encoders = List.of(new StringEncoder(), new JsonEncoder());
        ResponseSerializer responseSerializer = new DefaultResponseSerializer(List.of(), encoders);
        HttpConfig httpConfig = HttpConfig.builder().build();
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

    /** Minimal JSON response encoder for mapped errors. */
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
