// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.vertique.rest.core.config.HttpConfig;
import dev.vertique.rest.core.interceptor.ErrorInterceptor;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMount;
import dev.vertique.rest.test.RestTestMounts;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Comparator;
import java.util.List;
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
 *
 * <p>Requests are issued through a {@link WebClient} rather than a raw {@code HttpClient}
 * deliberately: a raw {@code HttpClientResponse} discards body buffers that arrive before a body
 * handler is attached, so under load {@code body()} can succeed with zero bytes while the status code
 * is correct (issue #167). The two acceptance tests pair the status with the resource's echoed part
 * count, so a silently emptied body would report a binding defect that did not happen. A
 * {@link WebClient} aggregates the body into its {@code HttpResponse} before completing the send, so
 * the race is closed by construction rather than by every author remembering an idiom.
 */
@ExtendWith(VertxExtension.class)
// 30s rather than testing.md's 20s default, deliberately: each of the seven tests below starts and
// stops its own server, and rejectedRequestLeavesNoSpooledFiles may hold up to the 10s cap of its
// quiescence poll. Tightening this to 20s would buy nothing and would flake under CI load.
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class MultipartPartCountLimitIT {

    /**
     * Read from {@link HttpConfig}'s own defaults rather than restated, so these tests observe the
     * limit the framework actually configures — which is what this class's javadoc claims — instead
     * of a literal that would silently stop tracking it.
     */
    private static final HttpConfig FRAMEWORK_DEFAULTS = HttpConfig.builder().build();

    private static final int CONFIGURED_FORM_FIELD_LIMIT = FRAMEWORK_DEFAULTS.maxFormFields();

    private static final int CONFIGURED_FORM_ATTRIBUTE_SIZE_LIMIT = FRAMEWORK_DEFAULTS.maxFormAttributeSize();

    private static final long ASYNC_TIMEOUT_SECONDS = 10;

    private static Vertx vertx;
    private static WebClient client;

    private HttpServer server;
    private java.nio.file.Path uploadsDirectory;
    private PartCountCapture capture;
    private FailureCapture failureCapture;

    /**
     * Creates the class-scoped {@link WebClient}. It is bound to a static field so
     * {@link #tearDownClient} can close it; an unbound client can never be closed at all.
     *
     * @param injectedVertx the class-scoped Vert.x instance injected by vertx-junit5
     */
    @BeforeAll
    static void setUpClient(Vertx injectedVertx) {
        vertx = injectedVertx;
        // Redirects off: parity with the raw client; WebClient forwards Authorization across 3xx.
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
    @DisplayName("Text and file parts share one counter — one file part past the limit, mixed, is rejected")
    void mixedTextAndFilePartsUnderByteCapObservedLimit() throws Exception {
        startServer("mixedTextAndFileParts");

        HttpResult result = postMultipart(MultipartBodies.parts(CONFIGURED_FORM_FIELD_LIMIT + 1 - 200, 200));

        assertEquals(
                400,
                result.statusCode(),
                "a shared counter must reject one mixed part past the limit; observed failure: "
                        + failureCapture.describe());
        assertNull(capture.observation(), "the decoder must reject before any framework handler runs");
    }

    @Test
    @DisplayName("A single form field larger than maxFormAttributeSize is rejected as a bad request")
    void oversizedSingleFormFieldRejectedAsBadRequest() throws Exception {
        startServer("oversizedSingleFormField");

        HttpResult result =
                postMultipart(MultipartBodies.singleTextField("oversized", CONFIGURED_FORM_ATTRIBUTE_SIZE_LIMIT + 1));

        assertEquals(
                400,
                result.statusCode(),
                "a form field beyond maxFormAttributeSize is a client error, not a server fault; observed failure: "
                        + failureCapture.describe());
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
    @DisplayName("Exactly the configured number of mixed parts is accepted")
    void mixedTextAndFilePartsAtLimitAccepted() throws Exception {
        startServer("mixedTextAndFilePartsAtLimit");

        // The matched pair to mixedTextAndFilePartsUnderByteCapObservedLimit's one-past-the-limit
        // rejection. A rejection above the line alone is consistent with several per-kind counting
        // schemes; acceptance at exactly the limit is what pins the counter as shared.
        HttpResult result = postMultipart(MultipartBodies.parts(CONFIGURED_FORM_FIELD_LIMIT - 200, 200));

        int filePartsAtLimit = CONFIGURED_FORM_FIELD_LIMIT - 200;
        assertEquals(
                200,
                result.statusCode(),
                "mixed parts sitting exactly on the shared limit must be accepted; observed failure: "
                        + failureCapture.describe());
        assertEquals("files=" + filePartsAtLimit, result.body().toString());
        Observation observed = capture.observation();
        assertNotNull(observed, "an accepted body must reach the router pipeline");
        assertEquals(filePartsAtLimit, observed.fileUploads(), "every file part must be bound");
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

        JsonObject config = new JsonObject()
                .put(
                        "http",
                        new JsonObject().put("host", "127.0.0.1").put("uploadsDirectory", uploadsDirectory.toString()));
        RestTestContributions contributions =
                RestTestContributions.builder().addRequestInterceptor(capture).build();

        MultipartPartCountLimitComponent component =
                DaggerMultipartPartCountLimitComponent.factory().create(vertx, config, contributions, failureCapture);
        RestTestMount mount = component.testMount();
        HttpConfig httpConfig = component.httpConfig();

        Router apiRouter = RestTestMounts.router(vertx, mount, Set.of(new PartsResource()))
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        Router root = Router.router(vertx);
        root.route("/*").subRouter(apiRouter);
        server = vertx.createHttpServer(
                        httpConfig.toHttpServerOptions().setHost("127.0.0.1").setPort(0))
                .requestHandler(root)
                .listen()
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * POSTs the pre-encoded multipart body to {@code /parts} and awaits the full response.
     *
     * <p>The {@link Buffer} {@link MultipartBodies} pre-encodes is sent verbatim through
     * {@code sendBuffer} rather than re-expressed as a {@code MultipartForm}: the exact part count and
     * part sizes are what the decoder's limits are being probed with.
     *
     * @param body the pre-encoded multipart body
     * @return the response status and body text
     * @throws Exception when the round trip fails or times out
     */
    private HttpResult postMultipart(Buffer body) throws Exception {
        return client.post(server.actualPort(), "127.0.0.1", "/parts")
                .putHeader("Content-Type", MultipartBodies.contentType())
                .sendBuffer(body)
                .map(response -> new HttpResult(response.statusCode(), String.valueOf(response.bodyAsString())))
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Samples the uploads directory until the file count stops changing for a full settle window,
     * so the result reflects everything the request produced rather than a premature snapshot.
     *
     * <p>This is deliberately not a wall-clock wait for a timer, and it must not be replaced by a
     * bare {@code count == 0} check: the server keeps draining the request body after the response
     * has been written, so an immediate sample can read "not yet written" as "cleaned up" — a test
     * that cannot fail. Waiting for the count to stop moving is what makes the assertion real.
     *
     * <p>Bounded on both ends: it gives up after a 10s cap, and returns as soon as the count has
     * held steady for a 500ms settle window (measured settle is ~26ms), so the common path costs
     * roughly half a second rather than the cap.
     *
     * @return the settled number of files in this test's uploads directory
     */
    private long awaitQuiescentSpooledFileCount() throws Exception {
        long settleWindowNanos = TimeUnit.MILLISECONDS.toNanos(500);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long count = spooledFileCount();
        long unchangedSince = System.nanoTime();

        while (System.nanoTime() < deadline) {
            Thread.sleep(50);
            long current = spooledFileCount();
            if (current != count) {
                count = current;
                unchangedSince = System.nanoTime();
            } else if (System.nanoTime() - unchangedSince >= settleWindowNanos) {
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
     * {@code JaxRsRouterMount.handleFailure} chose to dispatch, and the status Vert.x itself set on
     * the context. Together these say which branch of {@code handleFailure} a decoder rejection
     * actually takes.
     */
    static final class FailureCapture implements ErrorInterceptor {

        private final AtomicReference<String> described = new AtomicReference<>();

        @Override
        public Future<Throwable> beforeMapping(RoutingContext rc, Throwable throwable) {
            described.set("cause=" + throwable.getClass().getName()
                    + ", message=" + throwable.getMessage()
                    + ", ctx.statusCode()=" + rc.statusCode());
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

    /**
     * One observed HTTP response.
     *
     * <p>The body is captured as text rather than as a {@link Buffer} because a {@link WebClient}
     * reports an empty body as {@code null} where the raw client reported a zero-length buffer. It is
     * wrapped through {@code String.valueOf} so an unexpected empty body stays a legible assertion
     * failure instead of an NPE. The two accepted responses asserted on here carry the resource's
     * echoed part count; the rejections' bodies are not read.
     *
     * @param statusCode the response status code
     * @param body       the response body as text, or {@code "null"} when the response carried none
     */
    private record HttpResult(int statusCode, String body) {}
}
