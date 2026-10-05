// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.extension.ExtensionPhase;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
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
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Issue #230 acceptance proof: over a real multipart HTTP exchange, a {@link FileContentVerifier}
 * whose future never resolves is bounded by {@code jaxrs.fileContentVerifierDeadlineMs} and fails
 * closed, while the cooperative cancellation contract stays with the implementor.
 *
 * <p><strong>What is and is not claimed.</strong> The framework wait race ({@code Future.timeout})
 * reclaims the HTTP request chain only; it never cancels the verifier's future or its underlying
 * work (the SPI stays a single {@code verify(FileUpload)} method with no cancellation token). The
 * verifier-owned {@link ScannerClient} here models the documented implementor obligation: release
 * the scanner session when its own future completes <em>or</em> when it observes request end, with
 * an idempotent teardown. {@link ScanSession#closeCalls} counts every release attempt and {@link
 * ScanSession#teardowns} counts actual resource teardowns, so a duplicate teardown is observable
 * rather than assumed away.
 *
 * <p>The request-end observation is wired through a {@link RequestInterceptor} that registers a
 * {@code RoutingContext} end handler, which is how an implementor outside the SPI can learn that
 * the request ended. Each test uses its own uploads directory, and the {@link Vertx} instance has
 * a single event loop so isolation assertions cannot be satisfied by parallelism.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class FileContentVerifierDeadlineIT {

    private static final long ASYNC_TIMEOUT_SECONDS = 10;
    private static final byte[] PAYLOAD = {1, 2, 3};

    private static Vertx vertx;
    private static WebClient client;

    private HttpServer server;
    private java.nio.file.Path uploadsDirectory;

    @BeforeAll
    static void setUp() {
        vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        client = WebClient.create(vertx, new WebClientOptions().setFollowRedirects(false));
    }

    @AfterAll
    static void tearDownAll() throws Exception {
        if (client != null) {
            client.close();
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        if (uploadsDirectory != null) {
            RestTestMounts.deleteRecursively(uploadsDirectory);
        }
    }

    @Test
    @DisplayName("A hanging verifier fails closed with 500, deletes the upload, releases at request end, and a late"
            + " completion causes no dispatch, no next verifier, and no duplicate teardown")
    void hangingVerifierFailsClosedReleasesOnceAndLateCompletionIsInert() throws Exception {
        long deadlineMs = 300;
        long waitBoundMs = 5_000;
        ScannerClient scanner = new ScannerClient();
        TrackedVerifier hanging = new TrackedVerifier(
                "hanging", ExtensionPhase.SYSTEM_FIRST, scanner, session -> session.verdict.future());
        List<String> laterCalls = new CopyOnWriteArrayList<>();
        TrackedVerifier later = new TrackedVerifier(
                "later",
                ExtensionPhase.APPLICATION,
                new ScannerClient(),
                session -> Future.succeededFuture(FileVerificationResult.accepted()));
        later.calls = laterCalls;
        UploadResource resource = new UploadResource();
        startServer("hanging", deadlineMs, resource, List.of(scanner), hanging, later);

        // 1. 500 within the wait bound (and not before the configured deadline elapsed).
        long startNanos = System.nanoTime();
        HttpResult result = postMultipart("hang.bin").get(waitBoundMs, TimeUnit.MILLISECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        assertEquals(500, result.statusCode(), "a hung verifier must fail closed as infrastructure 500");
        assertTrue(elapsedMs >= deadlineMs - 50, "the 500 must be the deadline firing, not an early failure");
        assertTrue(elapsedMs < waitBoundMs, "the 500 must land inside the wait bound");

        ScanSession session = scanner.session("hang.bin");
        assertTrue(session.uploadExistedWhileOpen, "the upload must exist while the verifier is in flight");
        assertFalse(session.verdict.future().isComplete(), "the framework must not complete the verifier future");

        // 2. Upload deletion at request end.
        awaitDeleted(session.uploadPath);

        // 3. Release through the implementor-owned request-end contract: exactly one teardown.
        session.awaitTornDown();
        assertEquals(1, session.teardowns.get(), "request end must release the verifier-owned session once");
        assertEquals(1, session.closeCalls.get(), "only the request-end release has run so far");

        // 4. Complete the original future afterwards: inert.
        session.verdict.complete(FileVerificationResult.accepted());
        ping().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS); // event-loop round trip after the late completion
        assertEquals(2, session.closeCalls.get(), "the completion hook attempted a second release");
        assertEquals(1, session.teardowns.get(), "a late completion must not tear the session down twice");
        assertEquals(1, hanging.invocations.get(), "the hanging verifier is invoked exactly once");
        assertEquals(List.of(), laterCalls, "no later verifier may run after a wait-deadline timeout");
        assertEquals(0, resource.uploadInvocations.get(), "the resource must never be dispatched");
    }

    @Test
    @DisplayName("A hung verifier on one request does not stall an accepted request on the same event loop")
    void hangingRequestDoesNotStallAcceptedRequestOnSameEventLoop() throws Exception {
        long deadlineMs = 2_000;
        ScannerClient scanner = new ScannerClient();
        TrackedVerifier verifier = new TrackedVerifier("mixed", ExtensionPhase.APPLICATION, scanner, session -> {
            if ("hang.bin".equals(session.key)) {
                return session.verdict.future();
            }
            return Future.succeededFuture(FileVerificationResult.accepted());
        });
        UploadResource resource = new UploadResource();
        startServer("concurrent", deadlineMs, resource, List.of(scanner), verifier);

        CompletableFuture<HttpResult> hangRequest = postMultipart("hang.bin");
        awaitSession(scanner, "hang.bin");

        HttpResult accepted = postMultipart("ok.bin").get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(200, accepted.statusCode());
        assertEquals("uploaded:ok.bin", accepted.body());
        assertFalse(hangRequest.isDone(), "the hung request must still be waiting when the accepted one completes");
        assertEquals(List.of("ok.bin"), resource.dispatched, "only the accepted upload may reach the resource");

        HttpResult hung = hangRequest.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertEquals(500, hung.statusCode());
        assertEquals(List.of("ok.bin"), resource.dispatched, "the timed-out upload must never reach the resource");

        ScanSession hangSession = scanner.session("hang.bin");
        ScanSession okSession = scanner.session("ok.bin");
        assertSame(
                hangSession.openThread,
                okSession.openThread,
                "both verifications must run on the one event-loop thread");
        hangSession.awaitTornDown();
        okSession.awaitTornDown();
        awaitDeleted(hangSession.uploadPath);
        awaitDeleted(okSession.uploadPath);
        assertEquals(1, hangSession.teardowns.get());
        assertEquals(1, okSession.teardowns.get(), "completion and request end must tear the session down once");
    }

    @Test
    @DisplayName("Each verifier gets a fresh deadline: two verifiers below the bound but above it together pass")
    void sequentialVerifiersEachGetFreshDeadlineBudget() throws Exception {
        long deadlineMs = 1_000;
        long perVerifierDelayMs = 600;
        List<String> calls = new CopyOnWriteArrayList<>();
        Function<ScanSession, Future<FileVerificationResult>> delayed = session -> {
            Promise<FileVerificationResult> promise = Promise.promise();
            vertx.setTimer(perVerifierDelayMs, id -> promise.complete(FileVerificationResult.accepted()));
            return promise.future();
        };
        TrackedVerifier first = new TrackedVerifier("first", ExtensionPhase.SYSTEM_FIRST, new ScannerClient(), delayed);
        TrackedVerifier second =
                new TrackedVerifier("second", ExtensionPhase.APPLICATION, new ScannerClient(), delayed);
        first.calls = calls;
        second.calls = calls;
        UploadResource resource = new UploadResource();
        startServer("sequential", deadlineMs, resource, List.of(), first, second);

        long startNanos = System.nanoTime();
        HttpResult result = postMultipart("slow.bin").get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertTrue(
                elapsedMs > deadlineMs,
                "precondition: the verifiers together must exceed the per-call deadline, took " + elapsedMs + "ms");
        assertEquals(200, result.statusCode(), "a per-call budget must not become an overall chain budget");
        assertEquals(List.of("first:slow.bin", "second:slow.bin"), calls);
        assertEquals(List.of("slow.bin"), resource.dispatched);
    }

    private void startServer(
            String testName,
            long deadlineMs,
            UploadResource resource,
            List<ScannerClient> clients,
            FileContentVerifier... verifiers) {
        uploadsDirectory = java.nio.file.Path.of(
                "target", "file-uploads", "FileContentVerifierDeadlineIT", testName + "-" + UUID.randomUUID());
        RestTestContributions.Builder builder =
                RestTestContributions.builder().addRequestInterceptor(new RequestEndRelease(clients));
        for (FileContentVerifier verifier : verifiers) {
            builder.addFileContentVerifier(verifier);
        }
        JsonObject config = new JsonObject()
                .put("http", new JsonObject().put("uploadsDirectory", uploadsDirectory.toString()))
                .put("jaxrs", new JsonObject().put("fileContentVerifierDeadlineMs", deadlineMs));
        server = RestTestMounts.startServerBlocking(
                vertx,
                MountFixtures.mount(vertx, config, builder.build()),
                Set.of(resource),
                Duration.ofSeconds(ASYNC_TIMEOUT_SECONDS));
    }

    private CompletableFuture<HttpResult> postMultipart(String fileName) {
        Buffer body = MultipartBodies.singleFile("upload", fileName, MediaType.APPLICATION_OCTET_STREAM, PAYLOAD);
        return client.post(server.actualPort(), "127.0.0.1", "/files")
                .putHeader("Content-Type", MultipartBodies.contentType())
                .sendBuffer(body)
                .map(response -> new HttpResult(response.statusCode(), String.valueOf(response.bodyAsString())))
                .toCompletionStage()
                .toCompletableFuture();
    }

    private CompletableFuture<HttpResult> ping() {
        return client.get(server.actualPort(), "127.0.0.1", "/ping")
                .send()
                .map(response -> new HttpResult(response.statusCode(), String.valueOf(response.bodyAsString())))
                .toCompletionStage()
                .toCompletableFuture();
    }

    private static void awaitSession(ScannerClient scanner, String key) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(ASYNC_TIMEOUT_SECONDS);
        while (scanner.sessions.get(key) == null && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(scanner.sessions.containsKey(key), "verifier was never invoked for " + key);
    }

    private static void awaitDeleted(java.nio.file.Path uploadedPath) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Files.exists(uploadedPath) && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertFalse(Files.exists(uploadedPath), "temporary upload was not deleted: " + uploadedPath);
    }

    private record HttpResult(int statusCode, String body) {}

    /** Resource that records every upload dispatched past the validation gate. */
    @Path("/")
    public static class UploadResource {

        private final AtomicInteger uploadInvocations = new AtomicInteger();
        private final List<String> dispatched = new CopyOnWriteArrayList<>();

        @POST
        @Path("files")
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "uploadWithDeadline")
        public String upload(@FormParam("upload") FileUpload upload) {
            uploadInvocations.incrementAndGet();
            dispatched.add(upload.fileName());
            return "uploaded:" + upload.fileName();
        }

        @GET
        @Path("ping")
        @Produces(MediaType.TEXT_PLAIN)
        @Operation(operationId = "pingAfterLateCompletion")
        public String ping() {
            return "pong";
        }
    }

    /**
     * Verifier-owned scanner client: the stand-in for a remote or scanner-backed implementation's
     * transport. It keys one {@link ScanSession} per submitted file name.
     */
    private static final class ScannerClient {

        private final ConcurrentMap<String, ScanSession> sessions = new ConcurrentHashMap<>();

        ScanSession open(FileUpload part) {
            ScanSession session = new ScanSession(part.fileName(), java.nio.file.Path.of(part.uploadedFileName()));
            sessions.put(session.key, session);
            return session;
        }

        ScanSession session(String key) {
            ScanSession session = sessions.get(key);
            assertTrue(session != null, "no scanner session was opened for " + key);
            return session;
        }

        /** The implementor-owned request-end observation: release whatever this request opened. */
        void releaseAtRequestEnd(String key) {
            ScanSession session = sessions.get(key);
            if (session != null) {
                session.close();
            }
        }
    }

    /** One verifier-owned scanner session with an idempotent, counted teardown. */
    private static final class ScanSession {

        private final String key;
        private final java.nio.file.Path uploadPath;
        private final boolean uploadExistedWhileOpen;
        private final Thread openThread = Thread.currentThread();
        private final Promise<FileVerificationResult> verdict = Promise.promise();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger teardowns = new AtomicInteger();
        private final AtomicBoolean closed = new AtomicBoolean();
        private final CompletableFuture<Void> tornDown = new CompletableFuture<>();

        private ScanSession(String key, java.nio.file.Path uploadPath) {
            this.key = key;
            this.uploadPath = uploadPath;
            this.uploadExistedWhileOpen = Files.exists(uploadPath);
        }

        void close() {
            closeCalls.incrementAndGet();
            if (closed.compareAndSet(false, true)) {
                teardowns.incrementAndGet();
                tornDown.complete(null);
            }
        }

        void awaitTornDown() throws Exception {
            tornDown.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
    }

    /**
     * Verifier that opens a {@link ScanSession}, runs the supplied scan, and releases the session
     * when its own future completes — the "complete" half of the documented release contract.
     */
    private static final class TrackedVerifier implements FileContentVerifier {

        private final String id;
        private final ExtensionPhase phase;
        private final ScannerClient scanner;
        private final Function<ScanSession, Future<FileVerificationResult>> scan;
        private final AtomicInteger invocations = new AtomicInteger();
        private volatile List<String> calls = new CopyOnWriteArrayList<>();

        private TrackedVerifier(
                String id,
                ExtensionPhase phase,
                ScannerClient scanner,
                Function<ScanSession, Future<FileVerificationResult>> scan) {
            this.id = id;
            this.phase = phase;
            this.scanner = scanner;
            this.scan = scan;
        }

        @Override
        public Future<FileVerificationResult> verify(FileUpload part) {
            invocations.incrementAndGet();
            calls.add(id + ":" + part.fileName());
            ScanSession session = scanner.open(part);
            return scan.apply(session).andThen((result, failure) -> session.close());
        }

        @Override
        public ExtensionPhase phase() {
            return phase;
        }

        @Override
        public String orderKey() {
            return id;
        }
    }

    /**
     * Registers a request-end handler for every upload so verifier-owned clients can observe the
     * request ending, and runs after the multipart body is spooled and before the validation gate.
     */
    private static final class RequestEndRelease implements RequestInterceptor {

        private final List<ScannerClient> clients;

        private RequestEndRelease(List<ScannerClient> clients) {
            this.clients = clients;
        }

        @Override
        public void onRequest(RoutingContext rc) {
            for (FileUpload upload : rc.fileUploads()) {
                String key = upload.fileName();
                rc.addEndHandler(ignored -> clients.forEach(scanner -> scanner.releaseAtRequestEnd(key)));
            }
        }
    }
}
