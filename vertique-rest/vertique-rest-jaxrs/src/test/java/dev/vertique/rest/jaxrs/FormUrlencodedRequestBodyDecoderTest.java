// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import dev.vertique.rest.core.request.RequestValue;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FormUrlencodedRequestBodyDecoder}.
 *
 * <p>Verifies that the decoder accepts {@code application/x-www-form-urlencoded} only for
 * POJO target types (not {@link String}, {@link Buffer}, {@code byte[]}, or
 * {@link JsonObject}), and that form attributes are correctly mapped to a POJO via
 * {@link JsonObject#mapTo(Class)}.
 */
class FormUrlencodedRequestBodyDecoderTest {

    private static final String FORM_CONTENT_TYPE = "application/x-www-form-urlencoded";

    private final FormUrlencodedRequestBodyDecoder decoder = new FormUrlencodedRequestBodyDecoder();

    // --- canDecode ---

    @Test
    @DisplayName("Should accept application/x-www-form-urlencoded with POJO target")
    void shouldAcceptFormUrlencodedWithPojoTarget() {
        assertTrue(decoder.canDecode(TestDto.class, FORM_CONTENT_TYPE));
    }

    @Test
    @DisplayName("Should reject application/x-www-form-urlencoded with String target")
    void shouldRejectFormUrlencodedWithStringTarget() {
        assertFalse(decoder.canDecode(String.class, FORM_CONTENT_TYPE));
    }

    @Test
    @DisplayName("Should reject application/json with POJO target")
    void shouldRejectApplicationJsonWithPojoTarget() {
        assertFalse(decoder.canDecode(TestDto.class, "application/json"));
    }

    @Test
    @DisplayName("Should reject null content type with POJO target")
    void shouldRejectNullContentTypeWithPojoTarget() {
        assertFalse(decoder.canDecode(TestDto.class, null));
    }

    // --- decode ---

    @Test
    @DisplayName("Should map form attributes to POJO fields")
    void shouldMapFormAttributesToPojo() {
        RoutingContext ctx = mock(RoutingContext.class);
        var httpRequest = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(httpRequest);

        MultiMap formAttrs = MultiMap.caseInsensitiveMultiMap();
        formAttrs.add("name", "Alice");
        formAttrs.add("email", "alice@example.com");
        when(httpRequest.formAttributes()).thenReturn(formAttrs);

        RequestValue body = RequestValue.of(null);

        Object result = decoder.decode(ctx, body, TestDto.class, null);

        assertInstanceOf(TestDto.class, result);
        TestDto dto = (TestDto) result;
        assertEquals("Alice", dto.name());
        assertEquals("alice@example.com", dto.email());
    }

    @Test
    @DisplayName("Should return null when form attributes are empty")
    void shouldReturnNullForEmptyFormAttributes() {
        RoutingContext ctx = mock(RoutingContext.class);
        var httpRequest = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(httpRequest);

        MultiMap formAttrs = MultiMap.caseInsensitiveMultiMap();
        when(httpRequest.formAttributes()).thenReturn(formAttrs);

        RequestValue body = RequestValue.of(null);

        Object result = decoder.decode(ctx, body, TestDto.class, null);

        assertNull(result);
    }

    // --- helper types ---

    /** Simple DTO record used for form attribute mapping tests. */
    record TestDto(String name, String email) {}
}
