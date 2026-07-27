// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.interceptor.RequestInterceptor;
import dev.vertique.rest.core.response.ResponseBodyEncoder;
import dev.vertique.rest.core.response.SerializedBody;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.json.Json;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.core.Response;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

/**
 * Unit tests for {@link DefaultResponseSerializer}.
 *
 * <p>Verifies entity encoding dispatch: null entity, Buffer, String, byte[], and JSON fallback,
 * including default and explicit Content-Type handling, the no-encoder-matches 500 path,
 * and {@link RequestInterceptor#onSerialize} invocation.
 *
 * <p>Also verifies the dual-channel completion contract of
 * {@link dev.vertique.rest.core.response.ResponseSerializer#serialize}: every wire branch returns
 * a future mirroring the underlying {@code end(...)} / pipe outcome, streaming failures fail that
 * future without the serializer ending the response, and encode-time failures stay synchronous
 * throws with nothing initiated on the wire.
 *
 * <p>Note: status code and response headers are set by {@link ResponsePipeline} before
 * the serializer is called. HEAD responses and empty-body responses (204, 304, 412) are handled
 * upstream and do not reach the serializer in normal operation.
 */
class DefaultResponseSerializerTest {

    private static final List<ResponseBodyEncoder> DEFAULT_ENCODERS = List.of(
            new BufferBodyEncoder(),
            new ByteArrayBodyEncoder(),
            new StringBodyEncoder(),
            new ReadStreamBodyEncoder(),
            new JsonBodyEncoder());

    private DefaultResponseSerializer serializer;
    private RoutingContext ctx;
    private HttpServerResponse httpResponse;
    private MultiMap headers;

    @BeforeEach
    void setUp() {
        serializer = new DefaultResponseSerializer(List.of(), DEFAULT_ENCODERS);

        ctx = mock(RoutingContext.class);
        httpResponse = mock(HttpServerResponse.class, (Answer<Object>) DefaultResponseSerializerTest::wireDefault);

        var httpRequest = mock(HttpServerRequest.class);
        when(httpRequest.method()).thenReturn(HttpMethod.GET);
        when(ctx.request()).thenReturn(httpRequest);
        when(ctx.response()).thenReturn(httpResponse);
        when(httpResponse.setStatusCode(anyInt())).thenReturn(httpResponse);
        headers = MultiMap.caseInsensitiveMultiMap();
        when(httpResponse.headers()).thenReturn(headers);
        when(httpResponse.putHeader(anyString(), anyString())).thenAnswer(inv -> {
            headers.add((String) inv.getArgument(0), (String) inv.getArgument(1));
            return httpResponse;
        });
    }

    // --- Null entity (safety-net path) ---

    @Test
    @DisplayName("Should end with no body when serialize() is called with a null entity")
    void shouldEndWithNoBodyForNullEntity() {
        Response response = Response.noContent().build();

        serializer.serialize(ctx, response);

        verify(httpResponse).end();
    }

    // --- String entity ---

    @Test
    @DisplayName("Should write String entity as-is with default text/plain Content-Type")
    void shouldWriteStringAsIs() {
        Response response = Response.ok("pong").build();

        serializer.serialize(ctx, response);

        verify(httpResponse).putHeader("Content-Type", "text/plain");
        verify(httpResponse).end(Buffer.buffer("pong"));
    }

    // --- byte[] entity ---

    @Test
    @DisplayName("Should write byte[] entity as Buffer with default application/octet-stream Content-Type")
    void shouldWriteByteArrayAsBuffer() {
        byte[] data = new byte[] {1, 2, 3};
        Response response = Response.ok(data).build();

        serializer.serialize(ctx, response);

        verify(httpResponse).putHeader("Content-Type", "application/octet-stream");
        verify(httpResponse).end(Buffer.buffer(data));
    }

    // --- JSON fallback ---

    @Test
    @DisplayName("Should JSON-encode object entity with default application/json Content-Type")
    void shouldJsonEncodeObjectEntity() {
        Map<String, String> entity = Map.of("k", "v");
        Response response = Response.ok(entity).build();

        serializer.serialize(ctx, response);

        verify(httpResponse).putHeader("Content-Type", "application/json");
        verify(httpResponse).end(Buffer.buffer(Json.encode(entity)));
    }

    // --- No encoder matches ---

    @Test
    @DisplayName("Should return 500 with ProblemDetail when no encoder matches the entity type")
    void shouldReturn500WhenNoEncoderMatches() {
        DefaultResponseSerializer emptyEncoderSerializer = new DefaultResponseSerializer(List.of(), List.of());
        Response response = Response.ok("data").build();

        emptyEncoderSerializer.serialize(ctx, response);

        verify(httpResponse).setStatusCode(500);
        verify(httpResponse).putHeader("Content-Type", "application/problem+json");
        verify(httpResponse).end(argThat((String s) -> s.contains(String.class.getName())));
    }

