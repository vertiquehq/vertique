// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.request.FilePart;
import dev.vertique.rest.jaxrs.validation.FileContentVerifier;
import dev.vertique.rest.jaxrs.validation.FileVerificationResult;
import dev.vertique.rest.test.RestTestContributions;
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
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end proof of verifier rejection mapping and the empty-verifier baseline.
 *
 * <p>Requests are issued through a {@link WebClient} rather than a raw {@code HttpClient}
 * deliberately: a raw {@code HttpClientResponse} discards body buffers that arrive before a body
 * handler is attached, so under load {@code body()} can succeed with zero bytes while the status code
 * is correct (issue #167). Both tests here decode the response body as the problem-detail JSON, so a
 * silently emptied body would surface as a decode failure blamed on the verifier pipeline. A
 * {@link WebClient} aggregates the body into its {@code HttpResponse} before completing the send, so
 * the race is closed by construction rather than by every author remembering an idiom.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class FileVerifierRejectionIT {

    private static final Map<String, Object> REJECTION_ARGS = Map.of("policy", "strict", "rule", "test-signature");

    private static Vertx vertx;
    private static WebClient client;

    private HttpServer server;

    /**
     * Creates the class-scoped {@link WebClient}. It is bound to a static field so
     * {@link #tearDownClient} can close it; an unbound client can never be closed at all.
     *
     * @param injectedVertx the class-scoped Vert.x instance injected by vertx-junit5
     */
    @BeforeAll
    static void setUpClient(Vertx injectedVertx) {
        vertx = injectedVertx;
        client = WebClient.create(vertx);
    }

    @AfterEach
    void tearDownServer(VertxTestContext ctx) {
        Future<?> close = server != null ? server.close() : Future.succeededFuture();
        close.onComplete(ctx.succeeding(v -> ctx.completeNow()));
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

    @Test
    @DisplayName("A registered verifier rejection maps verbatim to 400 with the physical occurrence pointer")
    void registeredVerifierRejects400WithPointer(VertxTestContext ctx) {
        UploadResource resource = new UploadResource();
        RejectSecondVerifier verifier = new RejectSecondVerifier();

        startServer(Set.of(verifier), resource)
                .compose(listeningServer -> {
                    server = listeningServer;
                    return postMultipart(twoSameNamePngFiles());
                })
                .onComplete(ctx.succeeding(result -> {
                    ctx.verify(() -> {
                        assertEquals(400, result.statusCode());
                        assertTrue(result.contentType().contains("application/problem+json"));
                        JsonArray errors = new JsonObject(result.body()).getJsonArray("errors");
                        assertEquals(1, errors.size());
                        assertEquals(
                                new ValidationErrorDetail(
                                        "upload[1]",
                                        "application verifier rejected the second physical upload",
                                        "file",
                                        "applicationPolicyRejected",
                                        REJECTION_ARGS),
                                errors.getJsonObject(0).mapTo(ValidationErrorDetail.class));
                        assertEquals(2, verifier.invocations.get(), "both physical uploads must reach the verifier");
                        assertFalse(resource.invoked.get(), "a verifier rejection must prevent resource invocation");
                    });
                    ctx.completeNow();
                }));
    }

    @Test
    @DisplayName("With no verifier registered, only baseline file-part constraints apply")
    void noVerifierRegisteredOnlyBaselineApplies(VertxTestContext ctx) {
        UploadResource resource = new UploadResource();

        startServer(Set.of(), resource)
                .compose(listeningServer -> {
                    server = listeningServer;
                    return postMultipart(MultipartBodies.singleFile(
                            "upload", "payload.exe", "application/x-msdownload", new byte[] {'M', 'Z'}));
                })
                .onComplete(ctx.succeeding(result -> {
                    ctx.verify(() -> {
                        assertEquals(400, result.statusCode());
                        JsonArray errors = new JsonObject(result.body()).getJsonArray("errors");
                        assertEquals(1, errors.size(), "an empty verifier set must add no errors beyond baseline");
                        assertEquals(
                                new ValidationErrorDetail(
                                        "upload",
                                        "file part content type is not allowed",
                                        "file",
                                        "fileContentTypeNotAllowed",
                                        Map.of("allowedTypes", List.of("image/png"))),
                                errors.getJsonObject(0).mapTo(ValidationErrorDetail.class));
                        assertFalse(resource.invoked.get());
                    });
                    ctx.completeNow();
                }));
    }

    private Future<HttpServer> startServer(Set<FileContentVerifier> verifiers, UploadResource resource) {
        RestTestContributions.Builder contributions = RestTestContributions.builder();
        verifiers.forEach(contributions::addFileContentVerifier);
        return RestTestMounts.startServer(vertx, MountFixtures.mount(vertx, contributions.build()), Set.of(resource));
    }

    /**
     * POSTs the pre-encoded multipart body to {@code /files}.
     *
     * <p>The {@link Buffer} is sent verbatim through {@code sendBuffer} rather than re-expressed as a
     * {@code MultipartForm}: the exact bytes assembled here are what the upload path is exercised
     * against.
     *
     * @param body the pre-encoded multipart body
     * @return a future of the response status, Content-Type, and body text
     */
    private Future<HttpResult> postMultipart(Buffer body) {
        return client.post(server.actualPort(), "127.0.0.1", "/files")
                .putHeader("Content-Type", MultipartBodies.contentType())
                .sendBuffer(body)
                .map(response -> new HttpResult(
                        response.statusCode(),
                        response.getHeader("Content-Type"),
                        String.valueOf(response.bodyAsString())));
    }

    private static Buffer twoSameNamePngFiles() {
        Buffer body = Buffer.buffer();
        appendFilePart(body, "first.png", new byte[] {1});
        appendFilePart(body, "second.png", new byte[] {2});
        appendAscii(body, "--" + MultipartBodies.BOUNDARY + "--\r\n");
        return body;
    }

    private static void appendFilePart(Buffer body, String fileName, byte[] content) {
        appendAscii(body, "--" + MultipartBodies.BOUNDARY + "\r\n");
        appendAscii(body, "Content-Disposition: form-data; name=\"upload\"; filename=\"" + fileName + "\"\r\n");
        appendAscii(body, "Content-Type: image/png\r\n\r\n");
        body.appendBytes(content);
        appendAscii(body, "\r\n");
    }

    private static void appendAscii(Buffer body, String value) {
        body.appendBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * One observed HTTP response.
     *
     * <p>The body is captured as text rather than as a {@link Buffer} because a {@link WebClient}
     * reports an empty body as {@code null} where the raw client reported a zero-length buffer. It is
     * wrapped through {@code String.valueOf} so an unexpected empty body stays a legible failure
     * instead of an NPE. No response asserted on here is legitimately empty — every one carries either
     * a problem detail or the resource's own text.
     *
     * @param statusCode  the response status code
     * @param contentType the raw {@code Content-Type} header, or {@code null} when absent
     * @param body        the response body as text, or {@code "null"} when the response carried none
     */
    private record HttpResult(int statusCode, String contentType, String body) {}

    /** Resource with baseline type constraints that accepts every PNG-declared physical upload. */
    @Path("/files")
    public static class UploadResource {

        private final AtomicBoolean invoked = new AtomicBoolean();

        @POST
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "verifyUploadedFiles")
        public String upload(@FormParam("upload") @FilePart(allowedTypes = {"image/png"}) List<FileUpload> uploads) {
            invoked.set(true);
            return Integer.toString(uploads.size());
        }
    }

    /** Accepts the first physical upload and rejects the second with application-owned fields. */
    private static final class RejectSecondVerifier implements FileContentVerifier {

        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public Future<FileVerificationResult> verify(FileUpload part) {
            if (invocations.incrementAndGet() == 2) {
                return Future.succeededFuture(FileVerificationResult.rejected(
                        "application verifier rejected the second physical upload",
                        "applicationPolicyRejected",
                        REJECTION_ARGS));
            }
            return Future.succeededFuture(FileVerificationResult.accepted());
        }
    }
}
