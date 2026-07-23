// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.vertique.rest.core.pagination.CursorCodec;
import dev.vertique.rest.core.pagination.CursorPageRequest;
import dev.vertique.rest.core.pagination.InvalidCursorException;
import dev.vertique.rest.core.pagination.PlainCursorCodec;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CursorPageRequest} — cursor decoding, page size extraction,
 * and Jackson {@code convertValue} compatibility (used by {@code @BeanParam} extraction).
 */
class CursorPageRequestTest {

    // --- decodeCursor(codec) ---

    @Test
    @DisplayName("Should return empty when cursor param is null")
    void shouldReturnEmptyWhenCursorIsNull() {
        CursorPageRequest req = new CursorPageRequest(null, null);
        assertTrue(req.decodeCursor(PlainCursorCodec.INSTANCE).isEmpty());
    }

    @Test
    @DisplayName("Should return empty when cursor param is blank")
    void shouldReturnEmptyWhenCursorIsBlank() {
        CursorPageRequest req = new CursorPageRequest("   ", null);
        assertTrue(req.decodeCursor(PlainCursorCodec.INSTANCE).isEmpty());
    }

    @Test
    @DisplayName("Should return decoded cursor when cursor param is present")
    void shouldReturnDecodedCursorWhenPresent() {
        CursorPageRequest req = new CursorPageRequest("raw-token", null);
        Optional<String> result = req.decodeCursor(PlainCursorCodec.INSTANCE);

        assertTrue(result.isPresent());
        assertEquals("raw-token", result.get());
    }

    @Test
    @DisplayName("Should apply codec when decoding cursor")
    void shouldApplyCodecWhenDecoding() {
        CursorPageRequest req = new CursorPageRequest("ENCODED", null);
        CursorCodec codec = new CursorCodec() {
            @Override
            public String encode(String raw) {
                return raw.toUpperCase();
            }

            @Override
            public String decode(String token) {
                return token.toLowerCase();
            }
        };

        Optional<String> result = req.decodeCursor(codec);

        assertTrue(result.isPresent());
        assertEquals("encoded", result.get());
    }

    @Test
    @DisplayName("Should propagate InvalidCursorException from codec")
    void shouldPropagateInvalidCursorException() {
        CursorPageRequest req = new CursorPageRequest("bad-token", null);
        CursorCodec failingCodec = new CursorCodec() {
            @Override
            public String encode(String raw) {
                return raw;
            }

            @Override
            public String decode(String token) {
                throw new InvalidCursorException("tampered");
            }
        };

        assertThrows(InvalidCursorException.class, () -> req.decodeCursor(failingCodec));
    }

    // --- no-arg decodeCursor() — uses PlainCursorCodec ---

    @Test
    @DisplayName("No-arg decodeCursor() should return empty when no cursor")
    void noArgDecodeCursorShouldReturnEmptyWhenNoCursor() {
        CursorPageRequest req = new CursorPageRequest(null, null);
        assertTrue(req.decodeCursor().isEmpty());
    }

    @Test
    @DisplayName("No-arg decodeCursor() should pass cursor through unchanged")
    void noArgDecodeCursorShouldPassThroughUnchanged() {
        CursorPageRequest req = new CursorPageRequest("my-cursor", null);
        Optional<String> result = req.decodeCursor();

        assertTrue(result.isPresent());
        assertEquals("my-cursor", result.get());
    }

    // --- pageSize(defaultValue) ---

    @Test
    @DisplayName("Should return default when pageSize param is not set")
    void shouldReturnDefaultWhenPageSizeNotSet() {
        CursorPageRequest req = new CursorPageRequest(null, null);
        assertEquals(20, req.pageSize(20));
    }

    @Test
    @DisplayName("Should return page size when param is set")
    void shouldReturnPageSizeWhenSet() {
        CursorPageRequest req = new CursorPageRequest(null, 25);
        assertEquals(25, req.pageSize(20));
    }

