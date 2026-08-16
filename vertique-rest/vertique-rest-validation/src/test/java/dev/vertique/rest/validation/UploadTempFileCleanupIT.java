// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.SerializedBody;
import dev.vertique.rest.core.response.StreamingBody;
import dev.vertique.rest.test.RestTestContributions;
import dev.vertique.rest.test.RestTestMounts;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.FileUpload;
import io.vertx.ext.web.RoutingContext;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
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
 * facts that are only observable while the response is still on the wire.
 *
 * <p>Every test uses its own uploads directory. {@link UploadPathCapture} runs immediately after
 * the mount's {@code BodyHandler}, so it records the exact path created by Vert.x before the
 * resource is invoked.
 *
 * <p><strong>Raw {@link HttpClient} exemption — mid-stream observation and wire-level control.</strong>
 * The lifecycle the two tests below pin is only observable while a response is still streaming (the
 * upload must still exist between the first body byte and the last), and one of them must abort the
 * connection with {@code SO_LINGER(0)} through a raw {@link Socket}; a buffered {@code WebClient}
 * exchange can express neither. The exemption is contained to that need: nothing else in this file
 * issues a request. The three cleanup outcomes that are only inspected after the exchange has
 * settled were moved to {@code UploadTempFileCleanupWebClientIT}, which uses the default
 * {@code WebClient}. The one {@code HttpClient} exchange left here uses the raw-client idiom pinned
 * by {@code HttpClientBodyReadRaceIT}: the whole response continuation — including the body read —
 * is attached before {@code end()} initiates the send.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 30, unit = TimeUnit.SECONDS)
public class UploadTempFileCleanupIT {

    private static final byte[] PAYLOAD = "streamed-upload-payload".getBytes(StandardCharsets.UTF_8);
    private static final long ASYNC_TIMEOUT_SECONDS = 5;

    private static Vertx vertx;
    private static HttpClient client;

