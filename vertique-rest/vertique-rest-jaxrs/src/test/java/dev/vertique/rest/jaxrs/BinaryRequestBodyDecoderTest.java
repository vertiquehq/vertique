// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.request.RequestValue;
import io.vertx.core.buffer.Buffer;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link BinaryRequestBodyDecoder}.
 *
 * <p>Verifies that the decoder accepts {@code application/octet-stream} only for
 * {@link Buffer} and {@code byte[]} target types, rejects other content types or
 * target types, and correctly adapts the raw body to the requested target type.
 */
class BinaryRequestBodyDecoderTest {

    private final BinaryRequestBodyDecoder decoder = new BinaryRequestBodyDecoder();

    // --- canDecode ---

    @Test
    @DisplayName("Should accept application/octet-stream with Buffer target")
    void shouldAcceptOctetStreamWithBufferTarget() {
        assertTrue(decoder.canDecode(Buffer.class, "application/octet-stream"));
    }

    @Test
    @DisplayName("Should accept application/octet-stream with byte[] target")
    void shouldAcceptOctetStreamWithByteArrayTarget() {
        assertTrue(decoder.canDecode(byte[].class, "application/octet-stream"));
    }

    @Test
    @DisplayName("Should reject application/octet-stream with String target")
    void shouldRejectOctetStreamWithStringTarget() {
        assertFalse(decoder.canDecode(String.class, "application/octet-stream"));
    }

    @Test
    @DisplayName("Should reject application/json with Buffer target")
    void shouldRejectApplicationJsonWithBufferTarget() {
        assertFalse(decoder.canDecode(Buffer.class, "application/json"));
    }

    @Test
    @DisplayName("Should reject null content type with Buffer target")
    void shouldRejectNullContentTypeWithBufferTarget() {
        assertFalse(decoder.canDecode(Buffer.class, null));
    }

    // --- decode ---

    @Test
    @DisplayName("Should return Buffer directly when target type is Buffer")
    void shouldReturnBufferForBufferTarget() {
        RoutingContext ctx = mock(RoutingContext.class);
        Buffer buf = Buffer.buffer(new byte[] {1, 2, 3});
        RequestValue body = RequestValue.of(buf);

        Object result = decoder.decode(ctx, body, Buffer.class, null);

        assertSame(buf, result);
    }

    @Test
    @DisplayName("Should return byte[] from Buffer when target type is byte[]")
    void shouldReturnByteArrayForByteArrayTarget() {
        RoutingContext ctx = mock(RoutingContext.class);
        byte[] bytes = {4, 5, 6};
        Buffer buf = Buffer.buffer(bytes);
        RequestValue body = RequestValue.of(buf);

        Object result = decoder.decode(ctx, body, byte[].class, null);

        assertInstanceOf(byte[].class, result);
        assertArrayEquals(bytes, (byte[]) result);
    }
}
