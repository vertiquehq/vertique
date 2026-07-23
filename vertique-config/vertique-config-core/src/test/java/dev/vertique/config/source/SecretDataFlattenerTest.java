// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.source;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link SecretDataFlattener}.
 *
 * <p>Verifies flattening semantics: flat values, nested maps, null-skipping, non-string scalar
 * stringification, prefix prepending, insertion-order preservation, and collision policy.
 */
class SecretDataFlattenerTest {

    // --- Basic flat map ---

    @Nested
    @DisplayName("flat map (no nesting)")
    class FlatMap {

        @Test
        @DisplayName("string values are returned as-is")
        void stringValuesPreserved() {
            Map<String, Object> data = Map.of("host", "localhost", "port", "5432");
            Map<String, String> result = SecretDataFlattener.flatten(data, "");

            assertEquals("localhost", result.get("host"));
            assertEquals("5432", result.get("port"));
        }

        @Test
        @DisplayName("returns non-null map for empty input")
        void emptyInputReturnsNonNullMap() {
            Map<String, String> result = SecretDataFlattener.flatten(Map.of(), "");
            assertNotNull(result);
            assertTrue(result.isEmpty());
        }
    }

    // --- Prefix ---

    @Nested
    @DisplayName("prefix prepending")
    class PrefixPrepending {

        @Test
        @DisplayName("prefix is prepended to top-level keys")
        void prefixPrepended() {
            Map<String, Object> data = Map.of("host", "localhost");
            Map<String, String> result = SecretDataFlattener.flatten(data, "db.");

            assertEquals("localhost", result.get("db.host"));
            assertTrue(!result.containsKey("host"), "unprefixed key must not appear");
        }

        @Test
        @DisplayName("empty prefix produces key without separator")
        void emptyPrefixNoSeparator() {
            Map<String, Object> data = Map.of("key", "value");
            Map<String, String> result = SecretDataFlattener.flatten(data, "");

            assertEquals("value", result.get("key"));
        }

        @Test
        @DisplayName("prefix is prepended to all nested dot-joined keys")
        void prefixPrependedToNestedKeys() {
            Map<String, Object> inner = new HashMap<>();
            inner.put("host", "pg-host");
            inner.put("port", "5432");
            Map<String, Object> data = new HashMap<>();
            data.put("db", inner);

            Map<String, String> result = SecretDataFlattener.flatten(data, "app.");

            assertEquals("pg-host", result.get("app.db.host"));
            assertEquals("5432", result.get("app.db.port"));
        }
    }

    // --- Nested maps ---

    @Nested
    @DisplayName("nested map flattening")
    class NestedMaps {

        @Test
        @DisplayName("single level of nesting produces dot-joined keys")
        void singleLevelNestingDotJoined() {
            Map<String, Object> nested = new HashMap<>();
            nested.put("host", "pg-host");
            nested.put("port", "5432");
            Map<String, Object> data = new HashMap<>();
            data.put("db", nested);

            Map<String, String> result = SecretDataFlattener.flatten(data, "");

            assertEquals("pg-host", result.get("db.host"));
            assertEquals("5432", result.get("db.port"));
        }

        @Test
        @DisplayName("two levels of nesting produces two-dot keys")
        void twoLevelsOfNesting() {
            Map<String, Object> innermost = new HashMap<>();
            innermost.put("password", "secret");
            Map<String, Object> middle = new HashMap<>();
            middle.put("credentials", innermost);
            Map<String, Object> data = new HashMap<>();
            data.put("db", middle);

            Map<String, String> result = SecretDataFlattener.flatten(data, "");

            assertEquals("secret", result.get("db.credentials.password"));
        }

        @Test
        @DisplayName("mix of flat and nested entries in same map")
        void mixedFlatAndNested() {
            Map<String, Object> nested = new HashMap<>();
            nested.put("host", "pg-host");
            Map<String, Object> data = new HashMap<>();
            data.put("flat", "value");
            data.put("nested", nested);

            Map<String, String> result = SecretDataFlattener.flatten(data, "");

            assertEquals("value", result.get("flat"));
            assertEquals("pg-host", result.get("nested.host"));
        }
    }