    private HttpServer server;
    private java.nio.file.Path uploadsDirectory;
    private StreamingGate streamingGate;

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
        if (streamingGate != null) {
            streamingGate.release();
        }
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        }
        RestTestMounts.deleteRecursively(uploadsDirectory);
    }

    @Test
    @DisplayName("A hard client reset after the first response byte deletes the temporary file")
    void tempFileDeletedAfterClientResetMidResponse() throws Exception {
        uploadsDirectory = uniqueUploadsDirectory("tempFileDeletedAfterClientResetMidResponse");
        streamingGate = new StreamingGate();
        UploadPathCapture capture = startServer(uploadsDirectory);

        Buffer multipart = multipart("application/octet-stream", PAYLOAD);
        try (Socket socket = new Socket("127.0.0.1", server.actualPort())) {
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(ASYNC_TIMEOUT_SECONDS));
            writeRawMultipartRequest(socket, "/cleanup/stream", multipart);

            int firstBodyByte = readFirstResponseBodyByte(socket);
            java.nio.file.Path uploadedPath = capture.awaitPath();

            assertEquals(PAYLOAD[0] & 0xff, firstBodyByte, "the client must receive a streamed body byte before reset");
            assertTrue(Files.exists(uploadedPath), "the upload must remain available while the response is streaming");

            // Force a TCP RST rather than a graceful FIN. The gated response remains unfinished,
            // so cleanup can only be attributed to abnormal request termination.
            socket.setSoLinger(true, 0);
            socket.close();

            awaitDeleted(uploadedPath);
        }
    }

    @Test
    @DisplayName("The temporary file remains alive until a streaming response finishes")
    void fileAliveDuringResponseStreaming() throws Exception {
        uploadsDirectory = uniqueUploadsDirectory("fileAliveDuringResponseStreaming");
        streamingGate = new StreamingGate();
        UploadPathCapture capture = startServer(uploadsDirectory);

        StreamingResponse response = postStreamingMultipart("/cleanup/stream", PAYLOAD);
        int firstBodyByte = response.firstBodyByte().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        java.nio.file.Path uploadedPath = capture.awaitPath();

        assertEquals(PAYLOAD[0] & 0xff, firstBodyByte);
        assertTrue(Files.exists(uploadedPath), "cleanup must not race a response that is still streaming the upload");

        streamingGate.release();
        assertEquals(Buffer.buffer(PAYLOAD), response.body().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        awaitDeleted(uploadedPath);
    }

    private UploadPathCapture startServer(java.nio.file.Path directory) {
        UploadPathCapture capture = new UploadPathCapture();
        RestTestContributions contributions = RestTestContributions.builder()
                .addRequestInterceptor(capture)
                .addResponseBodyEncoder(new StreamedUploadEncoder())
                .build();
        JsonObject config =
                new JsonObject().put("http", new JsonObject().put("uploadsDirectory", directory.toString()));

        server = RestTestMounts.startServerBlocking(
                vertx,
                MountFixtures.mount(vertx, config, contributions),
                Set.of(new CleanupResource(capture, streamingGate)),
                Duration.ofSeconds(ASYNC_TIMEOUT_SECONDS));
        return capture;
    }

    private StreamingResponse postStreamingMultipart(String path, byte[] content) throws Exception {
        Buffer body = multipart("application/octet-stream", content);
        HttpClientResponse response = client.request(HttpMethod.POST, server.actualPort(), "127.0.0.1", path)
                .compose(request -> {
                    // The pause is reached through a continuation attached to response() BEFORE end()
                    // initiates the send. A continuation attached after the send sits in the same
                    // window the send does — chunks dispatched before it runs are already discarded and
                    // no pause() can recall them. Attached first, the pause runs on the tick that
                    // delivers the head and then holds the stream across the blocking get() below,
                    // until the handlers are registered. See HttpClientBodyReadRaceIT.
                    Future<HttpClientResponse> paused = request.response().map(pausedResponse -> {
                        pausedResponse.pause();
                        return pausedResponse;
                    });
                    request.putHeader("Content-Type", MultipartBodies.contentType());
                    request.end(body);
                    return paused;
                })
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        CompletableFuture<Integer> firstBodyByte = new CompletableFuture<>();
        CompletableFuture<Buffer> responseBody = new CompletableFuture<>();
        Buffer collected = Buffer.buffer();
        response.exceptionHandler(error -> {
            firstBodyByte.completeExceptionally(error);
            responseBody.completeExceptionally(error);
        });
        response.handler(chunk -> {
            collected.appendBuffer(chunk);
            if (chunk.length() > 0) {
                firstBodyByte.complete(chunk.getByte(0) & 0xff);
            }
        });
        response.endHandler(v -> responseBody.complete(collected));
        response.resume();
        return new StreamingResponse(firstBodyByte, responseBody);
    }

    private static Buffer multipart(String declaredType, byte[] content) {
        return MultipartBodies.singleFile("upload", "payload.bin", declaredType, content);
    }

    private static void writeRawMultipartRequest(Socket socket, String path, Buffer body) throws IOException {
        String headers = "POST " + path + " HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Type: " + MultipartBodies.contentType() + "\r\n"
                + "Content-Length: " + body.length() + "\r\n"
                + "Connection: keep-alive\r\n\r\n";
        socket.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().write(body.getBytes());
        socket.getOutputStream().flush();
    }

    private static int readFirstResponseBodyByte(Socket socket) throws IOException {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        int matched = 0;
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        while (matched < terminator.length) {
            int value = socket.getInputStream().read();
            if (value < 0) {
                throw new IOException("response ended before the first body byte");
            }
            headers.write(value);
            matched = value == terminator[matched] ? matched + 1 : (value == terminator[0] ? 1 : 0);
            if (headers.size() > 16 * 1024) {
                throw new IOException("response headers exceeded the test bound");
            }
        }
        String headerText = headers.toString(StandardCharsets.US_ASCII);
        assertTrue(headerText.startsWith("HTTP/1.1 200"), "streaming resource must start a 200 response");
        int firstBodyByte = socket.getInputStream().read();
        if (firstBodyByte < 0) {
            throw new IOException("response ended before the first body byte");
        }
        return firstBodyByte;
    }

    private static java.nio.file.Path uniqueUploadsDirectory(String testName) {
        return java.nio.file.Path.of(
                "target", "file-uploads", "UploadTempFileCleanupIT", testName + "-" + UUID.randomUUID());
    }

    private static void awaitDeleted(java.nio.file.Path uploadedPath) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (Files.exists(uploadedPath) && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertFalse(Files.exists(uploadedPath), "temporary upload was not deleted: " + uploadedPath);
    }

    /** JAX-RS fixture exposing the gated streaming outcome. */
    @Path("/cleanup")
    public static class CleanupResource {

        private final UploadPathCapture capture;
        private final StreamingGate streamingGate;

        private CleanupResource(UploadPathCapture capture, StreamingGate streamingGate) {
            this.capture = capture;
            this.streamingGate = streamingGate;
        }

        @POST
        @Path("/stream")
        @Consumes(MediaType.MULTIPART_FORM_DATA)
        @Produces(MediaType.APPLICATION_OCTET_STREAM)
        @Operation(operationId = "cleanupStream")
        public StreamedUpload stream(@FormParam("upload") FileUpload upload) throws IOException {
            capture.capture(upload);
            return new StreamedUpload(
                    Buffer.buffer(Files.readAllBytes(java.nio.file.Path.of(upload.uploadedFileName()))), streamingGate);
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

    private record StreamingResponse(CompletableFuture<Integer> firstBodyByte, CompletableFuture<Buffer> body) {}

    private record StreamedUpload(Buffer payload, StreamingGate gate) {}

    /** Gate keeping the response open after its first byte until the test explicitly releases it. */
    private static final class StreamingGate {

        private final CompletableFuture<Void> released = new CompletableFuture<>();

        void release() {
            released.complete(null);
        }

        boolean isReleased() {
            return released.isDone();
        }

        void onRelease(Runnable action) {
            released.whenComplete((ignored, error) -> action.run());
        }
    }

    /** Deterministic two-chunk stream: one byte, a gate, then the remainder. */
    private static final class GatedReadStream implements ReadStream<Buffer> {

        private final Context context;
        private final Buffer payload;
        private final StreamingGate gate;

        private Handler<Throwable> exceptionHandler;
        private Handler<Buffer> dataHandler;
        private Handler<Void> endHandler;
        private long demand;
        private int offset;
        private boolean scheduled;
        private boolean waitingForRelease;
        private boolean ended;

        private GatedReadStream(Context context, Buffer payload, StreamingGate gate) {
            this.context = context;
            this.payload = payload;
            this.gate = gate;
        }

        @Override
        public synchronized GatedReadStream exceptionHandler(Handler<Throwable> handler) {
            exceptionHandler = handler;
            return this;
        }

        @Override
        public synchronized GatedReadStream handler(Handler<Buffer> handler) {
            dataHandler = handler;
            scheduleDrain();
            return this;
        }

        @Override
        public synchronized GatedReadStream pause() {
            demand = 0;
            return this;
        }

        @Override
        public synchronized GatedReadStream resume() {
            demand = Long.MAX_VALUE;
            scheduleDrain();
            return this;
        }

        @Override
        public synchronized GatedReadStream fetch(long amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("amount must be non-negative");
            }
            if (demand != Long.MAX_VALUE) {
                demand = Math.min(Long.MAX_VALUE, demand + amount);
            }
            scheduleDrain();
            return this;
        }

        @Override
        public synchronized GatedReadStream endHandler(Handler<Void> handler) {
            endHandler = handler;
            scheduleDrain();
            return this;
        }

        private synchronized void scheduleDrain() {
            if (scheduled || ended) {
                return;
            }
            scheduled = true;
            context.runOnContext(ignored -> drain());
        }

        private void drain() {
            Handler<Buffer> emit = null;
            Handler<Void> finish = null;
            Buffer chunk = null;
            synchronized (this) {
                scheduled = false;
                if (ended || dataHandler == null || demand == 0) {
                    return;
                }
                if (offset == 1 && !gate.isReleased()) {
                    if (!waitingForRelease) {
                        waitingForRelease = true;
                        gate.onRelease(() -> {
                            synchronized (GatedReadStream.this) {
                                waitingForRelease = false;
                                scheduleDrain();
                            }
                        });
                    }
                    return;
                }
                if (offset < payload.length()) {
                    int nextOffset = offset == 0 ? 1 : payload.length();
                    chunk = payload.slice(offset, nextOffset);
                    offset = nextOffset;
                    if (demand != Long.MAX_VALUE) {
                        demand--;
                    }
                    emit = dataHandler;
                } else if (endHandler != null) {
                    ended = true;
                    finish = endHandler;
                }
            }
            try {
                if (emit != null) {
                    emit.handle(chunk);
                    scheduleDrain();
                } else if (finish != null) {
                    finish.handle(null);
                }
            } catch (Throwable error) {
                Handler<Throwable> failureHandler;
                synchronized (this) {
                    failureHandler = exceptionHandler;
                }
                if (failureHandler != null) {
                    failureHandler.handle(error);
                }
            }
        }
    }

    /** Encoder producing the gated two-chunk response used by the lifecycle tests. */
    private static final class StreamedUploadEncoder implements ResponseBodyEncoder {
        @Override
        public boolean canEncode(Class<?> entityType, String contentType) {
            return entityType == StreamedUpload.class;
        }

        @Override
        public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
            StreamedUpload upload = (StreamedUpload) entity;
            return new StreamingBody(
                    new GatedReadStream(Vertx.currentContext(), upload.payload(), upload.gate()),
                    MediaType.APPLICATION_OCTET_STREAM,
                    (long) upload.payload().length());
        }

        @Override
        public int priority() {
            return 900;
        }
    }
}
