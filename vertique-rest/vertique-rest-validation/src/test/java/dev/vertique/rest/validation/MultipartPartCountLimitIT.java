// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.json.DefaultJsonMapperProfileRegistry;
import dev.vertique.json.JsonConfig;
import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.context.RestContextResolution;
import dev.vertique.rest.core.interceptor.ErrorInterceptor;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
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
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Pins the part-count ceiling a multipart request actually meets under the framework's own
 * {@link HttpConfig} defaults, for bodies whose total size stays far below {@code maxBodySize}.
 *
 * <p>The server is created from {@link HttpConfig#toHttpServerOptions()} rather than from default
 * {@link io.vertx.core.http.HttpServerOptions}, so what these tests observe is the limit the
 * framework configures ({@code maxFormFields}), not a Vert.x default that happens to coincide.
 *
 * <p>Every test gets its own server and its own uploads directory, so a spooled-file assertion can
 * only ever see files this test's own request produced.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class MultipartPartCountLimitIT {

    /** The framework's configured {@code maxFormFields} default. */
    private static final int CONFIGURED_FORM_FIELD_LIMIT = 256;

    private static final long ASYNC_TIMEOUT_SECONDS = 10;

    private static Vertx vertx;
    private static HttpClient client;

    private HttpServer server;
    private java.nio.file.Path uploadsDirectory;
    private PartCountCapture capture;
    private FailureCapture failureCapture;

    @BeforeAll
    static void setUpClient(Vertx injectedVertx) {
        vertx = injectedVertx;
        client = vertx.createHttpClient();
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
            server = null;
        }
        deleteRecursively(uploadsDirectory);
    }

    @Test
    @DisplayName("One file part beyond the configured limit is rejected before the resource runs")
    void manySmallFilePartsUnderByteCapObservedLimit() throws Exception {
        startServer("manySmallFileParts");

        HttpResult result = postMultipart(MultipartBodies.parts(CONFIGURED_FORM_FIELD_LIMIT + 1, 0));

        assertEquals(
                400,
                result.statusCode(),
                "257 file parts under the byte cap must not be accepted; observed failure: "
                        + failureCapture.describe());
        assertNull(capture.observation(), "the decoder must reject before any framework handler runs");
    }

    @Test
    @DisplayName("One text form field beyond the configured limit is rejected before the resource runs")
    void manySmallTextPartsUnderByteCapObservedLimit() throws Exception {
        startServer("manySmallTextParts");

        HttpResult result = postMultipart(MultipartBodies.parts(0, CONFIGURED_FORM_FIELD_LIMIT + 1));

        assertEquals(
                400,
                result.statusCode(),
                "257 text form fields under the byte cap must not be accepted; observed failure: "
                        + failureCapture.describe());
        assertNull(capture.observation(), "the decoder must reject before any framework handler runs");
    }

    @Test
    @DisplayName("Text and file parts share one counter — 57 files + 200 fields crosses the same limit")
    void mixedTextAndFilePartsUnderByteCapObservedLimit() throws Exception {
        startServer("mixedTextAndFileParts");

        HttpResult result = postMultipart(MultipartBodies.parts(57, 200));

        assertEquals(
                400,
                result.statusCode(),
                "a shared counter must reject 257 mixed parts; observed failure: " + failureCapture.describe());
        assertNull(capture.observation(), "the decoder must reject before any framework handler runs");
    }

    @Test
    @DisplayName("Exactly the configured number of file parts is accepted and fully bound")
    void partsAtConfiguredLimitAccepted() throws Exception {
        startServer("partsAtConfiguredLimit");

        HttpResult result = postMultipart(MultipartBodies.parts(CONFIGURED_FORM_FIELD_LIMIT, 0));

        assertEquals(200, result.statusCode(), "the boundary case must be accepted, not merely 'not 500'");
        assertEquals("files=" + CONFIGURED_FORM_FIELD_LIMIT, result.body().toString());
        Observation observed = capture.observation();
        assertNotNull(observed, "an accepted body must reach the router pipeline");
        assertEquals(CONFIGURED_FORM_FIELD_LIMIT, observed.fileUploads(), "every file part must be spooled");
        assertEquals(0, observed.formAttributes(), "a file-only body contributes no form attributes");
    }

    @Test
    @DisplayName("A part-count rejection deletes every file it had already spooled")
    void rejectedRequestLeavesNoSpooledFiles() throws Exception {
        startServer("rejectedRequestLeavesNoSpooledFiles");

        postMultipart(MultipartBodies.parts(CONFIGURED_FORM_FIELD_LIMIT + 1, 0));

        // The decoder spools parts as it reads them, so files exist by the time the 257th part trips
        // the limit. The server also keeps draining the body after the response is written, so the
        // count must be allowed to settle before it is trusted — otherwise "still 0" would read as
        // "cleaned up" when it only means "not yet written".
        long remaining = awaitQuiescentSpooledFileCount();

        assertEquals(0, remaining, "a rejected request orphaned " + remaining + " spooled upload(s)");
    }

    // --- Harness ---

    private void startServer(String testName) throws Exception {
        uploadsDirectory = java.nio.file.Path.of(
                "target", "file-uploads", "MultipartPartCountLimitIT", testName + "-" + UUID.randomUUID());
        capture = new PartCountCapture();
        failureCapture = new FailureCapture();

        HttpConfig httpConfig = HttpConfig.builder()
                .uploadsDirectory(uploadsDirectory.toString())
                .build();
        JaxRsRouterMount mount = buildWebValidationFactory(httpConfig, capture, failureCapture)
                .create("/*", "openapi.json", Set.of(new PartsResource()));

        Router apiRouter = mount.createRouter(vertx)
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Router root = Router.router(vertx);
        root.route("/*").subRouter(apiRouter);
        server = vertx.createHttpServer(httpConfig.toHttpServerOptions().setPort(0))
                .requestHandler(root)
                .listen()
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private HttpResult postMultipart(Buffer body) throws Exception {
        return client.request(HttpMethod.POST, server.actualPort(), "localhost", "/parts")
                .compose(request -> request.putHeader("Content-Type", MultipartBodies.contentType())
                        .send(body))
                .compose(response -> {
                    int statusCode = response.statusCode();
                    return response.body().map(responseBody -> new HttpResult(statusCode, responseBody));
                })
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Samples the uploads directory until the file count stops changing for a full settle window,
     * so the result reflects everything the request produced rather than a premature snapshot.
     *
     * @return the settled number of files in this test's uploads directory
     */
    private long awaitQuiescentSpooledFileCount() throws Exception {
        long settleWindowMillis = 500;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long count = spooledFileCount();
        long unchangedSince = System.currentTimeMillis();

        while (System.nanoTime() < deadline) {
            Thread.sleep(50);
            long current = spooledFileCount();
            if (current != count) {
                count = current;
                unchangedSince = System.currentTimeMillis();
            } else if (System.currentTimeMillis() - unchangedSince >= settleWindowMillis) {
                return count;
            }
        }
        return count;
    }

    private long spooledFileCount() throws IOException {
        if (Files.notExists(uploadsDirectory)) {
            return 0;
        }
        try (var paths = Files.list(uploadsDirectory)) {
            return paths.count();
        }
    }

    /**
     * Best-effort teardown. Deliberately never throws: orphaned uploads are what
     * {@link #rejectedRequestLeavesNoSpooledFiles()} asserts on, so teardown must not convert that
     * finding into a misleading class-level error.
     *
     * @param directory the per-test uploads directory, possibly absent
     */
    private static void deleteRecursively(java.nio.file.Path directory) {
        if (directory == null || Files.notExists(directory)) {
            return;
        }
        for (int attempt = 0; attempt < 3 && Files.exists(directory); attempt++) {
            try (var paths = Files.walk(directory)) {
                for (java.nio.file.Path path :
                        paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException | UncheckedIOException e) {
                // A file landing mid-walk; retry.
            }
        }
    }

    /** Resource binding every multipart file part through the aggregate {@code List<FileUpload>} form. */
    @Path("/parts")
    public static class PartsResource {

        @POST
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "countParts")
        public String count(List<FileUpload> files) {
            return "files=" + (files == null ? -1 : files.size());
        }
    }

    /**
     * Records the shape of the failure the error pipeline received: the throwable
     * {@code JaxRsRouterMount.handleFailure} chose to dispatch, the status Vert.x itself set on the
     * context, and whether the {@code HttpException} unwrap branch stashed a status hint. Together
     * these say which branch of {@code handleFailure} a decoder rejection actually takes.
     */
    private static final class FailureCapture implements ErrorInterceptor {

        private final AtomicReference<String> described = new AtomicReference<>();

        @Override
        public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
            described.set("cause=" + throwable.getClass().getName()
                    + ", message=" + throwable.getMessage()
                    + ", ctx.statusCode()=" + rc.statusCode()
                    + ", vertxStatusHint=" + rc.data().get(RequestInterceptor.VERTX_STATUS_CODE_KEY));
            return Future.succeededFuture(throwable);
        }

        String describe() {
            String value = described.get();
            return value == null ? "the error pipeline never ran" : value;
        }
    }

    /** Records the decoder's output for the request currently in flight. */
    private static final class PartCountCapture implements RequestInterceptor {

        private final AtomicReference<Observation> observed = new AtomicReference<>();

        @Override
        public void onRequest(RoutingContext rc) {
            int formAttributes;
            try {
                formAttributes = rc.request().formAttributes().size();
            } catch (RuntimeException e) {
                formAttributes = -1;
            }
            observed.set(new Observation(rc.fileUploads().size(), formAttributes));
        }

        Observation observation() {
            return observed.get();
        }
    }

    private record Observation(int fileUploads, int formAttributes) {}

    private record HttpResult(int statusCode, Buffer body) {}

    private static JaxRsRouterMount.Factory buildWebValidationFactory(
            HttpConfig httpConfig, RequestInterceptor capture, ErrorInterceptor failureCapture) {
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
        JaxRsConfig jaxRsConfig = JaxRsConfig.builder()
                .validationStrategy(WebValidationStrategy.ID)
                .build();
        RequestValidationStrategy webValidation = new WebValidationStrategy(jaxRsConfig);
        OperationSchemaSource schemaSource = new AnnotationSchemaSource();

        return new JaxRsRouterMount.Factory(
                Set.of(),
                Set.of(),
                Set.of(failureCapture),
                Set.of(),
                Set.of(),
                Set.of(),
                Set.of(capture),
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

    /** Minimal public-SPI JSON encoder for problem bodies. */
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