    // --- Null skipping ---

    @Nested
    @DisplayName("null value skipping")
    class NullSkipping {

        @Test
        @DisplayName("null values are skipped — no entry produced")
        void nullValuesSkipped() {
            Map<String, Object> data = new HashMap<>();
            data.put("present", "value");
            data.put("nullKey", null);

            Map<String, String> result = SecretDataFlattener.flatten(data, "");

            assertEquals("value", result.get("present"));
            assertTrue(!result.containsKey("nullKey"), "null-valued key must not appear in result");
            assertEquals(1, result.size());
        }
    }

    // --- Non-string scalar stringification ---

    @Nested
    @DisplayName("non-string scalar stringification")
    class ScalarStringification {

        @Test
        @DisplayName("integer is stringified via String.valueOf")
        void integerStringified() {
            Map<String, Object> data = new HashMap<>();
            data.put("count", 42);

            assertEquals("42", SecretDataFlattener.flatten(data, "").get("count"));
        }

        @Test
        @DisplayName("boolean is stringified via String.valueOf")
        void booleanStringified() {
            Map<String, Object> data = new HashMap<>();
            data.put("enabled", true);
            data.put("disabled", false);

            Map<String, String> result = SecretDataFlattener.flatten(data, "");
            assertEquals("true", result.get("enabled"));
            assertEquals("false", result.get("disabled"));
        }

        @Test
        @DisplayName("double is stringified via String.valueOf")
        void doubleStringified() {
            Map<String, Object> data = new HashMap<>();
            data.put("ratio", 3.14);

            assertEquals("3.14", SecretDataFlattener.flatten(data, "").get("ratio"));
        }
    }

    // --- Insertion order ---

    @Nested
    @DisplayName("insertion order preservation")
    class InsertionOrder {

        @Test
        @DisplayName("result map preserves insertion order of source entries")
        void insertionOrderPreserved() {
            // Use LinkedHashMap for predictable source order
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("a", "first");
            data.put("b", "second");
            data.put("c", "third");

            Map<String, String> result = SecretDataFlattener.flatten(data, "");

            var keys = result.keySet().toArray();
            assertEquals("a", keys[0]);
            assertEquals("b", keys[1]);
            assertEquals("c", keys[2]);
        }
    }

    // --- Single-map contract (no merge, no collision) ---

    @Nested
    @DisplayName("single-map contract")
    class SingleMapContract {

        @Test
        @DisplayName("flattening one map produces entries only for that map")
        void oneMapNoMerge() {
            Map<String, Object> data = Map.of("x", "1");
            Map<String, String> result = SecretDataFlattener.flatten(data, "");

            assertEquals(1, result.size());
            assertEquals("1", result.get("x"));
        }
    }

    // --- Collision policy (caller-managed) ---

    @Nested
    @DisplayName("collision policy — later entry wins")
    class CollisionPolicy {

        /**
         * Documented contract: when two calls to {@code flatten} produce the same prefixed key and
         * the caller merges the results into a single map (the normal multi-secret scenario), the
         * later put wins. This test pins the behaviour so it is intentional and visible.
         */
        @Test
        @DisplayName("when two flatten calls produce the same key, later call wins in merged map")
        void laterEntryWinsInCallerMerge() {
            // Simulate what AwsSecretsPropertySource does: each secret flattened
            // individually, results merged into one shared map.
            Map<String, String> combined = new LinkedHashMap<>();

            // First secret: db.host = "host-a"
            combined.putAll(SecretDataFlattener.flatten(Map.of("host", "host-a"), "db."));
            // Second secret: also produces db.host — later entry wins
            combined.putAll(SecretDataFlattener.flatten(Map.of("host", "host-b"), "db."));

            assertEquals("host-b", combined.get("db.host"), "later flatten result must overwrite earlier one");
            assertEquals(1, combined.size(), "only one entry produced — no phantom keys");
        }
    }
}
