// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.rest.core.response.BufferedBody;
import dev.vertique.rest.core.response.SerializedBody;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BufferBodyEncoder}.
 *
 * <p>Verifies that {@link Buffer} entities are accepted and wrapped in a
 * {@link BufferedBody} with no default content type.
 */
class BufferBodyEncoderTest {

    private final BufferBodyEncoder encoder = new BufferBodyEncoder();

    // --- canEncode ---

    @Test
    @DisplayName("Should accept Buffer entity type")
    void shouldAcceptBufferType() {
        assertTrue(encoder.canEncode(Buffer.class, null));
        assertTrue(encoder.canEncode(Buffer.class, "application/json"));
    }

    @Test
    @DisplayName("Should reject non-Buffer entity types")
    void shouldRejectNonBufferType() {
        assertFalse(encoder.canEncode(String.class, null));
        assertFalse(encoder.canEncode(byte[].class, null));
    }

    // --- encode ---

    @Test
    @DisplayName("Should produce BufferedBody with no default content type")
    void shouldProduceBufferedBody() {
        Buffer buf = Buffer.buffer("hello");
        SerializedBody body = encoder.encode(null, null, buf);

        assertInstanceOf(BufferedBody.class, body);
        BufferedBody buffered = (BufferedBody) body;
        assertEquals(buf, buffered.buffer());
        assertNull(buffered.contentType());
        assertEquals((long) buf.length(), buffered.contentLength());
    }
}
