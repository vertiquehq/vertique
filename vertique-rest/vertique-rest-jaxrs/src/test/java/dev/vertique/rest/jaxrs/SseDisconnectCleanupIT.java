// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.context.DefaultContextHolder;
import dev.vertique.rest.core.config.JaxRsConfig;
import dev.vertique.rest.core.config.SseConfig;
import dev.vertique.rest.core.events.RestRequestCompletedEvent;
import dev.vertique.rest.core.events.RestRequestCompletionEmitter;
import dev.vertique.rest.core.middleware.RequestContextLifecycle;
import dev.vertique.rest.core.sse.BufferOverflowPolicy;
import dev.vertique.rest.core.sse.SseEvent;
import dev.vertique.rest.jaxrs.validation.NoneValidationStrategy;
import io.swagger.v3.oas.annotations.Operation;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.RoutingContext;
import io.vertx.junit5.VertxExtension;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * End-to-end acceptance proof that an SSE client disconnect mid-stream still produces exactly one
 * completion event and runs {@link RequestContextLifecycle.Handle#closeAll()}.
 *
 * <p>{@link SseReadStream} previously registered {@code response.closeHandler(...)} for disconnect
 * detection. That setter is single-slot: Vert.x Web has already installed its own close handler on
 * the first {@code addEndHandler} call ({@link RequestContextLifecycle} makes that call for every
 * request), so the SSE registration silently replaced it. No routing-context end handler fired for
 * the disconnect — including the completion emitter and lifecycle cleanup. Disconnect detection now
 * uses multicast {@link RoutingContext#addEndHandler}, matching MCP's settlement hooks.
 *
 * <p>This proof watches the effect rather than the mechanism: a live SSE response is left open after
 * the first event reaches the client, the client's socket is hard-reset, and both the completion
 * event and a lifecycle {@code onClose} registration must observe the disconnect. Reverting
 * {@link SseReadStream} to {@code response.closeHandler} turns it red.
 *
 * <p><strong>Raw {@link Socket} exemption.</strong> Only a hard RST ({@code SO_LINGER(0)}) reproduces
 * a client abort mid-stream; a buffered {@code WebClient} exchange cannot express that. The decisive
 * exchange uses the raw socket only — no Vert.x {@code HttpClient} is involved.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 20, unit = TimeUnit.SECONDS)
public class SseDisconnectCleanupIT {

    // --- Constants ---

    /** Loopback address used for both the bind and every client connection. */
    private static final String LOOPBACK = "127.0.0.1";

    /** Bound wait for every asynchronous step; the class-level {@code @Timeout} is the backstop. */
    private static final long ASYNC_TIMEOUT_SECONDS = 5;

    /** Request path served by {@link SseResource}. */
    private static final String STREAM_PATH = "/sse/stream";

    /** Request path whose source stream throws when the SSE adapter cancels it on disconnect. */
    private static final String THROWING_CANCEL_PATH = "/sse/stream-throwing-cancel";

    // --- Class-scoped resources ---

    private static Vertx vertx;

    // --- Per-test resources ---

    private HttpServer server;

    /**
     * Captures the class-scoped Vert.x instance injected by vertx-junit5.
     *
     * @param injectedVertx the class-scoped Vert.x instance
     */
    @BeforeAll
    static void setUpVertx(Vertx injectedVertx) {
        vertx = injectedVertx;
    }

    /**
     * Closes the per-test {@link HttpServer}.
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
    @DisplayName("An SSE client disconnect mid-stream emits one completion and closes the request lifecycle")
    void shouldEmitCompletionAndCloseLifecycleWhenSseClientDisconnects() throws Exception {
        assertDisconnectEmitsOneCompletionAndOneLifecycleClose(STREAM_PATH);
    }

    @Test
    @DisplayName("An SSE disconnect whose source cancellation throws still emits one completion and one close")
    void shouldEmitCompletionAndCloseLifecycleWhenSourceCancellationThrows() throws Exception {
        assertDisconnectEmitsOneCompletionAndOneLifecycleClose(THROWING_CANCEL_PATH);
    }

    /**
     * Drives a hard client reset against {@code path} and asserts exactly one completion event and
     * one lifecycle close.
     *
     * @param path the SSE resource path to connect to
     * @throws Exception if any asynchronous step fails or times out
     */
    private void assertDisconnectEmitsOneCompletionAndOneLifecycleClose(String path) throws Exception {
        CompletableFuture<Void> firstEventSent = new CompletableFuture<>();
        CompletableFuture<Void> lifecycleClosed = new CompletableFuture<>();
        List<RestRequestCompletedEvent> completed = new CopyOnWriteArrayList<>();
        AtomicInteger closeCount = new AtomicInteger();

        int port = startServer(firstEventSent, lifecycleClosed, closeCount, completed);

        try (Socket socket = new Socket(LOOPBACK, port)) {
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(ASYNC_TIMEOUT_SECONDS));
            writeRawGetRequest(socket, path);

            int firstBodyByte = readFirstResponseBodyByte(socket);
            assertTrue(firstBodyByte >= 0, "the client must receive SSE body bytes before the disconnect");
            firstEventSent.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            socket.setSoLinger(true, 0);
            socket.close();
        }

        lifecycleClosed.get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        awaitExactlyOneCompletion(completed);

        assertEquals(
                1,
                closeCount.get(),
                "DECISIVE: RequestContextLifecycle.Handle#closeAll() must run exactly once for an SSE "
                        + "client that disconnected mid-stream");
        assertEquals(1, completed.size(), "exactly one RestRequestCompletedEvent must be published");
        assertEquals(200, completed.get(0).statusCode(), "the status line was already on the wire");
    }

    // --- Server assembly ---

    /**
     * Starts a server whose root router carries the real ROOT middlewares
     * ({@link RequestContextLifecycle}, {@link RestRequestCompletionEmitter}) and mounts the API
     * router produced by {@link JaxRsRouterMount}.
     *
     * @param firstEventSent  completed by {@link SseResource} after the first SSE event is accepted
     * @param lifecycleClosed completed by the lifecycle {@code onClose} registration
     * @param closeCount      incremented by that same {@code onClose} registration
     * @param completed       collects every completion event the emitter publishes
     * @return the actual bound port, read after the bind
     * @throws Exception if the router build or the bind does not complete within the async bound
     */
    private int startServer(
            CompletableFuture<Void> firstEventSent,
            CompletableFuture<Void> lifecycleClosed,
            AtomicInteger closeCount,
            List<RestRequestCompletedEvent> completed)
            throws Exception {
        RestRequestCompletionEmitter emitter =
                new RestRequestCompletionEmitter(Optional.empty(), new DefaultContextHolder(), Set.of(completed::add));

        JaxRsRouterMount mount = buildFactory().create("/*", "openapi.json", Set.of(new SseResource(firstEventSent)));
        Router apiRouter = mount.createRouter(vertx)
                .toCompletionStage()
                .toCompletableFuture()
                .get(ASYNC_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        Router root = Router.router(vertx);
        root.route().order(RequestContextLifecycle.ORDER).handler(new RequestContextLifecycle());
        // Probe closeAll on the same RoutingContext the lifecycle owns (root), not the JAX-RS
        // sub-router context the resource method sees.
        root.route().order(RequestContextLifecycle.ORDER + 1).handler(ctx -> {
            RequestContextLifecycle.fromRoutingContext(ctx).onCloseRun(() -> {
                closeCount.incrementAndGet();
                lifecycleClosed.complete(null);
            });
            ctx.next();
        });
        root.route().order(emitter.priority()).handler(emitter);
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
     * Builds a {@link JaxRsRouterMount.Factory} with the production SSE encoder ahead of the JSON
     * fallback; every other collaborator is empty or a framework default.
     *
     * @return the factory used by {@link #startServer}
     */
    private static JaxRsRouterMount.Factory buildFactory() {
        JaxRsConfig jaxRsConfig = JaxRsConfig.builder()
                .validationStrategy(NoneValidationStrategy.ID)
                .sse(SseConfig.builder().keepAliveEnabled(false).build())
                .build();

        return TestFactories.builder()
                .encoders(List.of(new SseBodyEncoder(jaxRsConfig.sse()), new JsonBodyEncoder()))
                .jaxRsConfig(jaxRsConfig)
                .build();
    }

    /**
     * Polls until exactly one completion event has been recorded, or the async bound elapses.
     *
     * @param completed the shared capture list
     * @throws Exception if the bound elapses without a completion
     */
    private static void awaitExactlyOneCompletion(List<RestRequestCompletedEvent> completed) throws Exception {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(ASYNC_TIMEOUT_SECONDS);
        while (System.currentTimeMillis() < deadline) {
            if (completed.size() == 1) {
                return;
            }
            Thread.sleep(10);
        }
        assertEquals(1, completed.size(), "expected exactly one completion event; captured: " + completed.size());
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
        String request = "GET " + path + " HTTP/1.1\r\n"
                + "Host: " + LOOPBACK + "\r\n"
                + "Accept: text/event-stream\r\n"
                + "Connection: keep-alive\r\n\r\n";
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
        assertTrue(
                headerText.startsWith("HTTP/1.1 200"),
                "the SSE resource must start a 200 response; got: "
                        + headerText.lines().findFirst().orElse(headerText));
        int firstBodyByte = socket.getInputStream().read();
        if (firstBodyByte < 0) {
            throw new IOException("response ended before the first body byte");
        }
        return firstBodyByte;
    }

    // --- Fixtures ---

    /**
     * JAX-RS resource that opens an SSE channel, emits one event, and leaves the stream open until
     * the client disconnects.
     */
    @Path("/sse")
    public static class SseResource {

        private final CompletableFuture<Void> firstEventSent;

        /**
         * Creates the resource.
         *
         * @param firstEventSent completed after the first event is accepted by the channel
         */
        SseResource(CompletableFuture<Void> firstEventSent) {
            this.firstEventSent = firstEventSent;
        }

        /**
         * Opens a hanging SSE stream after sending one event.
         *
         * @return the open SSE event stream
         */
        @GET
        @Path("/stream")
        @Produces("text/event-stream")
        @Operation(operationId = "getSseStream")
        public ReadStream<SseEvent> stream() {
            DefaultSseChannel channel =
                    new DefaultSseChannel(Vertx.currentContext().owner(), 16, BufferOverflowPolicy.FAIL);
            channel.send(SseEvent.of("ping")).onSuccess(v -> firstEventSent.complete(null));
            return channel.stream();
        }

        /**
         * Opens a hanging SSE stream whose {@code handler(null)} cancellation throws, modelling a
         * custom {@link ReadStream} with a faulty cancel path.
         *
         * @return the open SSE event stream with a throwing cancellation
         */
        @GET
        @Path("/stream-throwing-cancel")
        @Produces("text/event-stream")
        @Operation(operationId = "getSseStreamThrowingCancel")
        public ReadStream<SseEvent> streamThrowingCancel() {
            return new ThrowingCancelStream(stream());
        }
    }

    /** Delegating {@link ReadStream} whose {@code handler(null)} cancellation always throws. */
    private static final class ThrowingCancelStream implements ReadStream<SseEvent> {

        private final ReadStream<SseEvent> delegate;

        ThrowingCancelStream(ReadStream<SseEvent> delegate) {
            this.delegate = delegate;
        }

        @Override
        public ReadStream<SseEvent> exceptionHandler(Handler<Throwable> handler) {
            delegate.exceptionHandler(handler);
            return this;
        }

        @Override
        public ReadStream<SseEvent> handler(Handler<SseEvent> handler) {
            if (handler == null) {
                throw new IllegalStateException("custom stream cancellation failed");
            }
            delegate.handler(handler);
            return this;
        }

        @Override
        public ReadStream<SseEvent> pause() {
            delegate.pause();
            return this;
        }

        @Override
        public ReadStream<SseEvent> resume() {
            delegate.resume();
            return this;
        }

        @Override
        public ReadStream<SseEvent> fetch(long amount) {
            delegate.fetch(amount);
            return this;
        }

        @Override
        public ReadStream<SseEvent> endHandler(Handler<Void> endHandler) {
            delegate.endHandler(endHandler);
            return this;
        }
    }
}