    @Test
    @DisplayName("Should clamp zero pageSize to 1 (single-arg)")
    void shouldClampZeroPageSizeToOneSingleArg() {
        CursorPageRequest req = new CursorPageRequest(null, 0);
        assertEquals(1, req.pageSize(20));
    }

    @Test
    @DisplayName("Should clamp negative pageSize to 1 (single-arg)")
    void shouldClampNegativePageSizeToOneSingleArg() {
        CursorPageRequest req = new CursorPageRequest(null, -5);
        assertEquals(1, req.pageSize(20));
    }

    @Test
    @DisplayName("Should clamp zero default to 1 when pageSize is null")
    void shouldClampZeroDefaultToOneSingleArg() {
        CursorPageRequest req = new CursorPageRequest(null, null);
        assertEquals(1, req.pageSize(0));
    }

    // --- pageSize(defaultValue, maxValue) ---

    @Test
    @DisplayName("Should return default when pageSize param is null (with max)")
    void shouldReturnDefaultWhenPageSizeNullWithMax() {
        CursorPageRequest req = new CursorPageRequest(null, null);
        assertEquals(20, req.pageSize(20, 100));
    }

    @Test
    @DisplayName("Should return pageSize when within range")
    void shouldReturnPageSizeWhenWithinRange() {
        CursorPageRequest req = new CursorPageRequest(null, 50);
        assertEquals(50, req.pageSize(20, 100));
    }

    @Test
    @DisplayName("Should clamp pageSize to maxValue when above max")
    void shouldClampPageSizeToMaxWhenAbove() {
        CursorPageRequest req = new CursorPageRequest(null, 200);
        assertEquals(100, req.pageSize(20, 100));
    }

    @Test
    @DisplayName("Should clamp zero pageSize to 1 (with max)")
    void shouldClampZeroPageSizeToOne() {
        CursorPageRequest req = new CursorPageRequest(null, 0);
        assertEquals(1, req.pageSize(20, 100));
    }

    @Test
    @DisplayName("Should clamp negative pageSize to 1 (with max)")
    void shouldClampNegativePageSizeToOne() {
        CursorPageRequest req = new CursorPageRequest(null, -5);
        assertEquals(1, req.pageSize(20, 100));
    }

    // --- Jackson convertValue compatibility (@BeanParam extraction path) ---

    @Test
    @DisplayName("Should populate via Jackson convertValue (mimics @BeanParam extraction)")
    void shouldPopulateViaJacksonConvertValue() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> values = Map.of("cursor", "my-token", "pageSize", 42);

        CursorPageRequest req = mapper.convertValue(values, CursorPageRequest.class);

        assertEquals(Optional.of("my-token"), req.decodeCursor());
        assertEquals(42, req.pageSize(20));
    }

    @Test
    @DisplayName("Should handle partial map via Jackson convertValue (cursor only)")
    void shouldHandlePartialMapViConvertValue() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> values = Map.of("cursor", "token-only");

        CursorPageRequest req = mapper.convertValue(values, CursorPageRequest.class);

        assertEquals(Optional.of("token-only"), req.decodeCursor());
        assertEquals(20, req.pageSize(20));
    }

    @Test
    @DisplayName("Should handle empty map via Jackson convertValue (first page, no pageSize)")
    void shouldHandleEmptyMapViaConvertValue() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> values = Map.of();

        CursorPageRequest req = mapper.convertValue(values, CursorPageRequest.class);

        assertTrue(req.decodeCursor().isEmpty());
        assertEquals(20, req.pageSize(20));
    }

    @Test
    @DisplayName("Should ignore unknown properties via Jackson convertValue")
    void shouldIgnoreUnknownPropertiesViaConvertValue() {
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> values = Map.of("cursor", "tok", "pageSize", 10, "unknownField", "ignored");

        assertDoesNotThrow(() -> mapper.convertValue(values, CursorPageRequest.class));
    }
}
