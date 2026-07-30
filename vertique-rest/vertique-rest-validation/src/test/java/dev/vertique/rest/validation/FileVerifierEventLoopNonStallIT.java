// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import dev.vertique.rest.jaxrs.validation.FileVerificationResult;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMounts;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.ext.web.FileUpload;
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

/**
 * Proves asynchronous file verification never blocks the request event loop.
 *
 * <p>Built through {@link MountFixtures} over {@link ValidationMountComponent} (the {@code
 * vertique-rest-test} fixture), so the mount carries the real injected {@link WebValidationStrategy}
 * and the full set of production middlewares. Neither request exercised here throws, so the richer
 * rule set on the production {@code DefaultExceptionMapper} (versus this file's previous bare {@code
 * new DefaultExceptionMapper()}) has no observable effect on the assertions below.
 */
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
        RestTestContributions contributions =
                RestTestContributions.builder().addFileContentVerifier(verifier).build();
        return RestTestMounts.startServer(vertx, MountFixtures.factory(vertx, contributions), Set.of(resource));
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
}
