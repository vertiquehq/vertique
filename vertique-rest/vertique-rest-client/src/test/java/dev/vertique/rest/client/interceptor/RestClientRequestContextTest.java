// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.client.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RestClientRequestContext}.
 *
 * <p>Verifies:
 *
 * <ul>
 *   <li>User-attribute preservation: all {@code with*} copy mutators preserve {@code attributes}
 *       unchanged (except the attribute-specific ones).
 *   <li>Defensive copying: the compact constructor copies the attributes map so mutating the source
 *       map after construction does not affect the context, and the stored map is unmodifiable.
 *   <li>Constructor parity: the 5-arg and 7-arg constructors both initialise {@code attributes}
 *       correctly (5-arg defaults {@code attributes} to an empty map).
 * </ul>
 */
class RestClientRequestContextTest {

    // --- Shared fixtures ---

    private static final String USER_KEY = "user.key";
    private static final Object USER_VALUE = "user-value";

    /**
     * Builds a minimal 5-arg context for tests that do not need headers/attributes.
     *
     * @param httpMethod the HTTP verb
     * @return a fresh request context
     */
    private static RestClientRequestContext minimal(String httpMethod) {
        return new RestClientRequestContext(httpMethod, "https://example.com/v1", null, "client", "method");
    }

    // === User-attribute preservation across non-attribute mutators ===

    @Nested
    @DisplayName("with* mutators preserve user attributes")
    class UserAttributePreservation {

        @Test
        @DisplayName("withBody preserves user attributes")
        void withBodyPreservesAttributes() {
            RestClientRequestContext ctx = minimal("GET").withAttribute(USER_KEY, USER_VALUE);
            RestClientRequestContext next = ctx.withBody(Buffer.buffer("hello"));
            assertEquals(USER_VALUE, next.attribute(USER_KEY));
        }

        @Test
        @DisplayName("withHeaders preserves user attributes")
        void withHeadersPreservesAttributes() {
            RestClientRequestContext ctx = minimal("GET").withAttribute(USER_KEY, USER_VALUE);
            RestClientRequestContext next =
                    ctx.withHeaders(MultiMap.caseInsensitiveMultiMap().add("X-Foo", "bar"));
            assertEquals(USER_VALUE, next.attribute(USER_KEY));
        }

        @Test
        @DisplayName("withRequestUri preserves user attributes")
        void withRequestUriPreservesAttributes() {
            RestClientRequestContext ctx = minimal("GET").withAttribute(USER_KEY, USER_VALUE);
            RestClientRequestContext next = ctx.withRequestUri("https://other.example.com/v2");
            assertEquals(USER_VALUE, next.attribute(USER_KEY));
        }

        @Test
        @DisplayName("withHeader preserves user attributes")
        void withHeaderPreservesAttributes() {
            RestClientRequestContext ctx = minimal("GET").withAttribute(USER_KEY, USER_VALUE);
            RestClientRequestContext next = ctx.withHeader("Authorization", "Bearer token");
            assertEquals(USER_VALUE, next.attribute(USER_KEY));
        }
    }

    // === User-attribute copy-on-write ===

    @Nested
    @DisplayName("withAttribute and withAttributes produce correct copies")
    class AttributeCopyOnWrite {

        @Test
        @DisplayName("withAttribute merges into the existing attribute map")
        void withAttributeMerges() {
            RestClientRequestContext ctx =
                    minimal("GET").withAttribute("first", "v1").withAttribute("second", "v2");

            assertEquals("v1", ctx.attribute("first"));
            assertEquals("v2", ctx.attribute("second"));
        }

        @Test
        @DisplayName("withAttributes replaces the entire attribute map")
        void withAttributesReplacesMap() {
            RestClientRequestContext ctx = minimal("GET").withAttribute("old-key", "old-val");
            RestClientRequestContext next = ctx.withAttributes(Map.of(USER_KEY, USER_VALUE));

            assertEquals(USER_VALUE, next.attribute(USER_KEY));
            assertNull(next.attribute("old-key"), "old key must be gone after withAttributes");
        }

        @Test
        @DisplayName("attribute() returns null for absent keys")
        void attributeReturnsNullForAbsentKey() {
            RestClientRequestContext ctx = minimal("GET");
            assertNull(ctx.attribute("absent-key"));
        }
    }

    // === Defensive copy in compact constructor ===

    @Nested
    @DisplayName("compact constructor defensively copies the attributes map")
    class DefensiveCopy {

        @Test
        @DisplayName("mutating the source attributes map after construction does not affect the context")
        void sourceAttributesMutationDoesNotAffectContext() {
            Map<String, Object> mutableAttrs = new HashMap<>();
            mutableAttrs.put("original", "v1");
            RestClientRequestContext ctx = new RestClientRequestContext(
                    "GET",
                    "https://example.com",
                    MultiMap.caseInsensitiveMultiMap(),
                    null,
                    "client",
                    "method",
                    mutableAttrs);

            mutableAttrs.put("added-after", "should-not-appear");
            assertNull(ctx.attribute("added-after"), "mutating the source map must not affect the stored attributes");
        }

        @Test
        @DisplayName("stored attributes map is unmodifiable")
        void storedAttributesIsUnmodifiable() {
            RestClientRequestContext ctx = minimal("GET").withAttribute(USER_KEY, USER_VALUE);
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> ctx.attributes().put("extra", "should-fail"),
                    "attributes() must return an unmodifiable map");
        }

        @Test
        @DisplayName("compact constructor does not store the original map reference for attributes")
        void attributesIsDefensivelyCopied() {
            Map<String, Object> source = new HashMap<>(Map.of(USER_KEY, USER_VALUE));
            RestClientRequestContext ctx = new RestClientRequestContext(
                    "GET", "https://example.com", MultiMap.caseInsensitiveMultiMap(), null, "client", "method", source);
            assertNotSame(source, ctx.attributes(), "attributes must be a defensive copy, not the original reference");
        }
    }

    // === Constructor parity ===

    @Nested
    @DisplayName("all constructors initialise attributes correctly")
    class ConstructorParity {

        @Test
        @DisplayName("5-arg constructor: attributes is empty")
        void fiveArgConstructorAttributesEmpty() {
            RestClientRequestContext ctx = new RestClientRequestContext("POST", "/path", null, "client", "method");
            assertEquals(Map.of(), ctx.attributes(), "5-arg: attributes must be empty");
        }

        @Test
        @DisplayName("7-arg constructor: attributes is populated with the provided value")
        void sevenArgConstructorAttributesSet() {
            RestClientRequestContext ctx = new RestClientRequestContext(
                    "GET",
                    "/path",
                    MultiMap.caseInsensitiveMultiMap(),
                    null,
                    "client",
                    "method",
                    Map.of(USER_KEY, USER_VALUE));

            assertEquals(USER_VALUE, ctx.attribute(USER_KEY), "7-arg: user attribute must be set");
        }

        @Test
        @DisplayName("5-arg constructor: null body is preserved as null")
        void fiveArgNullBodyPreserved() {
            RestClientRequestContext ctx = new RestClientRequestContext("GET", "/path", null, "client", "method");
            assertNull(ctx.body(), "5-arg: null body must remain null");
        }
    }
}