    // --- Mismatched Content-Type ---

    @Test
    @DisplayName("Should return 500 when explicit Content-Type does not match any encoder")
    void shouldReturn500ForMismatchedContentType() {
        Map<String, String> entity = Map.of("k", "v");
        // Pre-set Content-Type on headers (simulating what the handler does)
        headers.add("Content-Type", "application/xml");
        Response response = Response.ok(entity).type("application/xml").build();

        serializer.serialize(ctx, response);

        verify(httpResponse).setStatusCode(500);
        verify(httpResponse).putHeader("Content-Type", "application/problem+json");
    }

    // --- Explicit Content-Type preservation ---

    @Test
    @DisplayName("Should preserve explicit Content-Type over encoder default")
    void shouldPreserveExplicitContentTypeOverEncoderDefault() {
        // Pre-set Content-Type on headers (simulating what the handler does)
        headers.add("Content-Type", "text/html");
        Response response = Response.ok("data").type("text/html").build();

        serializer.serialize(ctx, response);

        verify(httpResponse, never()).putHeader("Content-Type", "text/plain");
        verify(httpResponse).end(Buffer.buffer("data"));
    }

    // --- onSerialize hook ---

    @Test
    @DisplayName("Should invoke onSerialize hook with non-null SerializedBody for non-null entity")
    void shouldInvokeOnSerializeWithSerializedBody() {
        RequestInterceptor hook = mock(RequestInterceptor.class);
        DefaultResponseSerializer hookSerializer = new DefaultResponseSerializer(List.of(hook), DEFAULT_ENCODERS);
        Response response = Response.ok("payload").build();

        hookSerializer.serialize(ctx, response);

        verify(hook).onSerialize(eq(ctx), any(Response.class), any(SerializedBody.class));
    }

    @Test
    @DisplayName("Should invoke onSerialize hook with 500 Response when no encoder matches")
    void shouldInvokeOnSerializeWhenNoEncoderMatches() {
        RequestInterceptor hook = mock(RequestInterceptor.class);
        DefaultResponseSerializer emptyEncoderSerializer = new DefaultResponseSerializer(List.of(hook), List.of());
        Response response = Response.ok("data").build();

        emptyEncoderSerializer.serialize(ctx, response);

        verify(hook).onSerialize(eq(ctx), argThat((Response r) -> r.getStatus() == 500), any(SerializedBody.class));
    }

    @Test
    @DisplayName("Should invoke onSerialize hook with null body for null entity")
    void shouldInvokeOnSerializeWithNullBodyForNullEntity() {
        RequestInterceptor hook = mock(RequestInterceptor.class);
        DefaultResponseSerializer hookSerializer = new DefaultResponseSerializer(List.of(hook), DEFAULT_ENCODERS);
        Response response = Response.noContent().build();

        hookSerializer.serialize(ctx, response);

        verify(hook).onSerialize(eq(ctx), any(Response.class), isNull());
    }

    // --- GET body encoding ---

    @Test
    @DisplayName("Should write body normally for GET request")
    void shouldWriteBodyForGetRequest() {
        Response response = Response.ok("hello").build();

        serializer.serialize(ctx, response);

        verify(httpResponse).end(Buffer.buffer("hello"));
    }

    // --- Buffered wire completion ---

    @Test
    @DisplayName("Buffered body: the returned future mirrors the end(buffer) future")
    void bufferedBodyFutureMirrorsEndFuture() {
        Promise<Void> endPromise = Promise.promise();
        when(httpResponse.end(any(Buffer.class))).thenReturn(endPromise.future());
        Response response = Response.ok("pong").build();

        Future<Void> completion = serializer.serialize(ctx, response);

        assertFalse(completion.isComplete(), "Completion must stay pending while end(buffer) is pending");
        endPromise.complete();
        assertTrue(completion.succeeded(), "Completion must succeed once end(buffer) succeeds");
    }

    @Test
    @DisplayName("Null entity: the returned future mirrors the end() future")
    void nullEntityReturnsEndFuture() {
        Promise<Void> endPromise = Promise.promise();
        when(httpResponse.end()).thenReturn(endPromise.future());
        Response response = Response.noContent().build();

        Future<Void> completion = serializer.serialize(ctx, response);

        assertFalse(completion.isComplete(), "Completion must stay pending while end() is pending");
        endPromise.complete();
        assertTrue(completion.succeeded(), "Completion must succeed once end() succeeds");
    }

