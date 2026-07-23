// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.config.JsonConfigPaths.LookupResult;
import dev.vertique.core.config.JsonConfigPaths.LookupStatus;
import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonConfigPaths}.
 *
 * <p>Verifies the two lookup modes:
 * <ul>
 *   <li>{@code navigateObject} — subtree traversal that returns an empty {@link JsonObject} when a
 *       segment key is absent (parity with the old {@code getJsonObject(key, default)} chain) and
 *       throws {@link ConfigurationException} with path context when the segment key is present
 *       but bound to anything other than a {@link JsonObject} (scalar, array, or explicit JSON
 *       null). Blank/null path segments are skipped.
 *   <li>{@code resolve} — strict dotted-path resolution distinguishing {@code MISSING},
 *       {@code INVALID_SHAPE}, and {@code PRESENT} including explicit-null leaves
 * </ul>
 */
class JsonConfigPathsTest {

    // --- navigateObject ---

    @Nested
    @DisplayName("navigateObject()")
    class NavigateObject {

        @Test
        @DisplayName("no segments returns the root unchanged")
        void noSegmentsReturnsRoot() {
            JsonObject root = new JsonObject().put("a", 1);
            assertSame(root, JsonConfigPaths.navigateObject(root));
        }

        @Test
        @DisplayName("single-level segment present returns that subtree")
        void singleLevelPresent() {
            JsonObject child = new JsonObject().put("k", "v");
            JsonObject root = new JsonObject().put("a", child);
            assertSame(child, JsonConfigPaths.navigateObject(root, "a"));
        }

        @Test
        @DisplayName("multi-level segments present returns the deepest subtree")
        void multiLevelPresent() {
            JsonObject leaf = new JsonObject().put("port", 8080);
            JsonObject root = new JsonObject().put("a", new JsonObject().put("b", leaf));
            assertSame(leaf, JsonConfigPaths.navigateObject(root, "a", "b"));
        }

        @Test
        @DisplayName("missing intermediate segment returns an empty, independent JsonObject")
        void missingIntermediateReturnsEmpty() {
            JsonObject root = new JsonObject().put("a", new JsonObject());
            JsonObject result = JsonConfigPaths.navigateObject(root, "a", "missing", "leaf");
            assertTrue(result.isEmpty());
            // Mutating the returned object must not corrupt the source tree.
            result.put("scratch", "x");
            assertNull(root.getJsonObject("a").getJsonObject("missing"));
        }

