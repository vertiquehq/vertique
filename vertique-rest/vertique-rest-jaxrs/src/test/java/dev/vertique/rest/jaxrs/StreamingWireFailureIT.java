// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.events.RestRequestCompletionEmitter;
import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
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
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * End-to-end acceptance proof that a post-handoff wire failure on a <em>streamed</em> response is
 * observable through {@link RestRequestCompletedEvent#wireFailureCode()}.
 *
 * <p>Both tests drive the real runtime stack over a live socket — no mocks, no hand-written
 * pipeline: {@link RequestContextLifecycle} and {@link RestRequestCompletionEmitter} are mounted as
 * ROOT middlewares on the root router, and the API router is the one built by
 * {@link JaxRsRouterMount#createRouter(Vertx)}, so the request flows through the production
 * {@code ResourceMethodInvoker} → {@code ResponsePipeline} → {@link DefaultResponseSerializer} →
 * {@link ReadStreamBodyEncoder} path. A {@code RestRequestCompletedListener} registered on the
 * emitter captures the single completion event per request.
 *
 * <p>The two tests cover the two <em>independent</em> failure inputs the emitter consults:
 * <ul>
 *   <li>{@link #midStreamSourceFailureSurfacesWireFailureCode()} — the streamed source fails after
 *       the first chunk reached the client. The pipe future fails, the response pipeline records
 *       the cause under {@link RestRequestCompletionEmitter#KEY_WIRE_FAILURE} and ends the
 *       response. The event carries status 200 <em>and</em> a non-null {@code wireFailureCode} —
 *       the truncated-response signature — while the client observes a well-formed but truncated
 *       body.</li>
 *   <li>{@link #clientAbortDuringStreamingSurfacesWireFailureCode()} — the client resets the
 *       connection ({@code SO_LINGER(0)}) while the stream is still open. Nothing is in flight, so
 *       the pipe future never settles; the failure reaches the event through the failed response
 *       end-handler {@code AsyncResult} instead.</li>
 * </ul>
 *
 * <p>Determinism: the server binds port 0 on the loopback address and the actual port is read after
 * the bind; a single {@link HttpClient} is shared by the class; every socket and server is closed on
 * every exit path; and all synchronization is latch-based ({@link CompletableFuture} completed by
 * the response handlers, the resource, and the completion listener) — there are no fixed sleeps.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class StreamingWireFailureIT {

    // --- Constants ---

    /** Loopback address used for both the bind and every client connection. */
    private static final String LOOPBACK = "127.0.0.1";

    /** Bound wait for every asynchronous step; the class-level {@code @Timeout} is the backstop. */
    private static final long ASYNC_TIMEOUT_SECONDS = 5;

    /** The single chunk the gated stream emits before a test fails it or resets the connection. */
    private static final Buffer FIRST_CHUNK = Buffer.buffer("chunk-1");

    /** Message of the source failure injected mid-stream; must not leak into the event. */
    private static final String SOURCE_FAILURE_MESSAGE = "gated source failed mid-stream";

    /** Request path served by {@link StreamingResource}. */
    private static final String STREAM_PATH = "/wire/stream";

    // --- Class-scoped resources ---

    private static Vertx vertx;
    private static HttpClient client;

    // --- Per-test resources ---

    private HttpServer server;

    /**
     * Creates the single {@link HttpClient} shared by every test in the class.
     *
     * @param injectedVertx the class-scoped Vert.x instance injected by vertx-junit5
     */
    @BeforeAll
    static void setUpClient(Vertx injectedVertx) {
        vertx = injectedVertx;
        client = vertx.createHttpClient();
    }

    /**
     * Closes the shared {@link HttpClient} and awaits the close before the class completes.
     *
     * @param ctx the test context used to signal teardown completion
     */
    @AfterAll
    static void tearDownClient(VertxTestContext ctx) {
        Future<?> close = client != null ? client.close() : Future.succeededFuture();
        close.onComplete(ar -> ctx.completeNow());
    }

    /**
     * Closes the per-test {@link HttpServer}; the shared client is closed only in
     * {@link #tearDownClient(VertxTestContext)}.
     *
     * @throws Exception if the server close does not complete within the async bound
     */
    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            server = null;
        }
    }

    // --- Tests ---

    @Test
    @DisplayName("A mid-stream source failure surfaces as a non-null wireFailureCode on a 200 event")
    void midStreamSourceFailureSurfacesWireFailureCode() throws Exception {
        CompletableFuture<GatedReadStream> streamCreated = new CompletableFuture<>();
        CompletableFuture<RestRequestCompletedEvent> completed = new CompletableFuture<>();
        int port = startServer(streamCreated, completed);

        HttpClientResponse response = client.request(HttpMethod.GET, port, LOOPBACK, STREAM_PATH)
                .compose(request -> request.send())
                .map(pausedResponse -> {
                    // Pause before handlers are attached so no already-delivered chunk is dropped.
                    pausedResponse.pause();
                    return pausedResponse;
                })
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Buffer collected = Buffer.buffer();
        CompletableFuture<Void> firstChunkSeen = new CompletableFuture<>();
        CompletableFuture<Buffer> bodyEnded = new CompletableFuture<>();
        response.exceptionHandler(error -> {
            firstChunkSeen.completeExceptionally(error);
            bodyEnded.completeExceptionally(error);
        });
        response.handler(chunk -> {
            collected.appendBuffer(chunk);
            firstChunkSeen.complete(null);
        });
        response.endHandler(ignored -> bodyEnded.complete(collected.copy()));
        response.resume();

        // The client has the first chunk: the response is committed and streaming.
        firstChunkSeen.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        GatedReadStream stream = streamCreated.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        stream.failFromSource(new IllegalStateException(SOURCE_FAILURE_MESSAGE));

        Buffer body = bodyEnded.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        RestRequestCompletedEvent event = completed.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(200, response.statusCode(), "the status line was already on the wire when the source failed");
        assertEquals(
                FIRST_CHUNK,
                body,
                "the client must see a truncated but cleanly ended body — only the chunk written "
                        + "before the source failed");
        assertEquals(200, event.statusCode(), "the completion event must report the status actually sent");
        assertNotNull(
                event.wireFailureCode(),
                "a truncated 200 must be observable: wireFailureCode must be populated from the "
                        + "pipeline's wire-failure marker");
        assertEquals(
                "IllegalStateException",
                event.wireFailureCode(),
                "wireFailureCode must be the source failure's class simple name");
        assertTrue(
                !SOURCE_FAILURE_MESSAGE.equals(event.safeFailureMessage()),
                "the raw source-failure message must never reach the event");
    }

    @Test
    @DisplayName("A client connection reset during streaming surfaces as a non-null wireFailureCode")
    void clientAbortDuringStreamingSurfacesWireFailureCode() throws Exception {
        CompletableFuture<GatedReadStream> streamCreated = new CompletableFuture<>();
        CompletableFuture<RestRequestCompletedEvent> completed = new CompletableFuture<>();
        int port = startServer(streamCreated, completed);

        // Raw socket: only a hard RST (SO_LINGER 0) reproduces a client abort; a graceful FIN from
        // the pooled HttpClient would let the server finish the response normally.
        try (Socket socket = new Socket(LOOPBACK, port)) {
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(ASYNC_TIMEOUT_SECONDS));
            writeRawGetRequest(socket, STREAM_PATH);

            int firstBodyByte = readFirstResponseBodyByte(socket);
            assertTrue(firstBodyByte >= 0, "the client must receive streamed body bytes before the reset");
            // The server-side stream is still open: no further chunk is emitted, so nothing is in
            // flight when the connection dies.
            streamCreated.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            socket.setSoLinger(true, 0);
            socket.close();
        }

        RestRequestCompletedEvent event = completed.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals(200, event.statusCode(), "the status line was already on the wire when the client aborted");
        assertNotNull(
                event.wireFailureCode(),
                "a client abort during streaming must be observable: wireFailureCode must be "
                        + "populated from the failed response end-handler result");
        // The exact classification is transport- and platform-dependent: a hard RST surfaces as the
        // JDK socket exception's class simple name (e.g. SocketException), while Vert.x's own
        // connection-close signal normalizes to "ConnectionClosed". Pinning either value would pin
        // the operating system, not the contract — what must hold is that the code is present and
        // stays a low-cardinality identifier safe to use as a metric label.
        assertTrue(
                event.wireFailureCode().matches("[A-Za-z0-9_$]+"),
                "wireFailureCode must remain a low-cardinality identifier (a class simple name or a "
                        + "normalized constant), but was: " + event.wireFailureCode());
    }

    // --- Server assembly ---

    /**
     * Starts a server whose root router carries the real ROOT middlewares
     * ({@link RequestContextLifecycle}, {@link RestRequestCompletionEmitter}) and mounts the API
     * router produced by {@link JaxRsRouterMount}.
     *
     * @param streamCreated completed by {@link StreamingResource} with the per-request stream
     * @param completed     completed by the registered listener with the request's completion event
     * @return the actual bound port, read after the bind
     * @throws Exception if the router build or the bind does not complete within the async bound
     */
    private int startServer(
            CompletableFuture<GatedReadStream> streamCreated, CompletableFuture<RestRequestCompletedEvent> completed)
            throws Exception {
        RestRequestCompletionEmitter emitter = new RestRequestCompletionEmitter(
                Optional.empty(), new DefaultContextHolder(), Set.of(completed::complete));

        JaxRsRouterMount mount =
                buildFactory().create("/*", "openapi.json", Set.of(new StreamingResource(streamCreated)));
        Router apiRouter = mount.createRouter(vertx)
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Router root = Router.router(vertx);
        // 1. RequestContextLifecycle (ORDER = Integer.MIN_VALUE) — its end handler fires last.
        root.route().order(RequestContextLifecycle.ORDER).handler(new RequestContextLifecycle());
        // 2. RestRequestCompletionEmitter (ORDER + 5) — registers the end handler that emits the event.
        root.route().order(emitter.priority()).handler(emitter);
        // 3. The JAX-RS API router.
        root.route("/*").subRouter(apiRouter);

        server = vertx.createHttpServer()
                .requestHandler(root)
                .listen(0, LOOPBACK)
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        return server.actualPort();
    }

    /**
     * Builds a {@link JaxRsRouterMount.Factory} with the production streaming encoder and the
     * chunked-response interceptor; every other collaborator is empty or a framework default.
     *
     * @return the factory used by {@link #startServer}
     */
    private static JaxRsRouterMount.Factory buildFactory() {
        ChunkedResponseInterceptor chunked = new ChunkedResponseInterceptor();
        JaxRsConfig jaxRsConfig = JaxRsConfig.builder()
                .validationStrategy(NoneValidationStrategy.ID)
                .build();

        return TestFactories.builder()
                .requestInterceptors(Set.of(chunked))
                .encoders(List.of(new ReadStreamBodyEncoder(), new JsonBodyEncoder()))
                .jaxRsConfig(jaxRsConfig)
                .build();
    }

    // --- Raw socket helpers ---

    /**
     * Writes a minimal HTTP/1.1 GET request onto the raw socket.
     *
     * @param socket the connected socket
     * @param path   the request path
     * @throws IOException if the request cannot be written
     */
    private static void writeRawGetRequest(Socket socket, String path) throws IOException {
        String request =
                "GET " + path + " HTTP/1.1\r\n" + "Host: " + LOOPBACK + "\r\n" + "Connection: keep-alive\r\n\r\n";
        socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    /**
     * Reads the response head from the raw socket, asserts it is a 200, and returns the first byte
     * of the body framing that follows it (proving the response is committed and streaming).
     *
     * @param socket the connected socket
     * @return the first byte after the header terminator
     * @throws IOException if the response ends before the first body byte or exceeds the test bound
     */
    private static int readFirstResponseBodyByte(Socket socket) throws IOException {
        ByteArrayOutputStream headers = new ByteArrayOutputStream();
        byte[] terminator = {'\r', '\n', '\r', '\n'};
        int matched = 0;
        while (matched < terminator.length) {
            int value = socket.getInputStream().read();
            if (value < 0) {
                throw new IOException("response ended before the header terminator");
            }
            headers.write(value);
            matched = value == terminator[matched] ? matched + 1 : (value == terminator[0] ? 1 : 0);
            if (headers.size() > 16 * 1024) {
                throw new IOException("response headers exceeded the test bound");
            }
        }
        String headerText = headers.toString(StandardCharsets.US_ASCII);
        assertTrue(headerText.startsWith("HTTP/1.1 200"), "the streaming resource must start a 200 response");
        int firstBodyByte = socket.getInputStream().read();
        if (firstBodyByte < 0) {
            throw new IOException("response ended before the first body byte");
        }
        return firstBodyByte;
    }

    // --- Fixtures ---

    /**
     * JAX-RS resource returning a gated {@link ReadStream} of {@link Buffer} chunks so the test can
     * decide when the source fails. The stream is published to the test through the future passed at
     * construction.
     */
    @Path("/wire")
    public static class StreamingResource {

        private final CompletableFuture<GatedReadStream> streamCreated;

        /**
         * Creates the resource.
         *
         * @param streamCreated completed with the stream created for the request under test
         */
        StreamingResource(CompletableFuture<GatedReadStream> streamCreated) {
            this.streamCreated = streamCreated;
        }

        /**
         * Streams a single seeded chunk and then stays open until the test fails the source or the
         * client aborts.
         *
         * @return the gated stream bound to the current request context
         */
        @GET
        @Path("/stream")
        @Produces(MediaType.APPLICATION_OCTET_STREAM)
        @Operation(operationId = "getWireStream")
        public ReadStream<Buffer> stream() {
            GatedReadStream stream = new GatedReadStream(Vertx.currentContext(), FIRST_CHUNK);
            streamCreated.complete(stream);
            return stream;
        }
    }

    /**
     * Marks every response as chunked before the body is written. A streamed response of unknown
     * length must declare chunked transfer encoding; a declared {@code Content-Length} would instead
     * put the truncated response on the pipeline's <em>reset</em> path (an under-length fixed-length
     * body cannot be framed honestly by {@code end()}), which is not the behavior under test here —
     * these tests pin the clean-end path for a chunked stream.
     *
     * <p>This is a test-explicitness device, not a production requirement. A plain
     * {@link ReadStream} entity yields a {@code StreamingBody} with neither {@code Content-Length}
     * nor {@code Transfer-Encoding} set by {@link ReadStreamBodyEncoder}, and Vert.x then applies
     * chunked transfer encoding itself on the first write (HTTP/1.1) — so a production streaming
     * resource is correctly framed without any interceptor. Setting it here makes the framing these
     * tests depend on explicit rather than implicit.
     */
    private static final class ChunkedResponseInterceptor implements RequestInterceptor {

        @Override
        public void onRequest(RoutingContext rc) {
            rc.response().setChunked(true);
        }
    }

    /**
     * Manually driven {@link ReadStream} of {@link Buffer} chunks used as the streamed response
     * body.
     *
     * <p>Honors the Vert.x demand protocol (pause / resume / fetch) so it can be piped, delivers
     * everything on the Vert.x context captured at construction (the request context), and lets the
     * test decide from its own thread exactly when a source failure reaches the pipe. Neither test
     * ends the stream normally — the end handler is retained only to satisfy the {@link ReadStream}
     * contract.
     */
    private static final class GatedReadStream implements ReadStream<Buffer> {

        private final Context context;
        private final Deque<Buffer> pending = new ArrayDeque<>();

        private Handler<Buffer> dataHandler;
        private Handler<Void> endHandler;
        private Handler<Throwable> exceptionHandler;
        private long demand;
        private Throwable pendingFailure;
        private boolean terminated;

        /**
         * Creates the stream with one chunk already queued, so the response head and the first body
         * bytes reach the client without any test-side coordination.
         *
         * @param context      the Vert.x context every delivery is dispatched on
         * @param initialChunk the chunk emitted as soon as the pipe resumes the stream
         */
        private GatedReadStream(Context context, Buffer initialChunk) {
            this.context = context;
            this.pending.add(initialChunk);
        }

        @Override
        public synchronized GatedReadStream exceptionHandler(Handler<Throwable> handler) {
            this.exceptionHandler = handler;
            return this;
        }

        @Override
        public GatedReadStream handler(Handler<Buffer> handler) {
            synchronized (this) {
                this.dataHandler = handler;
            }
            scheduleDrain();
            return this;
        }

        @Override
        public synchronized GatedReadStream pause() {
            demand = 0;
            return this;
        }

        @Override
        public GatedReadStream resume() {
            synchronized (this) {
                demand = Long.MAX_VALUE;
            }
            scheduleDrain();
            return this;
        }

        @Override
        public GatedReadStream fetch(long amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("amount must be non-negative");
            }
            synchronized (this) {
                if (demand != Long.MAX_VALUE) {
                    demand = Math.min(Long.MAX_VALUE, demand + amount);
                }
            }
            scheduleDrain();
            return this;
        }

        @Override
        public GatedReadStream endHandler(Handler<Void> handler) {
            synchronized (this) {
                this.endHandler = handler;
            }
            scheduleDrain();
            return this;
        }

        /**
         * Queues a source failure for delivery to the pipe. Callable from the test thread; the
         * failure is delivered on the captured context once every queued chunk has been drained.
         *
         * @param cause the failure the stream reports to its exception handler
         */
        void failFromSource(Throwable cause) {
            synchronized (this) {
                if (terminated || pendingFailure != null) {
                    return;
                }
                pendingFailure = cause;
            }
            scheduleDrain();
        }

        /** Schedules a drain pass on the captured request context. */
        private void scheduleDrain() {
            context.runOnContext(ignored -> drain());
        }

        /**
         * Delivers as many queued chunks as there is demand for, then the queued source failure (if
         * any). Handlers are always invoked outside the monitor and always on the captured context.
         */
        private void drain() {
            while (true) {
                Handler<Buffer> emit = null;
                Buffer chunk = null;
                Handler<Throwable> failureHandler = null;
                Throwable cause = null;
                synchronized (this) {
                    if (terminated) {
                        return;
                    }
                    if (dataHandler != null && demand > 0 && !pending.isEmpty()) {
                        chunk = pending.poll();
                        emit = dataHandler;
                        if (demand != Long.MAX_VALUE) {
                            demand--;
                        }
                    } else if (pendingFailure != null && pending.isEmpty()) {
                        terminated = true;
                        cause = pendingFailure;
                        failureHandler = exceptionHandler;
                    } else {
                        return;
                    }
                }
                if (emit != null) {
                    emit.handle(chunk);
                    continue;
                }
                if (failureHandler != null) {
                    failureHandler.handle(cause);
                }
                return;
            }
        }
    }
}
