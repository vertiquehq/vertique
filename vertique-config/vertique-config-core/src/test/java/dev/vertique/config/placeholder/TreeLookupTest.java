// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.placeholder;

import static org.junit.jupiter.api.Assertions.*;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TreeLookup}.
 *
 * <p>Verifies the two probe rules:
 * <ol>
 *   <li><em>Flat-key-first</em> — {@code tree.getValue(key)} is tried first; the result is used
 *       (even for keys that contain dots) unless the key is absent or its value is JSON null.</li>
 *   <li><em>Dot-path walk</em> — if the flat probe misses, the key is split on {@code '.'} and
 *       the path is descended through nested {@link JsonObject} entries only (no array indexing,
 *       no non-object intermediates in V1).</li>
 * </ol>
 *
 * <p>Also verifies {@link TreeLookup#stringify(Object)}: scalars via {@code String.valueOf},
 * {@link JsonObject}/{@link JsonArray} via {@code encode()}.
 */
class TreeLookupTest {

    // --- find: flat exact hit ---

    @Nested
    @DisplayName("Flat exact-key probe")
    class FlatExactHit {

        @Test
        @DisplayName("flat key present with String value returns that value")
        void flatKeyString() {
            JsonObject tree = new JsonObject().put("host", "localhost");
            Optional<Object> result = TreeLookup.find(tree, "host");
            assertTrue(result.isPresent());
            assertEquals("localhost", result.get());
        }

        @Test
        @DisplayName("flat key absent (no dot) returns empty")
        void flatKeyAbsent() {
            JsonObject tree = new JsonObject().put("host", "localhost");
            Optional<Object> result = TreeLookup.find(tree, "missing");
            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("key without dots not found at all returns empty")
        void keyWithoutDotsAbsent() {
            JsonObject tree = new JsonObject();
            Optional<Object> result = TreeLookup.find(tree, "nope");
            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("flat key with dot present shadows nested path (flat-first rule)")
        void flatShadowsNested() {
            // tree: {"a.b": "flat", "a": {"b": "nested"}}
            JsonObject tree = new JsonObject().put("a.b", "flat").put("a", new JsonObject().put("b", "nested"));
            Optional<Object> result = TreeLookup.find(tree, "a.b");
            assertTrue(result.isPresent());
            assertEquals("flat", result.get(), "flat key must shadow nested path");
        }

        @Test
        @DisplayName("find(\"a\") returns the nested JsonObject when no flat key 'a' with primitive")
        void flatKeyReturnsNestedObject() {
            JsonObject nested = new JsonObject().put("b", "value");
            JsonObject tree = new JsonObject().put("a", nested);
            Optional<Object> result = TreeLookup.find(tree, "a");
            assertTrue(result.isPresent());
            assertSame(nested, result.get());
        }
    }

    // --- find: dot-path walk ---

    @Nested
    @DisplayName("Dot-path walk (flat key absent)")
    class DotPathWalk {

        @Test
        @DisplayName("two-level path 'a.b' descends into nested JsonObject")
        void twoLevelPath() {
            JsonObject tree = new JsonObject().put("a", new JsonObject().put("b", "hello"));
            Optional<Object> result = TreeLookup.find(tree, "a.b");
            assertTrue(result.isPresent());
            assertEquals("hello", result.get());
        }

        @Test
        @DisplayName("three-level path 'a.b.c' descends two levels")
        void threeLevelPath() {
            JsonObject tree = new JsonObject().put("a", new JsonObject().put("b", new JsonObject().put("c", "deep")));
            Optional<Object> result = TreeLookup.find(tree, "a.b.c");
            assertTrue(result.isPresent());
            assertEquals("deep", result.get());
        }

        @Test
        @DisplayName("four-level path 'a.b.c.d' descends three levels")
        void fourLevelPath() {
            JsonObject tree = new JsonObject()
                    .put("a", new JsonObject().put("b", new JsonObject().put("c", new JsonObject().put("d", 42))));
            Optional<Object> result = TreeLookup.find(tree, "a.b.c.d");
            assertTrue(result.isPresent());
            assertEquals(42, result.get());
        }

        @Test
        @DisplayName("intermediate non-object (String in the middle) returns empty")
        void intermediateNonObject() {
            // "a" is a String, so descending "a.b" is impossible
            JsonObject tree = new JsonObject().put("a", "not-an-object");
            Optional<Object> result = TreeLookup.find(tree, "a.b");
            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("intermediate JsonArray is not descended (no array indexing in V1) returns empty")
        void intermediateJsonArray() {
            JsonObject tree = new JsonObject().put("a", new JsonArray().add("item"));
            Optional<Object> result = TreeLookup.find(tree, "a.b");
            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("intermediate path segment missing returns empty")
        void intermediateSegmentMissing() {
            JsonObject tree = new JsonObject().put("a", new JsonObject().put("x", "value"));
            Optional<Object> result = TreeLookup.find(tree, "a.b.c");
            assertFalse(result.isPresent());
        }

        @Test
        @DisplayName("flat 'a.b' absent but nested 'a' has String 'b' blocks descent — 'a.b.c' not found")
        void flatAbsentNestedIntermediateIsString() {
            // tree: {"a": {"b": "nested"}} — "a.b" is not flat so walk: a → JsonObject,
            // b → "nested" (String), then we need to descend past "b" for "a.b.c" → impossible
            JsonObject tree = new JsonObject().put("a", new JsonObject().put("b", "nested"));
            Optional<Object> result = TreeLookup.find(tree, "a.b.c");
            assertFalse(result.isPresent());
        }
    }

    // --- find: present-null = not-found ---

    @Nested
    @DisplayName("Present-null = not-found semantics")
    class PresentNullNotFound {

        @Test
        @DisplayName("flat key present with JSON null value returns empty (null-means-absent)")
        void flatNullValueIsNotFound() {
            JsonObject tree = new JsonObject().putNull("key");
            Optional<Object> result = TreeLookup.find(tree, "key");
            assertFalse(result.isPresent(), "a JSON null at a flat key must be treated as absent");
        }

        @Test
        @DisplayName("nested key with JSON null value returns empty (null-means-absent)")
        void nestedNullValueIsNotFound() {
            JsonObject tree = new JsonObject().put("a", new JsonObject().putNull("b"));
            Optional<Object> result = TreeLookup.find(tree, "a.b");
            assertFalse(result.isPresent(), "a JSON null at a nested key must be treated as absent");
        }
    }

    // --- find: typed return values ---

    @Nested
    @DisplayName("Typed return values (type preservation)")
    class TypedReturnValues {

        @Test
        @DisplayName("Integer value is returned as Integer")
        void integerPreserved() {
            JsonObject tree = new JsonObject().put("port", 8080);
            Optional<Object> result = TreeLookup.find(tree, "port");
            assertTrue(result.isPresent());
            assertInstanceOf(Integer.class, result.get());
            assertEquals(8080, result.get());
        }

        @Test
        @DisplayName("Long value is returned as Long")
        void longPreserved() {
            JsonObject tree = new JsonObject().put("timeout", Long.MAX_VALUE);
            Optional<Object> result = TreeLookup.find(tree, "timeout");
            assertTrue(result.isPresent());
            assertInstanceOf(Long.class, result.get());
            assertEquals(Long.MAX_VALUE, result.get());
        }

        @Test
        @DisplayName("Double value is returned as Double")
        void doublePreserved() {
            JsonObject tree = new JsonObject().put("ratio", 3.14);
            Optional<Object> result = TreeLookup.find(tree, "ratio");
            assertTrue(result.isPresent());
            assertInstanceOf(Double.class, result.get());
            assertEquals(3.14, result.get());
        }

        @Test
        @DisplayName("Boolean value is returned as Boolean")
        void booleanPreserved() {
            JsonObject tree = new JsonObject().put("enabled", true);
            Optional<Object> result = TreeLookup.find(tree, "enabled");
            assertTrue(result.isPresent());
            assertInstanceOf(Boolean.class, result.get());
            assertEquals(Boolean.TRUE, result.get());
        }

        @Test
        @DisplayName("JsonObject value is returned as the same JsonObject instance")
        void jsonObjectIdentity() {
            JsonObject inner = new JsonObject().put("x", 1);
            JsonObject tree = new JsonObject().put("nested", inner);
            Optional<Object> result = TreeLookup.find(tree, "nested");
            assertTrue(result.isPresent());
            assertInstanceOf(JsonObject.class, result.get());
        }

        @Test
        @DisplayName("JsonArray value is returned as JsonArray")
        void jsonArrayIdentity() {
            JsonArray arr = new JsonArray().add("a").add("b");
            JsonObject tree = new JsonObject().put("list", arr);
            Optional<Object> result = TreeLookup.find(tree, "list");
            assertTrue(result.isPresent());
            assertInstanceOf(JsonArray.class, result.get());
        }
    }

    // --- stringify ---

    @Nested
    @DisplayName("stringify matrix")
    class StringifyMatrix {

        @Test
        @DisplayName("String passthrough — no wrapping or quoting")
        void stringPassthrough() {
            assertEquals("hello world", TreeLookup.stringify("hello world"));
        }

        @Test
        @DisplayName("empty String passthrough")
        void emptyStringPassthrough() {
            assertEquals("", TreeLookup.stringify(""));
        }

        @Test
        @DisplayName("Integer via String.valueOf")
        void integerStringified() {
            assertEquals("42", TreeLookup.stringify(42));
        }

        @Test
        @DisplayName("Long via String.valueOf")
        void longStringified() {
            assertEquals("9223372036854775807", TreeLookup.stringify(Long.MAX_VALUE));
        }

        @Test
        @DisplayName("Double via String.valueOf")
        void doubleStringified() {
            assertEquals(String.valueOf(3.14), TreeLookup.stringify(3.14));
        }

        @Test
        @DisplayName("Boolean true via String.valueOf")
        void booleanTrueStringified() {
            assertEquals("true", TreeLookup.stringify(Boolean.TRUE));
        }

        @Test
        @DisplayName("Boolean false via String.valueOf")
        void booleanFalseStringified() {
            assertEquals("false", TreeLookup.stringify(Boolean.FALSE));
        }

        @Test
        @DisplayName("JsonObject via encode() — compact JSON")
        void jsonObjectEncoded() {
            JsonObject obj = new JsonObject().put("a", 1).put("b", "two");
            String result = TreeLookup.stringify(obj);
            assertEquals(obj.encode(), result);
        }

        @Test
        @DisplayName("JsonArray via encode() — compact JSON")
        void jsonArrayEncoded() {
            JsonArray arr = new JsonArray().add(1).add("two").add(true);
            String result = TreeLookup.stringify(arr);
            assertEquals(arr.encode(), result);
        }

        @Test
        @DisplayName("null input produces \"null\" (defensive; callers should not pass null)")
        void nullInput() {
            assertEquals("null", TreeLookup.stringify(null));
        }
    }
}
