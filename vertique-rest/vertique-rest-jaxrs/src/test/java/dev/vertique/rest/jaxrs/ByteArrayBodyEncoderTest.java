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
 * Unit tests for {@link ByteArrayBodyEncoder}.
 *
 * <p>Verifies that {@code byte[]} entities are accepted and wrapped in a
 * {@link BufferedBody} with {@code application/octet-stream} as the default content type.
 */
class ByteArrayBodyEncoderTest {

    private final ByteArrayBodyEncoder encoder = new ByteArrayBodyEncoder();

    // --- canEncode ---

    @Test
    @DisplayName("Should accept byte[] entity type")
    void shouldAcceptByteArrayType() {
        assertTrue(encoder.canEncode(byte[].class, null));
        assertTrue(encoder.canEncode(byte[].class, "application/octet-stream"));
    }

    @Test
    @DisplayName("Should reject non-byte[] entity types")
    void shouldRejectNonByteArrayType() {
        assertFalse(encoder.canEncode(String.class, null));
        assertFalse(encoder.canEncode(Buffer.class, null));
    }

    // --- encode ---

    @Test
    @DisplayName("Should produce BufferedBody with application/octet-stream and correct length")
    void shouldProduceBufferedBodyWithOctetStream() {
        byte[] data = new byte[] {1, 2, 3, 4, 5};
        SerializedBody body = encoder.encode(null, null, data);

        assertInstanceOf(BufferedBody.class, body);
        BufferedBody buffered = (BufferedBody) body;
        assertEquals(Buffer.buffer(data), buffered.buffer());
        assertEquals("application/octet-stream", buffered.contentType());
        assertEquals((long) data.length, buffered.contentLength());
    }
}
