// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.request.RequestValue;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TextRequestBodyDecoder}.
 *
 * <p>Verifies that the decoder only accepts {@code text/*} content types when the target
 * type is {@link String}, and that the raw body string is returned from {@code decode}.
 */
class TextRequestBodyDecoderTest {

    private final TextRequestBodyDecoder decoder = new TextRequestBodyDecoder();

    // --- canDecode ---

    @Test
    @DisplayName("Should accept text/plain with String target")
    void shouldAcceptTextPlainWithStringTarget() {
        assertTrue(decoder.canDecode(String.class, "text/plain"));
    }

    @Test
    @DisplayName("Should accept text/html with String target")
    void shouldAcceptTextHtmlWithStringTarget() {
        assertTrue(decoder.canDecode(String.class, "text/html"));
    }

    @Test
    @DisplayName("Should reject text/plain with non-String target")
    void shouldRejectTextPlainWithNonStringTarget() {
        assertFalse(decoder.canDecode(Integer.class, "text/plain"));
    }

    @Test
    @DisplayName("Should reject application/json with String target")
    void shouldRejectApplicationJsonWithStringTarget() {
        assertFalse(decoder.canDecode(String.class, "application/json"));
    }

    @Test
    @DisplayName("Should reject null content type with String target")
    void shouldRejectNullContentTypeWithStringTarget() {
        assertFalse(decoder.canDecode(String.class, null));
    }

    // --- decode ---

    @Test
    @DisplayName("Should return raw body string from RequestValue")
    void shouldReturnRawBodyString() {
        RoutingContext ctx = mock(RoutingContext.class);
        RequestValue body = RequestValue.of("hello text");

        Object result = decoder.decode(ctx, body, String.class, null);

        assertEquals("hello text", result);
    }
}
