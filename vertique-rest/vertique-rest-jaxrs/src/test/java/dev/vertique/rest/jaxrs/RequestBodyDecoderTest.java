// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.jaxrs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.rest.core.request.RequestBodyDecoder;
import dev.vertique.rest.core.request.RequestValue;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.web.RoutingContext;
import java.lang.reflect.Type;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Slice-3 migration tests for the {@link RequestBodyDecoder} SPI and the four built-in decoders,
 * verifying that {@code decode(...)} now accepts a transport-neutral {@link RequestValue} body
 * (FR-024) instead of {@code io.vertx.openapi.validation.RequestParameter}.
 *
 * <p>Each decoder test wraps a value with {@link RequestValue#of(Object)} and asserts that the
 * decoder reads it through the {@link RequestValue} accessors with the same semantics it previously
 * had against {@code RequestParameter}. The final parity test exercises the numeric / buffer /
 * array / boolean accessors behaviourally (FR-024 "behavioral, not just compiles").
 */
class RequestBodyDecoderTest {

    /** Simple POJO record used for JSON mapping. */
    record MyDto(String name) {}

    // --- Test 1: SPI signature accepts RequestValue ---

    @Test
    @DisplayName("RequestBodyDecoder.decode signature accepts a RequestValue body (compile-time)")
    void requestBodyDecoderDecodeSignatureAcceptsRequestValue() {
        // A lambda whose body parameter is typed RequestValue must satisfy the generic decode
        // overload — there is no RequestParameter in scope here.
        RequestBodyDecoder decoder = new RequestBodyDecoder() {
            @Override
            public boolean canDecode(Class<?> targetType, String contentType) {
                return true;
            }

            @Override
            public Object decode(RoutingContext ctx, RequestValue rv, Class<?> type, Type generic) {
                return rv == null ? null : rv.get();
            }
        };

        RequestValue rv = RequestValue.of("ok");
        assertEquals("ok", decoder.decode(mock(RoutingContext.class), rv, String.class, null));
    }

    // --- Test 2 & 3: JsonRequestBodyDecoder ---

    @Test
    @DisplayName("JsonRequestBodyDecoder decodes a JsonObject from a RequestValue body into a DTO")
    void jsonRequestBodyDecoderDecodesJsonObjectFromRequestValue() {
        JsonRequestBodyDecoder decoder = new JsonRequestBodyDecoder();
        RequestValue rv = RequestValue.of(new JsonObject().put("name", "Alice"));

        Object result = decoder.decode(mock(RoutingContext.class), rv, MyDto.class, null);

        assertInstanceOf(MyDto.class, result);
        assertEquals("Alice", ((MyDto) result).name());
    }

    @Test
    @DisplayName("JsonRequestBodyDecoder returns null for a null-valued RequestValue body")
    void jsonRequestBodyDecoderHandlesNullBody() {
        JsonRequestBodyDecoder decoder = new JsonRequestBodyDecoder();
        RequestValue rv = RequestValue.of(null);

        Object result = decoder.decode(mock(RoutingContext.class), rv, MyDto.class, null);

        assertNull(result);
    }

    // --- Test 4: TextRequestBodyDecoder ---

    @Test
    @DisplayName("TextRequestBodyDecoder decodes a String from a RequestValue body")
    void textRequestBodyDecoderDecodesStringFromRequestValue() {
        TextRequestBodyDecoder decoder = new TextRequestBodyDecoder();
        RequestValue rv = RequestValue.of("hello");

        Object result = decoder.decode(mock(RoutingContext.class), rv, String.class, null);

        assertEquals("hello", result);
    }

    // --- Test 5: BinaryRequestBodyDecoder ---

    @Test
    @DisplayName("BinaryRequestBodyDecoder decodes a Buffer from a RequestValue body")
    void binaryRequestBodyDecoderDecodesBufferFromRequestValue() {
        BinaryRequestBodyDecoder decoder = new BinaryRequestBodyDecoder();
        Buffer buffer = Buffer.buffer(new byte[] {0x01, 0x02});
        RequestValue rv = RequestValue.of(buffer);

        Object result = decoder.decode(mock(RoutingContext.class), rv, Buffer.class, null);

        assertSame(buffer, result);
    }

    @Test
    @DisplayName("BinaryRequestBodyDecoder decodes a byte[] from a RequestValue Buffer body")
    void binaryRequestBodyDecoderDecodesByteArrayFromRequestValue() {
        BinaryRequestBodyDecoder decoder = new BinaryRequestBodyDecoder();
        byte[] bytes = new byte[] {0x01, 0x02};
        RequestValue rv = RequestValue.of(Buffer.buffer(bytes));

        Object result = decoder.decode(mock(RoutingContext.class), rv, byte[].class, null);

        assertInstanceOf(byte[].class, result);
        assertArrayEquals(bytes, (byte[]) result);
    }

    // --- Test 6: FormUrlencodedRequestBodyDecoder ---

    @Test
    @DisplayName("FormUrlencodedRequestBodyDecoder decodes form-data into a DTO (body arg is a RequestValue)")
    void formRequestBodyDecoderDecodesFormFromRequestValue() {
        FormUrlencodedRequestBodyDecoder decoder = new FormUrlencodedRequestBodyDecoder();

        RoutingContext ctx = mock(RoutingContext.class);
        io.vertx.core.http.HttpServerRequest request = mock(io.vertx.core.http.HttpServerRequest.class);
        io.vertx.core.MultiMap formAttributes = io.vertx.core.MultiMap.caseInsensitiveMultiMap();
        formAttributes.add("name", "Bob");
        when(ctx.request()).thenReturn(request);
        when(request.formAttributes()).thenReturn(formAttributes);

        // The body RequestValue is unused by this decoder but must compile as a RequestValue.
        RequestValue rv = RequestValue.of(null);
        Object result = decoder.decode(ctx, rv, MyDto.class, null);

        assertInstanceOf(MyDto.class, result);
        assertEquals("Bob", ((MyDto) result).name());
    }

    // --- Test 7: RequestValue full-parity accessors (behavioral guard) ---

    @Test
    @DisplayName("RequestValue full-parity accessors return values for Double, Buffer, JsonArray, Boolean")
    void requestValueFullParityAccessors() {
        RequestValue doubleValue = RequestValue.of(3.14d);
        assertEquals(3.14d, doubleValue.getDouble());
        assertEquals(3.14d, doubleValue.getDouble(0.0d));
        assertTrue(doubleValue.isNumber());

        Buffer buffer = Buffer.buffer(new byte[] {0x01});
        RequestValue bufferValue = RequestValue.of(buffer);
        assertSame(buffer, bufferValue.getBuffer());
        assertTrue(bufferValue.isBuffer());

        JsonArray array = new JsonArray().add("a").add("b");
        RequestValue arrayValue = RequestValue.of(array);
        assertSame(array, arrayValue.getJsonArray());
        assertTrue(arrayValue.isJsonArray());

        RequestValue booleanValue = RequestValue.of(Boolean.TRUE);
        assertEquals(Boolean.TRUE, booleanValue.getBoolean());
        assertEquals(Boolean.TRUE, booleanValue.getBoolean(false));
        assertTrue(booleanValue.isBoolean());
    }
}
