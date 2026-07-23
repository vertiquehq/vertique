// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.config.SseConfig;
import dev.vertique.rest.core.sse.SseEvent;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.streams.ReadStream;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Unit tests for {@link SseReadStream}.
 *
 * <p>Verifies the SSE wire-format encoding for all event field combinations and header injection
 * on the first handler attachment.
 */
@ExtendWith(VertxExtension.class)
@Timeout(value = 10, unit = TimeUnit.SECONDS)
class SseReadStreamTest {

    // --- Wire format ---

    @Nested
    @DisplayName("Wire format")
    class WireFormat {

        @Test
        @DisplayName("Should emit data-only event as 'data: <value>\\n\\n'")
        void shouldEmitDataOnlyEvent(Vertx vertx, VertxTestContext testContext) {
            SimpleEventStream source = new SimpleEventStream();
            HttpServerResponse response = stubResponse();
            SseReadStream stream = new SseReadStream(source, vertx, noKeepAlive(), response);

            List<String> emitted = new ArrayList<>();
            stream.handler(buf -> {
                emitted.add(buf.toString());
                testContext.verify(() -> assertEquals("data: hello\n\n", emitted.get(0)));
                testContext.completeNow();
            });

            source.emit(SseEvent.of("hello"));
        }

        @Test
        @DisplayName("Should emit event with id, event type, and data")
        void shouldEmitEventWithAllFields(Vertx vertx, VertxTestContext testContext) {
            SimpleEventStream source = new SimpleEventStream();
            HttpServerResponse response = stubResponse();
            SseReadStream stream = new SseReadStream(source, vertx, noKeepAlive(), response);

            List<String> emitted = new ArrayList<>();
            stream.handler(buf -> {
                emitted.add(buf.toString());
                testContext.verify(() -> assertEquals("id: 42\nevent: update\ndata: hello\n\n", emitted.get(0)));
                testContext.completeNow();
            });

            source.emit(
                    SseEvent.builder().id("42").event("update").data("hello").build());
        }

        @Test
        @DisplayName("Should emit retry field when retryMs is set")
        void shouldEmitRetryField(Vertx vertx, VertxTestContext testContext) {
            SimpleEventStream source = new SimpleEventStream();
            HttpServerResponse response = stubResponse();
            SseReadStream stream = new SseReadStream(source, vertx, noKeepAlive(), response);

            List<String> emitted = new ArrayList<>();
            stream.handler(buf -> {
                emitted.add(buf.toString());
                testContext.verify(() -> assertEquals("retry: 5000\ndata: hello\n\n", emitted.get(0)));
                testContext.completeNow();
            });

            source.emit(SseEvent.builder().retryMs(5000L).data("hello").build());
        }

        @Test
        @DisplayName("Should emit comment-only event as ': <text>\\n\\n'")
        void shouldEmitCommentOnlyEvent(Vertx vertx, VertxTestContext testContext) {
            SimpleEventStream source = new SimpleEventStream();
            HttpServerResponse response = stubResponse();
            SseReadStream stream = new SseReadStream(source, vertx, noKeepAlive(), response);

            List<String> emitted = new ArrayList<>();
            stream.handler(buf -> {
                emitted.add(buf.toString());
                testContext.verify(() -> assertEquals(": heartbeat\n\n", emitted.get(0)));
                testContext.completeNow();
            });

            source.emit(SseEvent.comment("heartbeat"));
        }

        @Test
        @DisplayName("Should split multi-line data across multiple data: lines")
        void shouldSplitMultiLineData(Vertx vertx, VertxTestContext testContext) {
            SimpleEventStream source = new SimpleEventStream();
            HttpServerResponse response = stubResponse();
            SseReadStream stream = new SseReadStream(source, vertx, noKeepAlive(), response);

            List<String> emitted = new ArrayList<>();
            stream.handler(buf -> {
                emitted.add(buf.toString());
                testContext.verify(() -> assertEquals("data: line1\ndata: line2\n\n", emitted.get(0)));
                testContext.completeNow();
            });

            source.emit(SseEvent.of("line1\nline2"));
        }

