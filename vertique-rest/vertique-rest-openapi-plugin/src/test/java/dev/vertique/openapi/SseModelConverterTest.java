// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.openapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.vertx.core.streams.ReadStream;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SseModelConverter}, verifying that it resolves {@code
 * ReadStream<SseEvent>} return types to a plain string schema, delegates every other type
 * (including a {@code ReadStream} of a different type argument, and a raw {@code ReadStream} with
 * no type argument) unchanged to the next converter in the chain, and terminates gracefully when it
 * is last in an empty chain.
 *
 * <p>{@link SseModelConverter} matches its type argument by comparing the fully-qualified class
 * name {@code dev.vertique.rest.core.sse.SseEvent} as a string literal, so this test package
 * declares a same-named, same-package test fixture ({@link dev.vertique.rest.core.sse.SseEvent}) to
 * exercise the positive match path without adding a compile-time dependency on {@code rest-core}.
 */
class SseModelConverterTest {

    @Test
    @DisplayName("resolve() maps ReadStream<SseEvent> to a string schema describing an SSE event stream")
    void readStreamOfSseEvent_mapsToStringSchemaWithDescription() {
        SseModelConverter converter = new SseModelConverter();
        var readStreamOfSseEvent = Json.mapper()
                .getTypeFactory()
                .constructParametricType(ReadStream.class, dev.vertique.rest.core.sse.SseEvent.class);
        AnnotatedType type = new AnnotatedType().type(readStreamOfSseEvent);
        Iterator<ModelConverter> emptyChain = Collections.emptyIterator();

        Schema<?> result = converter.resolve(type, null, emptyChain);

        assertInstanceOf(StringSchema.class, result);
        assertEquals("string", result.getType());
        assertEquals("SSE event stream", result.getDescription());
    }

    @Test
    @DisplayName("resolve() passes ReadStream<T> with a non-SseEvent type argument through to the next converter")
    void readStreamOfOtherType_delegatesToChain() {
        SseModelConverter converter = new SseModelConverter();
        Schema<?> marker = new Schema<>();
        CapturingModelConverter next = new CapturingModelConverter(marker);
        Iterator<ModelConverter> chain = List.<ModelConverter>of(next).iterator();

        var readStreamOfString = Json.mapper().getTypeFactory().constructParametricType(ReadStream.class, String.class);
        AnnotatedType type = new AnnotatedType().type(readStreamOfString);

        Schema<?> result = converter.resolve(type, null, chain);

        assertSame(marker, result);
        assertNotNull(next.capturedType);
        assertSame(type, next.capturedType);
    }

    @Test
    @DisplayName("resolve() passes a raw ReadStream with no type argument through to the next converter")
    void rawReadStream_delegatesToChain() {
        SseModelConverter converter = new SseModelConverter();
        Schema<?> marker = new Schema<>();
        CapturingModelConverter next = new CapturingModelConverter(marker);
        Iterator<ModelConverter> chain = List.<ModelConverter>of(next).iterator();

        AnnotatedType type = new AnnotatedType().type(ReadStream.class);

        Schema<?> result = converter.resolve(type, null, chain);

        assertSame(marker, result);
        assertNotNull(next.capturedType);
        assertSame(type, next.capturedType);
    }

    @Test
    @DisplayName("resolve() passes non-ReadStream types through to the next converter unchanged")
    void nonReadStreamType_delegatesToChain() {
        SseModelConverter converter = new SseModelConverter();
        Schema<?> marker = new Schema<>();
        CapturingModelConverter next = new CapturingModelConverter(marker);
        Iterator<ModelConverter> chain = List.<ModelConverter>of(next).iterator();

        AnnotatedType type = new AnnotatedType().type(String.class);

        Schema<?> result = converter.resolve(type, null, chain);

        assertSame(marker, result);
        assertNotNull(next.capturedType);
        assertSame(type, next.capturedType);
    }

    @Test
    @DisplayName("resolve() returns null instead of throwing when last in an empty converter chain for a non-SSE type")
    void lastInChain_nonSseType_returnsNull() {
        SseModelConverter converter = new SseModelConverter();
        AnnotatedType type = new AnnotatedType().type(String.class);
        Iterator<ModelConverter> emptyChain = Collections.emptyIterator();

        Schema<?> result = converter.resolve(type, null, emptyChain);

        assertNull(result);
    }
}
