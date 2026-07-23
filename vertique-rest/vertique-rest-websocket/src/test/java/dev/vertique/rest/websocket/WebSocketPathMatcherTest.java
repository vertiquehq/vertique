// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link WebSocketPathMatcher}, verifying path template compilation,
 * parameter extraction, and non-matching behaviour.
 */
@DisplayName("WebSocketPathMatcher")
class WebSocketPathMatcherTest {

    // --- Static path tests ---

    @Nested
    @DisplayName("static path (no parameters)")
    class StaticPath {

        private final WebSocketPathMatcher matcher = new WebSocketPathMatcher("/ws/chat");

        @Test
        @DisplayName("matches exact path and returns empty params")
        void matchesExactPath() {
            Map<String, String> params = matcher.extractParams("/ws/chat");
            assertTrue(params.isEmpty(), "Expected empty param map for static path");
        }

        @Test
        @DisplayName("non-matching path returns empty map")
        void nonMatchingPathReturnsEmpty() {
            Map<String, String> params = matcher.extractParams("/ws/other");
            assertTrue(params.isEmpty(), "Expected empty map for non-matching path");
        }

        @Test
        @DisplayName("paramNames returns empty list for static path")
        void paramNamesEmptyForStaticPath() {
            assertTrue(matcher.paramNames().isEmpty());
        }
    }

    // --- Single parameter tests ---

    @Nested
    @DisplayName("single path parameter")
    class SingleParam {

        private final WebSocketPathMatcher matcher = new WebSocketPathMatcher("/ws/chat/{roomId}");

        @Test
        @DisplayName("extracts roomId from matching path")
        void extractsSingleParam() {
            Map<String, String> params = matcher.extractParams("/ws/chat/room1");
            assertEquals(1, params.size());
            assertEquals("room1", params.get("roomId"));
        }

        @Test
        @DisplayName("non-matching path returns empty map")
        void nonMatchingReturnsEmpty() {
            Map<String, String> params = matcher.extractParams("/ws/other/room1");
            assertTrue(params.isEmpty());
        }

        @Test
        @DisplayName("paramNames returns list with single name")
        void paramNamesContainsSingleEntry() {
            assertEquals(List.of("roomId"), matcher.paramNames());
        }
    }

    // --- Multiple parameter tests ---

    @Nested
    @DisplayName("multiple path parameters")
    class MultipleParams {

        private final WebSocketPathMatcher matcher = new WebSocketPathMatcher("/ws/{type}/{id}");

        @Test
        @DisplayName("extracts all params from matching path")
        void extractsMultipleParams() {
            Map<String, String> params = matcher.extractParams("/ws/chat/42");
            assertEquals(2, params.size());
            assertEquals("chat", params.get("type"));
            assertEquals("42", params.get("id"));
        }

        @Test
        @DisplayName("paramNames returns names in declaration order")
        void paramNamesInDeclarationOrder() {
            assertIterableEquals(List.of("type", "id"), matcher.paramNames());
        }

        @Test
        @DisplayName("non-matching path returns empty map")
        void nonMatchingReturnsEmpty() {
            Map<String, String> params = matcher.extractParams("/ws/chat");
            assertTrue(params.isEmpty());
        }
    }

    // --- Special characters tests ---

    @Nested
    @DisplayName("special characters in param values")
    class SpecialCharacters {

        private final WebSocketPathMatcher matcher = new WebSocketPathMatcher("/ws/{channel}");

        @Test
        @DisplayName("matches URL-encoded safe chars in param value")
        void matchesUrlEncodedSafeChars() {
            Map<String, String> params = matcher.extractParams("/ws/my-channel_1");
            assertEquals("my-channel_1", params.get("channel"));
        }

        @Test
        @DisplayName("matches alphanumeric with dots in param value")
        void matchesAlphanumericWithDots() {
            Map<String, String> params = matcher.extractParams("/ws/v1.0.beta");
            assertEquals("v1.0.beta", params.get("channel"));
        }

        @Test
        @DisplayName("param value containing slash does not match (single segment)")
        void slashInParamDoesNotMatch() {
            Map<String, String> params = matcher.extractParams("/ws/a/b");
            assertTrue(params.isEmpty(), "Slash in param value should not match single-segment placeholder");
        }
    }

    // --- Immutability tests ---

    @Nested
    @DisplayName("returned map immutability")
    class Immutability {

        @Test
        @DisplayName("extractParams result is unmodifiable")
        void extractParamsResultIsUnmodifiable() {
            WebSocketPathMatcher matcher = new WebSocketPathMatcher("/ws/{id}");
            Map<String, String> params = matcher.extractParams("/ws/abc");
            assertFalse(params.isEmpty());
            org.junit.jupiter.api.Assertions.assertThrows(
                    UnsupportedOperationException.class, () -> params.put("key", "val"));
        }

        @Test
        @DisplayName("paramNames result is unmodifiable")
        void paramNamesResultIsUnmodifiable() {
            WebSocketPathMatcher matcher = new WebSocketPathMatcher("/ws/{id}");
            List<String> names = matcher.paramNames();
            org.junit.jupiter.api.Assertions.assertThrows(
                    UnsupportedOperationException.class, () -> names.add("extra"));
        }
    }
}
