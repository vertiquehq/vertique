// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.config.SseConfig;
import dev.vertique.rest.core.response.SerializedBody;
import dev.vertique.rest.core.response.StreamingBody;
import dev.vertique.rest.core.sse.SseEvent;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.streams.ReadStream;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SseBodyEncoder}.
 *
 * <p>Verifies content-type-based selection, priority ordering, and correct wrapping of
 * {@link ReadStream}{@code <SseEvent>} into a {@link StreamingBody}.
 */
class SseBodyEncoderTest {

    private final SseBodyEncoder encoder =
            new SseBodyEncoder(SseConfig.builder().build());

    // --- priority ---

    @Nested
    @DisplayName("priority()")
    class Priority {

        @Test
        @DisplayName("Should report priority 999")
        void shouldReportPriority999() {
            assertEquals(999, encoder.priority());
        }
    }

    // --- canEncode ---

    @Nested
    @DisplayName("canEncode()")
    class CanEncode {

        @Test
        @DisplayName("Should accept text/event-stream content type")
        void shouldAcceptTextEventStream() {
            assertTrue(encoder.canEncode(Object.class, "text/event-stream"));
        }

        @Test
        @DisplayName("Should accept text/event-stream with charset parameter")
        void shouldAcceptTextEventStreamWithCharset() {
            assertTrue(encoder.canEncode(Object.class, "text/event-stream; charset=utf-8"));
        }

        @Test
        @DisplayName("Should reject application/json content type")
        void shouldRejectApplicationJson() {
            assertFalse(encoder.canEncode(Object.class, "application/json"));
        }

        @Test
        @DisplayName("Should reject null content type")
        void shouldRejectNullContentType() {
            assertFalse(encoder.canEncode(Object.class, null));
        }

        @Test
        @DisplayName("Should accept Object.class entity type for MediaTypeValidator startup check")
        void shouldAcceptObjectClassEntityType() {
            // MediaTypeValidator calls canEncode(Object.class, mediaType) at startup
            // SseBodyEncoder must return true regardless of entity type when content type matches
            assertTrue(encoder.canEncode(Object.class, "text/event-stream"));
        }

        @Test
        @DisplayName("Should accept ReadStream entity type with text/event-stream")
        void shouldAcceptReadStreamEntityType() {
            assertTrue(encoder.canEncode(SseEventReadStream.class, "text/event-stream"));
        }
    }

    // --- encode ---

    @Nested
    @DisplayName("encode()")
    class Encode {

        @Test
        @DisplayName("Should wrap ReadStream<SseEvent> in StreamingBody with SSE content type")
        void shouldWrapReadStreamInStreamingBody() {
            RoutingContext ctx = mock(RoutingContext.class);
            Vertx vertx = mock(Vertx.class);
            HttpServerResponse response = mock(HttpServerResponse.class);
            when(ctx.vertx()).thenReturn(vertx);
            when(ctx.response()).thenReturn(response);
            when(response.closeHandler(any())).thenReturn(response);

            SseEventReadStream stream = new SseEventReadStream();
            SerializedBody body = encoder.encode(ctx, null, stream);

            assertInstanceOf(StreamingBody.class, body);
            StreamingBody streaming = (StreamingBody) body;
            assertEquals(SseBodyEncoder.SSE_CONTENT_TYPE, streaming.contentType());
            assertNull(streaming.contentLength());
        }

        @Test
        @DisplayName("Should throw IllegalArgumentException for non-ReadStream entity")
        void shouldThrowForNonReadStreamEntity() {
            RoutingContext ctx = mock(RoutingContext.class);

            assertThrows(IllegalArgumentException.class, () -> encoder.encode(ctx, null, "not a stream"));
        }
    }

    // --- Test doubles ---

    /** Minimal {@link ReadStream}{@code <SseEvent>} stub for tests. */
    private static final class SseEventReadStream implements ReadStream<SseEvent> {

        @Override
        public ReadStream<SseEvent> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public ReadStream<SseEvent> handler(Handler<SseEvent> handler) {
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
            return this;
        }
    }
}
