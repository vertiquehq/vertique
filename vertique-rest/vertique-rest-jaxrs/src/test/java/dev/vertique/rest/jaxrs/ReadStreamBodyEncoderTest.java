// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.rest.core.response.SerializedBody;
import dev.vertique.rest.core.response.StreamingBody;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ReadStreamBodyEncoder}.
 *
 * <p>Verifies that only {@code ReadStream<Buffer>} streams are accepted and wrapped
 * in a {@link StreamingBody} with no default content type or content length.
 */
class ReadStreamBodyEncoderTest {

    private final ReadStreamBodyEncoder encoder = new ReadStreamBodyEncoder();

    // --- canEncode ---

    @Test
    @DisplayName("Should accept ReadStream<Buffer> subtype")
    void shouldAcceptBufferReadStreamType() {
        assertTrue(encoder.canEncode(BufferReadStream.class, null));
        assertTrue(encoder.canEncode(BufferReadStream.class, "application/octet-stream"));
    }

    @Test
    @DisplayName("Should reject non-ReadStream entity types")
    void shouldRejectNonReadStreamType() {
        assertFalse(encoder.canEncode(String.class, null));
        assertFalse(encoder.canEncode(Buffer.class, null));
    }

    @Test
    @DisplayName("Should reject raw ReadStream type when element type is unresolved")
    void shouldRejectRawReadStreamType() {
        assertFalse(encoder.canEncode(ReadStream.class, null));
    }

    @Test
    @DisplayName("Should reject ReadStream<String> subtype")
    void shouldRejectNonBufferReadStreamSubtype() {
        assertFalse(encoder.canEncode(StringReadStream.class, null));
    }

    // --- encode ---

    @Test
    @DisplayName("Should produce StreamingBody with null content type and null content length")
    void shouldProduceStreamingBodyWithNullTypeAndLength() {
        BufferReadStream stream = new BufferReadStream();
        SerializedBody body = encoder.encode(null, null, stream);

        assertInstanceOf(StreamingBody.class, body);
        StreamingBody streaming = (StreamingBody) body;
        assertSame(stream, streaming.stream());
        assertNull(streaming.contentType());
        assertNull(streaming.contentLength());
    }

    @Test
    @DisplayName("Should throw when encoding non-Buffer stream directly")
    void shouldThrowForNonBufferStream() {
        assertThrows(IllegalArgumentException.class, () -> encoder.encode(null, null, new StringReadStream()));
    }

    private static final class BufferReadStream implements ReadStream<Buffer> {
        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            return this;
        }
    }

    private static final class StringReadStream implements ReadStream<String> {
        @Override
        public ReadStream<String> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public ReadStream<String> handler(Handler<String> handler) {
            return this;
        }

        @Override
        public ReadStream<String> pause() {
            return this;
        }

        @Override
        public ReadStream<String> resume() {
            return this;
        }

        @Override
        public ReadStream<String> fetch(long amount) {
            return this;
        }

        @Override
        public ReadStream<String> endHandler(Handler<Void> endHandler) {
            return this;
        }
    }
}