    @Test
    @DisplayName("No matching encoder: the returned future mirrors the 500 fallback end future")
    void noEncoderFallback500ReturnsEndFuture() {
        Promise<Void> endPromise = Promise.promise();
        when(httpResponse.end(anyString())).thenReturn(endPromise.future());
        DefaultResponseSerializer emptyEncoderSerializer = new DefaultResponseSerializer(List.of(), List.of());
        Response response = Response.ok("data").build();

        Future<Void> completion = emptyEncoderSerializer.serialize(ctx, response);

        assertFalse(completion.isComplete(), "Completion must stay pending while the 500 end is pending");
        endPromise.complete();
        assertTrue(completion.succeeded(), "Completion must succeed once the 500 end succeeds");
    }

    // --- Streaming wire completion ---

    @Test
    @DisplayName("Streaming body: the future completes only after every chunk and the end signal")
    void streamingBodySuccessCompletesAfterFullPipe() {
        GatedReadStream stream = new GatedReadStream();
        Response response = Response.ok(stream).build();

        Future<Void> completion = serializer.serialize(ctx, response);

        assertFalse(completion.isComplete(), "Completion must be pending while the stream is open");
        stream.emit(Buffer.buffer("chunk-1"));
        assertFalse(completion.isComplete(), "Completion must stay pending between chunks");
        stream.emit(Buffer.buffer("chunk-2"));
        stream.complete();

        assertTrue(completion.succeeded(), "Completion must succeed once the stream is fully piped and ended");
        verify(httpResponse).write(Buffer.buffer("chunk-1"));
        verify(httpResponse).write(Buffer.buffer("chunk-2"));
        verify(httpResponse).end();
    }

    @Test
    @DisplayName("Streaming body: a mid-stream source failure fails the future and the serializer never ends")
    void streamingBodyMidStreamFailureFailsFutureWithSourceCause() {
        GatedReadStream stream = new GatedReadStream();
        RuntimeException sourceFailure = new RuntimeException("source blew up mid-stream");
        Future<Void> completion = serializer.serialize(ctx, Response.ok(stream).build());
        stream.emit(Buffer.buffer("chunk-1"));

        stream.fail(sourceFailure);

        assertTrue(completion.failed(), "A mid-stream source failure must fail the completion future");
        assertSame(sourceFailure, completion.cause(), "The completion future must carry the source cause");
        verify(httpResponse, never()).end();
        verify(httpResponse, never()).end(any(Buffer.class));
    }

    @Test
    @DisplayName("Streaming body: a source failure on resume yields an already-failed future at return")
    void streamingBodyImmediateSourceFailureYieldsFailedFutureAtReturn() {
        RuntimeException sourceFailure = new RuntimeException("source failed on resume");
        GatedReadStream stream = GatedReadStream.failingOnResume(sourceFailure);

        Future<Void> completion = serializer.serialize(ctx, Response.ok(stream).build());

        assertTrue(completion.failed(), "serialize() must return an already-failed future for an immediate failure");
        assertSame(sourceFailure, completion.cause(), "The completion future must carry the source cause");
        verify(httpResponse, never()).end();
    }

    @Test
    @DisplayName("Streaming body: a client write failure fails the future with the unwrapped cause")
    void streamingBodyClientWriteFailureFailsFuture() {
        RuntimeException writeFailure = new RuntimeException("client went away");
        when(httpResponse.write(any(Buffer.class))).thenReturn(Future.failedFuture(writeFailure));
        GatedReadStream stream = new GatedReadStream();
        Future<Void> completion = serializer.serialize(ctx, Response.ok(stream).build());

        stream.emit(Buffer.buffer("chunk-1"));

        assertTrue(completion.failed(), "A destination write failure must fail the completion future");
        assertSame(writeFailure, completion.cause(), "The WriteException wrapper must be unwrapped to the write cause");
    }

    // --- Synchronous encode failure (no wire initiation) ---

    @Test
    @DisplayName("Encoder failure throws synchronously with no write or end initiated")
    void encodeThrowRemainsSynchronousWithNothingInitiated() {
        RuntimeException encodeFailure = new RuntimeException("encoder blew up");
        DefaultResponseSerializer throwingSerializer =
                new DefaultResponseSerializer(List.of(), List.of(new ThrowingEncoder(encodeFailure)));
        Response response = Response.ok("data").build();

        RuntimeException thrown =
                assertThrows(RuntimeException.class, () -> throwingSerializer.serialize(ctx, response));

        assertSame(encodeFailure, thrown, "The encode-time failure must propagate as a synchronous throw");
        verify(httpResponse, never()).end();
        verify(httpResponse, never()).end(any(Buffer.class));
        verify(httpResponse, never()).end(anyString());
        verify(httpResponse, never()).write(any(Buffer.class));
    }

    // --- Test fixtures ---

