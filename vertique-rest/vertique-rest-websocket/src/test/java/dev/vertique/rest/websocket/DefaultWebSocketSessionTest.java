// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.vertique.context.ContextSnapshot;
import dev.vertique.core.context.ContextHolder;
import io.vertx.core.MultiMap;
import io.vertx.core.http.ServerWebSocket;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultWebSocketSession}, verifying session identity, path/query
 * parameter delegation, attribute mutability, context snapshot/scope nullability and
 * mutability, and {@code isOpen()} delegation to {@link ServerWebSocket#isClosed()}.
 */
@DisplayName("DefaultWebSocketSession")
class DefaultWebSocketSessionTest {

    private ServerWebSocket ws;
    private MultiMap queryParams;
    private MultiMap headers;

    @BeforeEach
    void setUp() {
        ws = mock(ServerWebSocket.class);
        when(ws.path()).thenReturn("/ws/test");
        when(ws.isClosed()).thenReturn(false);

        queryParams = MultiMap.caseInsensitiveMultiMap();
        headers = MultiMap.caseInsensitiveMultiMap();
    }

    private DefaultWebSocketSession session(Map<String, String> pathParams) {
        return new DefaultWebSocketSession(ws, pathParams, queryParams, headers);
    }

    // --- Identity tests ---

    @Nested
    @DisplayName("session identity")
    class Identity {

        @Test
        @DisplayName("id() returns a non-null UUID-format string")
        void idIsNonNullUuid() {
            DefaultWebSocketSession s = session(Map.of());
            assertNotNull(s.id());
            assertFalse(s.id().isBlank());
            // UUID format: 8-4-4-4-12 hex digits
            assertTrue(
                    s.id().matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
                    "Expected UUID format but got: " + s.id());
        }

        @Test
        @DisplayName("two sessions have different IDs")
        void twoSessionsHaveDifferentIds() {
            DefaultWebSocketSession s1 = session(Map.of());
            DefaultWebSocketSession s2 = session(Map.of());
            assertFalse(s1.id().equals(s2.id()), "Two sessions must have unique IDs");
        }
    }

    // --- Path params tests ---

    @Nested
    @DisplayName("pathParams()")
    class PathParams {

        @Test
        @DisplayName("pathParams() returns values from constructor")
        void pathParamsReturnsProvidedValues() {
            Map<String, String> provided = new HashMap<>();
            provided.put("roomId", "abc");
            DefaultWebSocketSession s = new DefaultWebSocketSession(ws, provided, queryParams, headers);
            assertEquals("abc", s.pathParams().get("roomId"));
        }

        @Test
        @DisplayName("pathParams() returns an immutable copy")
        void pathParamsIsImmutable() {
            Map<String, String> provided = new HashMap<>();
            provided.put("key", "value");
            DefaultWebSocketSession s = new DefaultWebSocketSession(ws, provided, queryParams, headers);
            Map<String, String> result = s.pathParams();
            org.junit.jupiter.api.Assertions.assertThrows(
                    UnsupportedOperationException.class, () -> result.put("extra", "x"));
        }

        @Test
        @DisplayName("mutating the original map does not affect session params")
        void mutatingOriginalMapDoesNotAffectSession() {
            Map<String, String> provided = new HashMap<>();
            provided.put("key", "original");
            DefaultWebSocketSession s = new DefaultWebSocketSession(ws, provided, queryParams, headers);
            provided.put("key", "mutated");
            assertEquals("original", s.pathParams().get("key"));
        }
    }

    // --- Context snapshot/scope tests ---

    @Nested
    @DisplayName("contextSnapshot()")
    class ContextSnapshotTests {

        @Test
        @DisplayName("contextSnapshot() returns null before it is set")
        void returnsNullBeforeSet() {
            DefaultWebSocketSession s = session(Map.of());
            assertNull(s.contextSnapshot(), "contextSnapshot must be null before it is set");
        }

        @Test
        @DisplayName("contextSnapshot(snapshot) stores the value and contextSnapshot() returns it")
        void setterAndGetterRoundTrip() {
            DefaultWebSocketSession s = session(Map.of());
            ContextSnapshot snapshot = mock(ContextSnapshot.class);
            s.contextSnapshot(snapshot);
            assertSame(snapshot, s.contextSnapshot(), "contextSnapshot() must return the value passed to the setter");
        }
    }

    @Nested
    @DisplayName("contextScope()")
    class ContextScopeTests {

        @Test
        @DisplayName("contextScope() returns null before it is set")
        void returnsNullBeforeSet() {
            DefaultWebSocketSession s = session(Map.of());
            assertNull(s.contextScope(), "contextScope must be null before it is set");
        }

        @Test
        @DisplayName("contextScope(scope) stores the value and contextScope() returns it")
        void setterAndGetterRoundTrip() {
            DefaultWebSocketSession s = session(Map.of());
            ContextHolder.Scope scope = mock(ContextHolder.Scope.class);
            s.contextScope(scope);
            assertSame(scope, s.contextScope(), "contextScope() must return the value passed to the setter");
        }

        @Test
        @DisplayName("contextScope(null) clears the stored scope")
        void clearScopeWithNull() {
            DefaultWebSocketSession s = session(Map.of());
            ContextHolder.Scope scope = mock(ContextHolder.Scope.class);
            s.contextScope(scope);
            s.contextScope(null);
            assertNull(s.contextScope(), "contextScope() must return null after being cleared");
        }
    }

    // --- Attributes tests ---

    @Nested
    @DisplayName("attributes()")
    class Attributes {

        @Test
        @DisplayName("attributes() map is initially empty")
        void initiallyEmpty() {
            DefaultWebSocketSession s = session(Map.of());
            assertTrue(s.attributes().isEmpty());
        }

        @Test
        @DisplayName("attributes() map is mutable")
        void isMutable() {
            DefaultWebSocketSession s = session(Map.of());
            s.attributes().put("myKey", "myValue");
            assertEquals("myValue", s.attributes().get("myKey"));
        }

        @Test
        @DisplayName("same attributes map reference is returned on every call")
        void sameReferenceReturned() {
            DefaultWebSocketSession s = session(Map.of());
            assertSame(s.attributes(), s.attributes());
        }
    }

    // --- isOpen() delegation tests ---

    @Nested
    @DisplayName("isOpen()")
    class IsOpen {

        @Test
        @DisplayName("isOpen() returns true when ws.isClosed() is false")
        void isOpenWhenNotClosed() {
            when(ws.isClosed()).thenReturn(false);
            DefaultWebSocketSession s = session(Map.of());
            assertTrue(s.isOpen());
        }

        @Test
        @DisplayName("isOpen() returns false when ws.isClosed() is true")
        void isClosedWhenWsIsClosed() {
            when(ws.isClosed()).thenReturn(true);
            DefaultWebSocketSession s = session(Map.of());
            assertFalse(s.isOpen());
        }
    }

    // --- Delegation tests ---

    @Nested
    @DisplayName("path() delegation")
    class PathDelegation {

        @Test
        @DisplayName("path() delegates to ServerWebSocket.path()")
        void pathDelegatesToWs() {
            when(ws.path()).thenReturn("/ws/test/42");
            DefaultWebSocketSession s = session(Map.of());
            assertEquals("/ws/test/42", s.path());
        }
    }

    // --- raw() tests ---

    @Nested
    @DisplayName("raw()")
    class Raw {

        @Test
        @DisplayName("raw() returns the underlying ServerWebSocket")
        void rawReturnsUnderlyingWs() {
            DefaultWebSocketSession s = session(Map.of());
            assertSame(ws, s.raw());
        }
    }
}
