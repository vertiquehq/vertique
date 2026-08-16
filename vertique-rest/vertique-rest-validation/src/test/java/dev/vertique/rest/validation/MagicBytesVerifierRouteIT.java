// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.request.FilePart;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMount;
import dev.vertique.rest.test.RestTestMounts;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
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
import java.util.Base64;
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

/**
 * End-to-end proof that the opt-in magic-bytes module participates in the validation gate.
 *
 * <p>Requests are issued through a {@link WebClient} rather than a raw {@code HttpClient}
 * deliberately: a raw {@code HttpClientResponse} discards body buffers that arrive before a body
 * handler is attached, so under load {@code body()} can succeed with zero bytes while the status code
 * is correct (issue #167). The rejection test decodes the body as the problem-detail JSON and the
 * acceptance test compares it to {@code "accepted"}, so a silently emptied body would be reported as a
 * magic-bytes verdict that never happened. A {@link WebClient} aggregates the body into its
 * {@code HttpResponse} before completing the send, so the race is closed by construction rather than
 * by every author remembering an idiom.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class MagicBytesVerifierRouteIT {

    private static final long ASYNC_TIMEOUT_SECONDS = 5;
    private static final byte[] GENUINE_PNG = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    private static Vertx vertx;
    private static WebClient client;
    private static Set<FileContentVerifier> verifiers;

    private HttpServer server;
    private java.nio.file.Path uploadsDirectory;

    /**
     * Creates the class-scoped {@link WebClient} and resolves the opt-in magic-bytes verifiers. The
     * client is bound to a static field so {@link #tearDownClient} can close it; an unbound client can
     * never be closed at all.
     *
     * @param injectedVertx the class-scoped Vert.x instance injected by vertx-junit5
     */
    @BeforeAll
    static void setUpClient(Vertx injectedVertx) {
        vertx = injectedVertx;
        // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
        verifiers = DaggerValidationMountComponent_MagicBytesVerifierComponent.factory()
                .create(vertx)
                .fileContentVerifiers();
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
                        JsonArray errors = new JsonObject(result.body()).getJsonArray("errors");
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
        return RestTestMounts.startServer(vertx, buildMount(), Set.of(resource));
    }

    /**
     * POSTs {@code content} to {@code /files} as a single PNG-declared multipart file part.
     *
     * <p>The {@link Buffer} {@link MultipartBodies} pre-encodes is sent verbatim through
     * {@code sendBuffer} rather than re-expressed as a {@code MultipartForm}: the exact bytes are what
     * the magic-bytes verifier is being exercised against.
     *
     * @param content the raw file-part content
     * @return a future of the response status, Content-Type, and body text
     */
    private Future<HttpResult> postMultipart(byte[] content) {
        Buffer body = MultipartBodies.singleFile("upload", "payload.png", "image/png", content);
        return client.post(server.actualPort(), "127.0.0.1", "/files")
                .putHeader("Content-Type", MultipartBodies.contentType())
                .sendBuffer(body)
                .map(response -> new HttpResult(
                        response.statusCode(),
                        response.getHeader("Content-Type"),
                        String.valueOf(response.bodyAsString())));
    }

    /**
     * Builds the mount handle for this test through {@link ValidationMountComponent}, carrying the
     * real magic-bytes verifier resolved in {@link #setUpClient} as a test contribution. An instance
     * method (not static) because it reads the per-test {@link #uploadsDirectory} field, which flows
     * in as the production {@code http.uploadsDirectory} config.
     *
     * @return the real mount handle, wired with the {@code web-validation} strategy and the
     *     opt-in magic-bytes verifier
     */
    private RestTestMount buildMount() {
        RestTestContributions.Builder contributions = RestTestContributions.builder();
        verifiers.forEach(contributions::addFileContentVerifier);
        JsonObject config =
                new JsonObject().put("http", new JsonObject().put("uploadsDirectory", uploadsDirectory.toString()));
        return MountFixtures.mount(vertx, config, contributions.build());
    }

    private static java.nio.file.Path uniqueUploadsDirectory(String testName) {
        return java.nio.file.Path.of(
                "target", "file-uploads", "MagicBytesVerifierRouteIT", testName + "-" + UUID.randomUUID());
    }

    /**
     * One observed HTTP response.
     *
     * <p>The body is captured as text rather than as a {@link Buffer} because a {@link WebClient}
     * reports an empty body as {@code null} where the raw client reported a zero-length buffer. It is
     * wrapped through {@code String.valueOf} so an unexpected empty body stays a legible failure
     * instead of an NPE. Neither response asserted on here is legitimately empty — the rejection
     * carries a problem detail and the acceptance carries {@code "accepted"}.
     *
     * @param statusCode  the response status code
     * @param contentType the raw {@code Content-Type} header, or {@code null} when absent
     * @param body        the response body as text, or {@code "null"} when the response carried none
     */
    private record HttpResult(int statusCode, String contentType, String body) {}

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
}
