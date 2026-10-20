// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.core.json.JsonProfileId;
import dev.vertique.core.json.VertiqueJson;
import dev.vertique.rest.core.request.RequestValue;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import io.vertx.core.json.jackson.VertxModule;
import io.vertx.ext.web.RoutingContext;
import org.junit.jupiter.api.AfterEach;
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

    /** Restores the raw process codec after a test that installed a mapper. */
    @AfterEach
    void restoreRawProcessCodec() {
        VertiqueJson.resetForTests();
    }

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

    @Test
    @DisplayName("Should accept an upper-case form content type under a Turkish default locale")
    void shouldAcceptUpperCaseFormUrlencodedUnderTurkishLocale() {
        java.util.Locale previous = java.util.Locale.getDefault();
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("tr"));
        try {
            assertTrue(decoder.canDecode(TestDto.class, "APPLICATION/X-WWW-FORM-URLENCODED"));
        } finally {
            java.util.Locale.setDefault(previous);
        }
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

    @Test
    @DisplayName("Should record the mapper that bound the form body, not the one read after binding")
    void shouldRecordTheMapperThatBoundTheBody() {
        // Given a process mapper a, and a DTO whose construction swaps the process mapper for b
        ObjectMapper a = processMapper();
        ObjectMapper b = processMapper();
        VertiqueJson.install(JsonProfileId.SYSTEM, a);
        SwappingDto.swapTo = b;
        RoutingContext ctx = mock(RoutingContext.class);
        var httpRequest = mock(io.vertx.core.http.HttpServerRequest.class);
        when(ctx.request()).thenReturn(httpRequest);
        MultiMap formAttrs = MultiMap.caseInsensitiveMultiMap();
        formAttrs.add("name", "Alice");
        when(httpRequest.formAttributes()).thenReturn(formAttrs);

        // When the body is decoded
        Object result = decoder.decode(ctx, RequestValue.of(null), SwappingDto.class, null);

        // Then the body was bound by a, and the process mapper is now b
        assertInstanceOf(SwappingDto.class, result);
        assertSame(b, VertiqueJson.mapper(), "the swap must have happened during the binding");
        // And the mark names a, the mapper that did the binding, never the later b
        org.mockito.ArgumentCaptor<Object> mark = org.mockito.ArgumentCaptor.forClass(Object.class);
        verify(ctx).put(eq(BodyBindingMark.KEY), mark.capture());
        BodyBindingMark.Binding binding = (BodyBindingMark.Binding) mark.getValue();
        assertEquals(BodyBindingMark.Kind.FORM, binding.kind());
        assertSame(a, binding.mapper(), "the mark must hold the mapper that bound the body");
    }

    private static ObjectMapper processMapper() {
        return new ObjectMapper().registerModule(new VertxModule());
    }

    // --- helper types ---

    /** A DTO whose construction replaces the process mapper, as a concurrent reconfiguration would. */
    record SwappingDto(String name) {
        static volatile ObjectMapper swapTo;

        SwappingDto {
            VertiqueJson.install(JsonProfileId.SYSTEM, swapTo);
        }
    }

    /** Simple DTO record used for form attribute mapping tests. */
    record TestDto(String name, String email) {}
}
