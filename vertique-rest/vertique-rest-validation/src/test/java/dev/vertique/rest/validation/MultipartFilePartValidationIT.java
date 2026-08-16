// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.ValidationErrorDetail;
import dev.vertique.rest.core.request.FilePart;
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
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.validation.constraints.Size;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
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
 *
 * <p>Built through {@link MountFixtures} over {@link ValidationMountComponent} (the {@code
 * vertique-rest-test} fixture) with {@link RestTestContributions#none()} — nothing beyond the
 * real {@code web-validation} strategy and the victools-backed {@code AnnotationSchemaSource} that
 * including {@code RestValidationModule} already supplies. The mount is built once in
 * {@link #setUp}, for the whole class, rather than per test.
 *
 * <p>Requests are issued through a {@link WebClient} rather than a raw {@code HttpClient}
 * deliberately: a raw {@code HttpClientResponse} discards body buffers that arrive before a body
 * handler is attached, so under load {@code body()} can succeed with zero bytes while the status code
 * is correct (issue #167). Three of the four tests decode the response body as the problem detail and
 * the fourth compares it to the resource's echoed binding, so a silently emptied body would be
 * reported as a validation defect that did not happen. A {@link WebClient} aggregates the body into
 * its {@code HttpResponse} before completing the send, so the race is closed by construction rather
 * than by every author remembering an idiom.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class MultipartFilePartValidationIT {

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
    private static final AtomicBoolean INVOKED = new AtomicBoolean();

    private static HttpServer server;
    private static WebClient client;

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
        RestTestMounts.startServer(
                        vertx, MountFixtures.mount(vertx, RestTestContributions.none()), Set.of(new FileResource()))
                .onComplete(ctx.succeeding(listeningServer -> {
                    server = listeningServer;
                    // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
                    client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
                    ctx.completeNow();
                }));
    }

    @BeforeEach
    void resetInvocationProbe() {
        INVOKED.set(false);
    }

    /**
     * Closes the shared {@link WebClient} and then the shared server, before the extension-owned
     * {@link Vertx} instance is closed.
     *
     * <p>{@link WebClient#close()} is {@code void}, unlike {@code HttpClient.close()}: it returns once
     * the underlying client has been asked to close, so there is no future to join here and the server
     * close alone carries the completion.
     *
     * @param ctx the test context used for async teardown assertion
     */
    @AfterAll
    static void tearDown(VertxTestContext ctx) {
        if (client != null) {
            client.close();
        }
        Future<Void> serverClose = server != null ? server.close() : Future.succeededFuture();
        serverClose.onComplete(ctx.succeeding(v -> ctx.completeNow()));
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

                JsonObject problem = new JsonObject(result.body());
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
                JsonArray errors = new JsonObject(result.body()).getJsonArray("errors");
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

                JsonArray errors = new JsonObject(result.body()).getJsonArray("errors");
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

    /**
     * POSTs the pre-encoded multipart body to {@code /files}.
     *
     * <p>The {@link Buffer} {@link MultipartBodies} pre-encodes is sent verbatim through
     * {@code sendBuffer} rather than re-expressed as a {@code MultipartForm}: the exact bytes —
     * including the deliberately malformed {@code "image / png"} part header — are what the file-part
     * gate is exercised against.
     *
     * @param body the pre-encoded multipart body
     * @return a future of the response status, Content-Type, and body text
     */
    private static Future<HttpResult> postMultipart(Buffer body) {
        return client.post(server.actualPort(), "127.0.0.1", "/files")
                .putHeader("Content-Type", MultipartBodies.contentType())
                .sendBuffer(body)
                .map(response -> new HttpResult(
                        response.statusCode(),
                        response.getHeader("Content-Type"),
                        String.valueOf(response.bodyAsString())));
    }

    /**
     * One observed HTTP response.
     *
     * <p>The body is captured as text rather than as a {@link Buffer} because a {@link WebClient}
     * reports an empty body as {@code null} where the raw client reported a zero-length buffer. It is
     * wrapped through {@code String.valueOf} so an unexpected empty body stays a legible failure
     * instead of an NPE. No response asserted on here is legitimately empty — every one carries either
     * a problem detail or the resource's echoed binding.
     *
     * @param statusCode  the response status code
     * @param contentType the raw {@code Content-Type} header, or {@code null} when absent
     * @param body        the response body as text, or {@code "null"} when the response carried none
     */
    private record HttpResult(int statusCode, String contentType, String body) {}
}
