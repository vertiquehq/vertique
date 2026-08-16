// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.request.FilePart;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMounts;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end proof for the framework-owned lifecycle of multipart upload temporary files, for the
 * outcomes observable once the exchange has completed.
 *
 * <p>Every test uses its own uploads directory. {@link UploadPathCapture} runs immediately after the
 * mount's {@code BodyHandler}, so it records the exact path created by Vert.x even when the
 * validation gate rejects the request before resource invocation.
 *
 * <p>Requests go through a {@link WebClient}, the repository default. Nothing here observes the
 * response while it is still streaming: each test sends a complete multipart body, waits for the
 * exchange to settle, and only then polls the filesystem for the deletion. That is precisely the
 * shape a buffered client expresses, so these tests carry no raw-client exemption. The lifecycle
 * facts that <em>are</em> only observable mid-stream — the upload surviving until the last response
 * byte, and cleanup after a {@code SO_LINGER(0)} reset — live in {@code UploadTempFileCleanupIT},
 * which keeps the raw client for exactly those two tests.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class UploadTempFileCleanupWebClientIT {

    private static final byte[] PAYLOAD = "streamed-upload-payload".getBytes(StandardCharsets.UTF_8);
    private static final long ASYNC_TIMEOUT_SECONDS = 5;

    private static Vertx vertx;
    private static WebClient client;

    private HttpServer server;
    private java.nio.file.Path uploadsDirectory;

    /**
     * Creates the class-scoped {@link WebClient}. It is bound to a static field so
     * {@link #tearDownClient} can close it; an unbound client can never be closed at all.
     *
     * @param injectedVertx the class-scoped Vert.x instance injected by vertx-junit5
     */
    @BeforeAll
    static void setUpClient(Vertx injectedVertx) {
        vertx = injectedVertx;
        // Redirects off: parity with the raw client, which never follows them.
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
    }

    /**
     * Closes the shared {@link WebClient} before the extension-owned {@link Vertx} instance is closed.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is no future to await here.
     *
     * @param ctx the test context used for async teardown assertion
     */
    @AfterAll
    static void tearDownClient(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        ctx.completeNow();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        RestTestMounts.deleteRecursively(uploadsDirectory);
    }

    @Test
    @DisplayName("A normally completed response deletes its exact multipart temporary file")
    void tempFileDeletedAfterSuccess() throws Exception {
        uploadsDirectory = uniqueUploadsDirectory("tempFileDeletedAfterSuccess");
        UploadPathCapture capture = startServer(uploadsDirectory);

        HttpResult result = postMultipart("/cleanup/success", "application/octet-stream", PAYLOAD);
        java.nio.file.Path uploadedPath = capture.awaitPath();

        assertEquals(200, result.statusCode());
        assertEquals("ok", result.body().toString());
        assertTrue(capture.existedWhenCaptured(), "the interceptor must observe the exact spooled file");
        awaitDeleted(uploadedPath);
    }

    @Test
    @DisplayName("A validation-gate rejection deletes its exact multipart temporary file")
    void tempFileDeletedAfterGateRejection() throws Exception {
        uploadsDirectory = uniqueUploadsDirectory("tempFileDeletedAfterGateRejection");
        UploadPathCapture capture = startServer(uploadsDirectory);

        HttpResult result = postMultipart("/cleanup/reject", "application/x-msdownload", new byte[] {'M', 'Z'});
        java.nio.file.Path uploadedPath = capture.awaitPath();

        assertEquals(400, result.statusCode());
        assertTrue(capture.existedWhenCaptured(), "the interceptor must capture the upload before gate rejection");
        awaitDeleted(uploadedPath);
    }

    @Test
    @DisplayName("HttpConfig uploadsDirectory controls where BodyHandler spools multipart files")
    void customUploadsDirectoryHonored() throws Exception {
        uploadsDirectory = uniqueUploadsDirectory("customUploadsDirectoryHonored");
        UploadPathCapture capture = startServer(uploadsDirectory);

        HttpResult result = postMultipart("/cleanup/success", "application/octet-stream", PAYLOAD);
        java.nio.file.Path uploadedPath = capture.awaitPath().toAbsolutePath().normalize();

        assertEquals(200, result.statusCode());
        assertTrue(capture.existedWhenCaptured(), "the configured directory must contain the upload during handling");
        assertEquals(
                uploadsDirectory.toAbsolutePath().normalize(),
                uploadedPath.getParent(),
                "the exact spooled file must be created directly in the configured directory");
    }

    private UploadPathCapture startServer(java.nio.file.Path directory) {
        UploadPathCapture capture = new UploadPathCapture();
        RestTestContributions contributions =
                RestTestContributions.builder().addRequestInterceptor(capture).build();
        JsonObject config =
                new JsonObject().put("http", new JsonObject().put("uploadsDirectory", directory.toString()));

        server = RestTestMounts.startServerBlocking(
                vertx,
                MountFixtures.mount(vertx, config, contributions),
                Set.of(new CleanupResource(capture)),
                Duration.ofSeconds(ASYNC_TIMEOUT_SECONDS));
        return capture;
    }

    /**
     * POSTs a single-file multipart body and returns the settled response.
     *
     * <p>The body is sent verbatim through {@code sendBuffer} rather than re-expressed as a
     * {@code MultipartForm}: the exact bytes {@link MultipartBodies} assembles are what the upload
     * path is exercised against.
     *
     * <p>An absent body is normalised to a zero-length {@link Buffer}, because a {@link WebClient}
     * reports one as {@code null} where the raw client reported an empty buffer. Keeping a
     * {@link Buffer} in {@link HttpResult} preserves byte-for-byte parity with what these tests
     * previously observed; wrapping the text instead would have made a body-less response read as the
     * literal {@code "null"}.
     *
     * @param path         the request path
     * @param declaredType the {@code Content-Type} declared on the file part
     * @param content      the raw file-part content
     * @return the response status code and body
     * @throws Exception if the exchange does not settle within the async budget
     */
    private HttpResult postMultipart(String path, String declaredType, byte[] content) throws Exception {
        Buffer body = multipart(declaredType, content);
        return client.post(server.actualPort(), "127.0.0.1", path)
                .putHeader("Content-Type", MultipartBodies.contentType())
                .sendBuffer(body)
                .map(response -> new HttpResult(
                        response.statusCode(), response.body() == null ? Buffer.buffer() : response.body()))
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    private static Buffer multipart(String declaredType, byte[] content) {
        return MultipartBodies.singleFile("upload", "payload.bin", declaredType, content);
    }

    private static java.nio.file.Path uniqueUploadsDirectory(String testName) {
        return java.nio.file.Path.of(
                "target", "file-uploads", "UploadTempFileCleanupWebClientIT", testName + "-" + UUID.randomUUID());
    }

    private static void awaitDeleted(java.nio.file.Path uploadedPath) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Files.exists(uploadedPath) && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertFalse(Files.exists(uploadedPath), "temporary upload was not deleted: " + uploadedPath);
    }

    /** JAX-RS fixture exposing the success and gate-rejection outcomes. */
    @Path("/cleanup")
    public static class CleanupResource {

        private final UploadPathCapture capture;

        private CleanupResource(UploadPathCapture capture) {
            this.capture = capture;
        }

        @POST
        @Path("/success")
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "cleanupSuccess")
        public String success(@FormParam("upload") FileUpload upload) {
            capture.capture(upload);
            return "ok";
        }

        @POST
        @Path("/reject")
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "cleanupGateRejection")
        public String reject(@FormParam("upload") @FilePart(allowedTypes = {"image/png"}) FileUpload upload) {
            capture.capture(upload);
            return "unexpected";
        }
    }

    /** Captures the exact file produced by BodyHandler before validation or resource invocation. */
    private static final class UploadPathCapture implements RequestInterceptor {

        private final CompletableFuture<CaptureObservation> observed = new CompletableFuture<>();

        @Override
        public void onRequest(RoutingContext rc) {
            if (!rc.fileUploads().isEmpty()) {
                capture(rc.fileUploads().getFirst());
            }
        }

        void capture(FileUpload upload) {
            java.nio.file.Path path = java.nio.file.Path.of(upload.uploadedFileName());
            observed.complete(new CaptureObservation(path, Files.exists(path)));
        }

        java.nio.file.Path awaitPath() throws Exception {
            return observed.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS).path();
        }

        boolean existedWhenCaptured() throws Exception {
            return observed.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS).existed();
        }
    }

    private record CaptureObservation(java.nio.file.Path path, boolean existed) {}

    private record HttpResult(int statusCode, Buffer body) {}
}