        @Test
        @DisplayName("Should JSON-serialize structured object data")
        void shouldJsonSerializeStructuredData(Vertx vertx, VertxTestContext testContext) {
            SimpleEventStream source = new SimpleEventStream();
            HttpServerResponse response = stubResponse();
            SseReadStream stream = new SseReadStream(source, vertx, noKeepAlive(), response);

            List<String> emitted = new ArrayList<>();
            stream.handler(buf -> {
                emitted.add(buf.toString());
                testContext.verify(() -> {
                    String wire = emitted.get(0);
                    assertTrue(wire.startsWith("data: {"), "Expected JSON data line, got: " + wire);
                    assertTrue(wire.contains("\"key\""), "Expected 'key' field in JSON");
                    assertTrue(wire.contains("\"value\""), "Expected 'value' field in JSON");
                    assertTrue(wire.endsWith("\n\n"), "Expected trailing blank line");
                });
                testContext.completeNow();
            });

            source.emit(SseEvent.of(new TestPayload("key", "value")));
        }

        @Test
        @DisplayName("Should emit all fields in field-order: id, event, retry, comment, data")
        void shouldEmitAllFieldsInOrder(Vertx vertx, VertxTestContext testContext) {
            SimpleEventStream source = new SimpleEventStream();
            HttpServerResponse response = stubResponse();
            SseReadStream stream = new SseReadStream(source, vertx, noKeepAlive(), response);

            List<String> emitted = new ArrayList<>();
            stream.handler(buf -> {
                emitted.add(buf.toString());
                testContext.verify(() -> {
                    String wire = emitted.get(0);
                    int idPos = wire.indexOf("id: ");
                    int eventPos = wire.indexOf("event: ");
                    int retryPos = wire.indexOf("retry: ");
                    int commentPos = wire.indexOf(": note");
                    int dataPos = wire.indexOf("data: ");
                    assertTrue(idPos < eventPos, "id should come before event");
                    assertTrue(eventPos < retryPos, "event should come before retry");
                    assertTrue(retryPos < commentPos, "retry should come before comment");
                    assertTrue(commentPos < dataPos, "comment should come before data");
                });
                testContext.completeNow();
            });

            source.emit(SseEvent.builder()
                    .id("1")
                    .event("ping")
                    .retryMs(1000L)
                    .comment("note")
                    .data("payload")
                    .build());
        }
    }

    // --- Headers ---

    @Nested
    @DisplayName("Response headers")
    class ResponseHeaders {

        @Test
        @DisplayName("Should write Cache-Control and Connection headers on first handler set")
        void shouldWriteSseHeadersOnFirstHandlerSet(Vertx vertx) {
            SimpleEventStream source = new SimpleEventStream();
            HttpServerResponse response = stubResponse();
            SseReadStream stream = new SseReadStream(source, vertx, noKeepAlive(), response);

            stream.handler(buf -> {});

            verify(response).putHeader("Cache-Control", "no-cache");
            verify(response).putHeader("Connection", "keep-alive");
        }

        @Test
        @DisplayName("Should enable chunked transfer encoding on first handler set")
        void shouldEnableChunkedEncoding(Vertx vertx) {
            SimpleEventStream source = new SimpleEventStream();
            HttpServerResponse response = stubResponse();
            SseReadStream stream = new SseReadStream(source, vertx, noKeepAlive(), response);

            stream.handler(buf -> {});

            // HTTP/1.1 requires either Content-Length or chunked encoding for streaming responses.
            // Without this flag, every response.write(...) throws IllegalStateException.
            verify(response).setChunked(true);
        }

        @Test
        @DisplayName("Should write headers only once even if handler is called multiple times")
        void shouldWriteHeadersOnlyOnce(Vertx vertx) {
            SimpleEventStream source = new SimpleEventStream();
            HttpServerResponse response = stubResponse();
            SseReadStream stream = new SseReadStream(source, vertx, noKeepAlive(), response);

            stream.handler(buf -> {});
            stream.handler(buf -> {}); // second call

            verify(response, times(1)).putHeader("Cache-Control", "no-cache");
            verify(response, times(1)).putHeader("Connection", "keep-alive");
            verify(response, times(1)).setChunked(true);
        }
    }