        @Test
        @DisplayName("missing leaf segment returns an empty JsonObject")
        void missingLeafReturnsEmpty() {
            JsonObject root = new JsonObject().put("a", new JsonObject());
            JsonObject result = JsonConfigPaths.navigateObject(root, "a", "missing");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("null root returns an empty JsonObject")
        void nullRootReturnsEmpty() {
            JsonObject result = JsonConfigPaths.navigateObject(null, "a");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("blank and null segments are skipped (matches services-config service-type idiom)")
        void blankSegmentsSkipped() {
            JsonObject leaf = new JsonObject().put("port", 8080);
            JsonObject root = new JsonObject().put("a", new JsonObject().put("b", leaf));
            // Empty type segment between "a" and "b" must be ignored.
            assertSame(leaf, JsonConfigPaths.navigateObject(root, "a", "", "b"));
            assertSame(leaf, JsonConfigPaths.navigateObject(root, "a", null, "b"));
            // Whitespace-only segment is treated as blank as well.
            assertSame(leaf, JsonConfigPaths.navigateObject(root, "a", "   ", "b"));
        }

        @Test
        @DisplayName("non-object intermediate value throws ConfigurationException with path context")
        void nonObjectIntermediateThrows() {
            JsonObject root = new JsonObject().put("a", "not-an-object");
            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> JsonConfigPaths.navigateObject(root, "a", "b"));
            assertTrue(
                    ex.getMessage().contains("'a'") && ex.getMessage().contains("JSON object"),
                    "Expected path-bearing 'must be a JSON object' message, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("non-object terminal value throws ConfigurationException")
        void nonObjectTerminalThrows() {
            JsonObject root = new JsonObject().put("a", new JsonObject().put("b", 42));
            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> JsonConfigPaths.navigateObject(root, "a", "b"));
            assertTrue(
                    ex.getMessage().contains("'a.b'"),
                    "Expected dotted-path message including 'a.b', got: " + ex.getMessage());
        }

        @Test
        @DisplayName("explicit-null intermediate throws ConfigurationException (old chain would NPE on next deref)")
        void explicitNullIntermediateThrows() {
            // Vert.x getJsonObject(key, def) returns def only when !containsKey; for {"a": null}
            // it returns null, and the next dereference (which the migrated callers always do)
            // NPEs. The strict path-bearing exception is the typed equivalent of that fail-fast.
            JsonObject root = new JsonObject().putNull("a");
            ConfigurationException ex =
                    assertThrows(ConfigurationException.class, () -> JsonConfigPaths.navigateObject(root, "a", "b"));
            assertTrue(
                    ex.getMessage().contains("'a'") && ex.getMessage().contains("got null"),
                    "Expected 'must be a JSON object, got null' message with segment, got: " + ex.getMessage());
        }
    }

    // --- resolve ---

    @Nested
    @DisplayName("resolve()")
    class Resolve {

        @Test
        @DisplayName("single-segment leaf present returns PRESENT with the scalar value")
        void singleSegmentScalarPresent() {
            JsonObject root = new JsonObject().put("port", 8080);
            LookupResult result = JsonConfigPaths.resolve(root, "port");
            assertEquals(LookupStatus.PRESENT, result.status());
            assertEquals(8080, result.value());
            assertEquals("port", result.path());
            assertNull(result.failingSegment());
        }

        @Test
        @DisplayName("multi-segment scalar leaf returns PRESENT with the scalar value")
        void multiSegmentScalarPresent() {
            JsonObject root = new JsonObject()
                    .put("sasl", new JsonObject().put("jaas", new JsonObject().put("config", "required;")));
            LookupResult result = JsonConfigPaths.resolve(root, "sasl.jaas.config");
            assertEquals(LookupStatus.PRESENT, result.status());
            assertEquals("required;", result.value());
        }

        @Test
        @DisplayName("present leaf may be a JsonObject")
        void leafJsonObjectPresent() {
            JsonObject sub = new JsonObject().put("k", "v");
            JsonObject root = new JsonObject().put("a", new JsonObject().put("b", sub));
            LookupResult result = JsonConfigPaths.resolve(root, "a.b");
            assertEquals(LookupStatus.PRESENT, result.status());
            assertInstanceOf(JsonObject.class, result.value());
            assertEquals(sub, result.value());
        }

        @Test
        @DisplayName("present leaf may be a JsonArray")
        void leafJsonArrayPresent() {
            JsonArray arr = new JsonArray().add("one").add("two");
            JsonObject root = new JsonObject().put("a", new JsonObject().put("b", arr));
            LookupResult result = JsonConfigPaths.resolve(root, "a.b");
            assertEquals(LookupStatus.PRESENT, result.status());
            assertInstanceOf(JsonArray.class, result.value());
            assertEquals(arr, result.value());
        }

        @Test
        @DisplayName("missing intermediate returns MISSING with the failing segment")
        void missingIntermediate() {
            JsonObject root = new JsonObject().put("a", new JsonObject());
            LookupResult result = JsonConfigPaths.resolve(root, "a.b.c");
            assertEquals(LookupStatus.MISSING, result.status());
            assertNull(result.value());
            assertEquals("a.b.c", result.path());
            assertEquals("b", result.failingSegment());
        }

        @Test
        @DisplayName("missing leaf returns MISSING with the leaf as failing segment")
        void missingLeaf() {
            JsonObject root = new JsonObject().put("a", new JsonObject().put("b", new JsonObject()));
            LookupResult result = JsonConfigPaths.resolve(root, "a.b.c");
            assertEquals(LookupStatus.MISSING, result.status());
            assertEquals("c", result.failingSegment());
        }

        @Test
        @DisplayName("scalar at intermediate position returns INVALID_SHAPE with that segment")
        void invalidShapeAtIntermediate() {
            JsonObject root = new JsonObject().put("a", new JsonObject().put("b", "scalar"));
            LookupResult result = JsonConfigPaths.resolve(root, "a.b.c");
            assertEquals(LookupStatus.INVALID_SHAPE, result.status());
            assertEquals("b", result.failingSegment());
        }

        @Test
        @DisplayName("explicit-null intermediate node returns INVALID_SHAPE — distinct from MISSING")
        void invalidShapeAtNullIntermediate() {
            // FR-CG011-009 requires distinguishing absent intermediate (MISSING → matchIfMissing)
            // from present-but-malformed intermediate (INVALID_SHAPE → throw). An explicit-null
            // intermediate is malformed: traversal cannot continue into a non-object node.
            JsonObject root = new JsonObject().putNull("feature");
            LookupResult result = JsonConfigPaths.resolve(root, "feature.enabled");
            assertEquals(LookupStatus.INVALID_SHAPE, result.status());
            assertEquals("feature", result.failingSegment());
        }

        @Test
        @DisplayName("null root returns MISSING with first segment of the path")
        void nullRoot() {
            LookupResult result = JsonConfigPaths.resolve(null, "a.b");
            assertEquals(LookupStatus.MISSING, result.status());
            assertEquals("a", result.failingSegment());
        }

        @Test
        @DisplayName("null root with single-segment path reports the whole path as failing")
        void nullRootSingleSegment() {
            LookupResult result = JsonConfigPaths.resolve(null, "leaf");
            assertEquals(LookupStatus.MISSING, result.status());
            assertEquals("leaf", result.failingSegment());
        }

        @Test
        @DisplayName("explicit-null leaf returns PRESENT with value null (KafkaConfigHelper contract)")
        void explicitNullLeafIsPresent() {
            JsonObject root = new JsonObject().putNull("port");
            LookupResult result = JsonConfigPaths.resolve(root, "port");
            assertEquals(LookupStatus.PRESENT, result.status());
            assertNull(result.value());
            assertNull(result.failingSegment());
        }

        @Test
        @DisplayName("LookupResult instances are independent records")
        void resultsAreIndependent() {
            JsonObject root = new JsonObject().put("a", "x");
            LookupResult first = JsonConfigPaths.resolve(root, "a");
            LookupResult second = JsonConfigPaths.resolve(root, "a");
            assertEquals(first, second);
            assertNotSame(first, second);
        }

        @Test
        @DisplayName("null path throws IllegalArgumentException")
        void nullPathThrows() {
            JsonObject root = new JsonObject();
            assertThrows(IllegalArgumentException.class, () -> JsonConfigPaths.resolve(root, null));
        }

        @Test
        @DisplayName("empty path throws IllegalArgumentException")
        void emptyPathThrows() {
            JsonObject root = new JsonObject();
            assertThrows(IllegalArgumentException.class, () -> JsonConfigPaths.resolve(root, ""));
        }

        @Test
        @DisplayName("trailing dot throws — would silently normalize to single segment otherwise")
        void trailingDotThrows() {
            JsonObject root = new JsonObject().put("mode", "a");
            IllegalArgumentException ex =
                    assertThrows(IllegalArgumentException.class, () -> JsonConfigPaths.resolve(root, "mode."));
            assertTrue(ex.getMessage().contains("'mode.'"), "Expected path in message, got: " + ex.getMessage());
        }

        @Test
        @DisplayName("leading dot throws")
        void leadingDotThrows() {
            JsonObject root = new JsonObject().put("a", new JsonObject().put("b", 1));
            assertThrows(IllegalArgumentException.class, () -> JsonConfigPaths.resolve(root, ".a"));
        }

        @Test
        @DisplayName("consecutive dots throw")
        void consecutiveDotsThrow() {
            JsonObject root = new JsonObject().put("a", new JsonObject().put("b", 1));
            assertThrows(IllegalArgumentException.class, () -> JsonConfigPaths.resolve(root, "a..b"));
        }

        @Test
        @DisplayName("path of dots only throws — would AIOBE on parts[-1] otherwise")
        void dotsOnlyThrows() {
            JsonObject root = new JsonObject();
            assertThrows(IllegalArgumentException.class, () -> JsonConfigPaths.resolve(root, "."));
            assertThrows(IllegalArgumentException.class, () -> JsonConfigPaths.resolve(root, ".."));
        }
    }
}