    /**
     * Default answer for the {@link HttpServerResponse} mock: {@link Future}-returning wire methods
     * settle successfully and fluent self-returning methods return the mock, so the Vert.x pipe
     * implementation can drive the mock without per-test stubbing of every touched method.
     *
     * @param invocation the intercepted mock invocation
     * @return a succeeded future, the mock itself, or Mockito's default value for the return type
     * @throws Throwable if Mockito's default answer fails
     */
    private static Object wireDefault(InvocationOnMock invocation) throws Throwable {
        Class<?> returnType = invocation.getMethod().getReturnType();
        if (Future.class.isAssignableFrom(returnType)) {
            return Future.succeededFuture();
        }
        if (returnType.isInstance(invocation.getMock())) {
            return invocation.getMock();
        }
        return RETURNS_DEFAULTS.answer(invocation);
    }

    /**
     * Encoder that matches every entity and always throws from {@link #encode}, pinning the
     * synchronous channel of the serializer contract.
     *
     * @param failure the exception thrown by {@link #encode}
     */
    private record ThrowingEncoder(RuntimeException failure) implements ResponseBodyEncoder {

        @Override
        public boolean canEncode(Class<?> entityType, String contentType) {
            return true;
        }

        @Override
        public SerializedBody encode(RoutingContext ctx, Response response, Object entity) {
            throw failure;
        }
    }

    /**
     * Manually driven {@link ReadStream} of {@link Buffer} chunks.
     *
     * <p>Honors the Vert.x demand protocol (pause / resume / fetch) so it can be piped, and lets a
     * test decide exactly when chunks, the end signal, or a source failure reach the pipe.
     * A stream created via {@link #failingOnResume(Throwable)} fails from within {@link #resume()},
     * reproducing a source failure that lands before {@code Pipe.to(...)} returns.
     */
    private static final class GatedReadStream implements ReadStream<Buffer> {

        private final Deque<Buffer> pending = new ArrayDeque<>();
        private final Throwable failOnResume;

        private Handler<Buffer> dataHandler;
        private Handler<Void> endHandler;
        private Handler<Throwable> exceptionHandler;
        private long demand;
        private boolean endPending;
        private boolean terminated;

        private GatedReadStream() {
            this(null);
        }

        private GatedReadStream(Throwable failOnResume) {
            this.failOnResume = failOnResume;
        }

        /**
         * Creates a stream that fails as soon as the pipe resumes it.
         *
         * @param cause the failure delivered to the stream's exception handler
         * @return the gated stream
         */
        static GatedReadStream failingOnResume(Throwable cause) {
            return new GatedReadStream(cause);
        }

        @Override
        public GatedReadStream exceptionHandler(Handler<Throwable> handler) {
            this.exceptionHandler = handler;
            return this;
        }

        @Override
        public GatedReadStream handler(Handler<Buffer> handler) {
            this.dataHandler = handler;
            drain();
            return this;
        }

        @Override
        public GatedReadStream pause() {
            demand = 0;
            return this;
        }

        @Override
        public GatedReadStream resume() {
            demand = Long.MAX_VALUE;
            if (failOnResume != null && !terminated) {
                fail(failOnResume);
                return this;
            }
            drain();
            return this;
        }

        @Override
        public GatedReadStream fetch(long amount) {
            if (amount < 0) {
                throw new IllegalArgumentException("amount must be non-negative");
            }
            if (demand != Long.MAX_VALUE) {
                demand = Math.min(Long.MAX_VALUE, demand + amount);
            }
            drain();
            return this;
        }

        @Override
        public GatedReadStream endHandler(Handler<Void> handler) {
            this.endHandler = handler;
            drain();
            return this;
        }

        /**
         * Offers a chunk to the stream, delivering it when there is outstanding demand.
         *
         * @param chunk the chunk to emit
         */
        void emit(Buffer chunk) {
            pending.add(chunk);
            drain();
        }

        /** Signals normal end of stream once all offered chunks have been delivered. */
        void complete() {
            endPending = true;
            drain();
        }

        /**
         * Delivers a source failure to the stream's exception handler, at most once.
         *
         * @param cause the failure to deliver
         */
        void fail(Throwable cause) {
            if (terminated) {
                return;
            }
            terminated = true;
            if (exceptionHandler != null) {
                exceptionHandler.handle(cause);
            }
        }

        /** Delivers pending chunks and the end signal while demand and handlers allow. */
        private void drain() {
            while (!terminated && dataHandler != null && demand > 0 && !pending.isEmpty()) {
                if (demand != Long.MAX_VALUE) {
                    demand--;
                }
                dataHandler.handle(pending.poll());
            }
            if (!terminated && endPending && pending.isEmpty() && endHandler != null && demand > 0) {
                terminated = true;
                endHandler.handle(null);
            }
        }
    }
}