    // --- Keep-alive ---

    @Nested
    @DisplayName("Keep-alive")
    class KeepAlive {

        @Test
        @DisplayName("Should emit keep-alive comments at configured interval")
        void shouldEmitKeepAliveComments(Vertx vertx, VertxTestContext testContext) {
            SimpleEventStream source = new SimpleEventStream();
            HttpServerResponse response = stubResponse();
            SseConfig config = SseConfig.builder()
                    .keepAliveEnabled(true)
                    .keepAliveIntervalMs(50)
                    .build();
            SseReadStream stream = new SseReadStream(source, vertx, config, response);

            List<String> emitted = new ArrayList<>();
            stream.handler(buf -> {
                emitted.add(buf.toString());
                if (emitted.stream().anyMatch(s -> s.contains(": keep-alive"))) {
                    testContext.completeNow();
                }
            });
        }
    }

    // --- End handler ---

    @Nested
    @DisplayName("End handler")
    class EndHandlerBehavior {

        @Test
        @DisplayName("Should fire end handler when source stream ends")
        void shouldFireEndHandlerOnSourceEnd(Vertx vertx, VertxTestContext testContext) {
            SimpleEventStream source = new SimpleEventStream();
            HttpServerResponse response = stubResponse();
            SseReadStream stream = new SseReadStream(source, vertx, noKeepAlive(), response);

            stream.handler(buf -> {});
            stream.endHandler(v -> testContext.completeNow());

            source.end();
        }
    }

    // --- Helpers ---

    /**
     * Returns an {@link SseConfig} with keep-alive disabled for deterministic tests.
     *
     * @return no-keep-alive config
     */
    private static SseConfig noKeepAlive() {
        return SseConfig.builder().keepAliveEnabled(false).build();
    }

    /**
     * Creates a stub {@link HttpServerResponse} that accepts headers and close-handler registration.
     *
     * @return lenient mock response
     */
    private static HttpServerResponse stubResponse() {
        HttpServerResponse response = mock(HttpServerResponse.class);
        when(response.putHeader(anyString(), anyString())).thenReturn(response);
        when(response.setChunked(anyBoolean())).thenReturn(response);
        when(response.closeHandler(any())).thenReturn(response);
        return response;
    }

    // --- Test doubles ---

    /**
     * Simple in-memory {@link ReadStream}{@code <SseEvent>} for testing. Stores handlers and
     * exposes {@link #emit(SseEvent)} and {@link #end()} for test-driven data injection.
     */
    private static final class SimpleEventStream implements ReadStream<SseEvent> {

        private Handler<SseEvent> dataHandler;
        private Handler<Void> endHandler;
        private Handler<Throwable> exceptionHandler;

        /**
         * Emits a single event to the registered data handler.
         *
         * @param event the event to emit
         */
        void emit(SseEvent event) {
            if (dataHandler != null) {
                dataHandler.handle(event);
            }
        }

        /**
         * Signals end of stream to the registered end handler.
         */
        void end() {
            if (endHandler != null) {
                endHandler.handle(null);
            }
        }

        @Override
        public ReadStream<SseEvent> exceptionHandler(Handler<Throwable> handler) {
            this.exceptionHandler = handler;
            return this;
        }

        @Override
        public ReadStream<SseEvent> handler(Handler<SseEvent> handler) {
            this.dataHandler = handler;
            return this;
        }

        @Override
        public ReadStream<SseEvent> pause() {
            return this;
        }

        @Override
        public ReadStream<SseEvent> resume() {
            return this;
        }

        @Override
        public ReadStream<SseEvent> fetch(long amount) {
            return this;
        }

        @Override
        public ReadStream<SseEvent> endHandler(Handler<Void> endHandler) {
            this.endHandler = endHandler;
            return this;
        }
    }

    /** Simple record used to verify JSON serialization of structured data. */
    record TestPayload(String key, String value) {}
}
