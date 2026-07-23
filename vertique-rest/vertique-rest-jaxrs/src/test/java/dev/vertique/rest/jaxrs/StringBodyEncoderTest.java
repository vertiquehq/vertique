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
 * Unit tests for {@link StringBodyEncoder}.
 *
 * <p>Verifies that {@link String} entities are accepted and wrapped in a
 * {@link BufferedBody} with {@code text/plain} as the default content type
 * and a null content length.
 */
class StringBodyEncoderTest {

    private final StringBodyEncoder encoder = new StringBodyEncoder();

    // --- canEncode ---

    @Test
    @DisplayName("Should accept String entity type")
    void shouldAcceptStringType() {
        assertTrue(encoder.canEncode(String.class, null));
        assertTrue(encoder.canEncode(String.class, "text/plain"));
    }

    @Test
    @DisplayName("Should reject non-String entity types")
    void shouldRejectNonStringType() {
        assertFalse(encoder.canEncode(byte[].class, null));
        assertFalse(encoder.canEncode(Buffer.class, null));
    }

    // --- encode ---

    @Test
    @DisplayName("Should produce BufferedBody with text/plain and null content length")
    void shouldProduceBufferedBodyWithTextPlain() {
        String str = "hello world";
        SerializedBody body = encoder.encode(null, null, str);

        assertInstanceOf(BufferedBody.class, body);
        BufferedBody buffered = (BufferedBody) body;
        assertEquals(Buffer.buffer(str), buffered.buffer());
        assertEquals("text/plain", buffered.contentType());
        assertNull(buffered.contentLength());
    }
}
