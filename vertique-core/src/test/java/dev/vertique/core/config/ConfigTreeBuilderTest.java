// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.exception.ConfigurationException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigTreeBuilder} — the flat→nested config-tree builder used by the
 * Spring/Quarkus host bridges.
 *
 * <p>Verifies the frozen syntactic grammar:
 * <ul>
 *   <li>bare segments → object keys (including bare numerics, which are keys, never indices);</li>
 *   <li>{@code [N]} → array indices; literal dotted/quoted bracket content → literal map keys
 *       preserving inner dots;</li>
 *   <li>leaf values stored verbatim as {@link String}s (type coercion is downstream);</li>
 *   <li>fail-fast on non-contiguous array indices, mixed array/object nodes, and leaf/parent
 *       collisions, with error messages naming keys/paths only — never values (secret hygiene);</li>
 *   <li>build-order independence (collision detected regardless of input map iteration order);</li>
 *   <li>empty input → empty object;</li>
 *   <li>bridge parity (AC-13): Spring-flat and SmallRye-flat key shapes for the same logical config
 *       produce the identical nested tree.</li>
 * </ul>
 */
class ConfigTreeBuilderTest {

    // --- Basic object nesting ---

    @Nested
    @DisplayName("object nesting")
    class ObjectNesting {

        @Test
        @DisplayName("flat scalars nest into sibling object keys")
        void flatScalars() {
            JsonObject result = ConfigTreeBuilder.build(Map.of("a.b", "1", "a.c", "2"));
            assertEquals(
                    new JsonObject().put("a", new JsonObject().put("b", "1").put("c", "2")), result);
        }

        @Test
        @DisplayName("deeply nested keyed-object path nests one object per segment")
        void deeplyNested() {
            JsonObject result = ConfigTreeBuilder.build(Map.of("kafka.consumers.foo.topic", "t"));
            JsonObject expected = new JsonObject()
                    .put(
                            "kafka",
                            new JsonObject()
                                    .put("consumers", new JsonObject().put("foo", new JsonObject().put("topic", "t"))));
            assertEquals(expected, result);
        }

        @Test
        @DisplayName("leaf values are stored verbatim as strings (no type coercion)")
        void leafValuesAreStrings() {
            JsonObject result = ConfigTreeBuilder.build(Map.of("port", "9090", "enabled", "true"));
            // The builder is type-blind: "9090"/"true" stay strings; ConfigParser coerces downstream.
            assertEquals("9090", result.getString("port"));
            assertEquals("true", result.getString("enabled"));
        }
    }

    // --- Literal map keys (bracketed) ---

    @Nested
    @DisplayName("literal map keys")
    class LiteralMapKeys {

        @Test
        @DisplayName("bracketed dotted content is a literal map key preserving inner dots")
        void literalDotKey() {
            JsonObject result =
                    ConfigTreeBuilder.build(Map.of("audit.capture.bindings[http.server].dimensions[0]", "x"));
            // The "http.server" map key keeps its inner dot; the [0] segment makes "dimensions" an array.
            JsonObject expected = new JsonObject(
                    "{\"audit\":{\"capture\":{\"bindings\":{\"http.server\":{\"dimensions\":[\"x\"]}}}}}");
            assertEquals(expected, result);
        }

        @Test
        @DisplayName("quoted bracket content is a literal map key with quotes stripped")
        void quotedKeyStripsQuotes() {
            JsonObject result = ConfigTreeBuilder.build(Map.of("bindings[\"http.server\"].topic", "t"));
            JsonObject bindings = result.getJsonObject("bindings");
            assertTrue(bindings.containsKey("http.server"), "quotes should be stripped from the map key");
            assertEquals("t", bindings.getJsonObject("http.server").getString("topic"));
        }

        @Test
        @DisplayName("quoted all-digit bracket content is a literal key, not an array index")
        void quotedDigitsAreKey() {
            JsonObject result = ConfigTreeBuilder.build(Map.of("m[\"0\"]", "v"));
            // Quoting forces key semantics even for all-digit content.
            assertEquals("v", result.getJsonObject("m").getString("0"));
        }

        @Test
        @DisplayName("single-quoted bracket content is a literal map key with quotes stripped")
        void singleQuotedKeyStripsQuotes() {
            JsonObject result = ConfigTreeBuilder.build(Map.of("m['a.b']", "v"));
            assertEquals("v", result.getJsonObject("m").getString("a.b"));
        }

        @Test
        @DisplayName("leading-zero bracket content is a literal key, not index 0")
        void leadingZeroBracketIsKey() {
            // "01" is not a canonical int literal, so it stays a literal map key (round-trips verbatim).
            JsonObject result = ConfigTreeBuilder.build(Map.of("m[01]", "v"));
            assertEquals("v", result.getJsonObject("m").getString("01"));
        }

        @Test
        @DisplayName("consecutive brackets nest a literal map key then an array index")
        void consecutiveBrackets() {
            JsonObject result = ConfigTreeBuilder.build(Map.of("bindings[a.b][0]", "x"));
            JsonArray arr = result.getJsonObject("bindings").getJsonArray("a.b");
            assertEquals("x", arr.getString(0));
        }
    }

    // --- Bare numerics ---

    @Nested
    @DisplayName("bare numeric segments")
    class BareNumeric {

        @Test
        @DisplayName("bare numeric segment is an object key, never an array index")
        void bareNumericIsObjectKey() {
            JsonObject result = ConfigTreeBuilder.build(Map.of("years.2026.total", "5"));
            JsonObject expected =
                    new JsonObject().put("years", new JsonObject().put("2026", new JsonObject().put("total", "5")));
            assertEquals(expected, result);
        }
    }

    // --- Arrays ---

    @Nested
    @DisplayName("arrays")
    class Arrays {

        @Test
        @DisplayName("indexed object elements build an array of objects")
        void arrayOfObjects() {
            Map<String, String> flat = new LinkedHashMap<>();
            flat.put("servers[0].host", "h");
            flat.put("servers[1].host", "k");
            JsonObject result = ConfigTreeBuilder.build(flat);
            JsonObject expected = new JsonObject()
                    .put(
                            "servers",
                            new JsonArray()
                                    .add(new JsonObject().put("host", "h"))
                                    .add(new JsonObject().put("host", "k")));
            assertEquals(expected, result);
        }

        @Test
        @DisplayName("indexed scalar elements build an array of scalars")
        void arrayOfScalars() {
            Map<String, String> flat = new LinkedHashMap<>();
            flat.put("tags[0]", "a");
            flat.put("tags[1]", "b");
            JsonObject result = ConfigTreeBuilder.build(flat);
            JsonObject expected =
                    new JsonObject().put("tags", new JsonArray().add("a").add("b"));
            assertEquals(expected, result);
        }

        @Test
        @DisplayName("non-contiguous indices (a gap) fail fast naming the path, not padding with nulls")
        void nonContiguousGapFails() {
            Map<String, String> flat = new LinkedHashMap<>();
            flat.put("tags[0]", "a");
            flat.put("tags[2]", "c");
            ConfigurationException ex = assertThrows(ConfigurationException.class, () -> ConfigTreeBuilder.build(flat));
            assertTrue(ex.getMessage().contains("tags"), "message should name the array path");
            assertTrue(ex.getMessage().contains("non-contiguous"), "message should explain the gap");
        }

        @Test
        @DisplayName("array index and object key at the same node fail fast")
        void mixedArrayAndObjectFails() {
            Map<String, String> flat = new LinkedHashMap<>();
            flat.put("x[0]", "a");
            flat.put("x.name", "n");
            ConfigurationException ex = assertThrows(ConfigurationException.class, () -> ConfigTreeBuilder.build(flat));
            assertTrue(ex.getMessage().contains("x"), "message should name the offending key");
        }
    }

    // --- Collision (leaf vs parent) ---

    @Nested
    @DisplayName("leaf/parent collision")
    class Collision {

        @Test
        @DisplayName("a path used as both a leaf and a parent fails fast naming both keys' shared path")
        void leafThenParentFails() {
            Map<String, String> flat = new LinkedHashMap<>();
            flat.put("a.b", "1");
            flat.put("a.b.c", "2");
            ConfigurationException ex = assertThrows(ConfigurationException.class, () -> ConfigTreeBuilder.build(flat));
            assertTrue(ex.getMessage().contains("a.b"), "message should name the colliding path");
        }

        @Test
        @DisplayName("collision is detected regardless of input map iteration order (parent-first)")
        void collisionOrderIndependentParentFirst() {
            // LinkedHashMap preserves insertion order; here the longer (parent) key is inserted first.
            Map<String, String> flat = new LinkedHashMap<>();
            flat.put("a.b.c", "2");
            flat.put("a.b", "1");
            assertThrows(ConfigurationException.class, () -> ConfigTreeBuilder.build(flat));
        }

        @Test
        @DisplayName("collision is detected regardless of input map iteration order (leaf-first)")
        void collisionOrderIndependentLeafFirst() {
            Map<String, String> flat = new LinkedHashMap<>();
            flat.put("a.b", "1");
            flat.put("a.b.c", "2");
            assertThrows(ConfigurationException.class, () -> ConfigTreeBuilder.build(flat));
        }
    }

    // --- Secret non-leakage ---

    @Nested
    @DisplayName("secret non-leakage")
    class SecretNonLeakage {

        @Test
        @DisplayName("a collision whose values are secrets reports keys/paths only, never the values")
        void collisionMessageHasNoSecretValues() {
            String secret1 = "s3cr3t-passw0rd-do-not-leak";
            String secret2 = "tok_AKIA_should_not_appear";
            Map<String, String> flat = new LinkedHashMap<>();
            flat.put("db.password", secret1);
            flat.put("db.password.encrypted", secret2);
            ConfigurationException ex = assertThrows(ConfigurationException.class, () -> ConfigTreeBuilder.build(flat));
            // The message must name the keys/paths...
            assertTrue(ex.getMessage().contains("db.password"), "message should name the colliding path");
            // ...but never echo the secret values.
            assertFalse(ex.getMessage().contains(secret1), "message must not leak the first secret value");
            assertFalse(ex.getMessage().contains(secret2), "message must not leak the second secret value");
        }

        @Test
        @DisplayName("an array-gap error whose values are secrets reports paths only, never the values")
        void arrayGapMessageHasNoSecretValues() {
            String secret = "password=hunter2-leaked";
            Map<String, String> flat = new LinkedHashMap<>();
            flat.put("creds[0]", "ok");
            flat.put("creds[2]", secret);
            ConfigurationException ex = assertThrows(ConfigurationException.class, () -> ConfigTreeBuilder.build(flat));
            assertTrue(ex.getMessage().contains("creds"), "message should name the array path");
            assertFalse(ex.getMessage().contains(secret), "message must not leak the secret value");
        }
    }

    // --- Empty input ---

    @Nested
    @DisplayName("empty input")
    class EmptyInput {

        @Test
        @DisplayName("empty map produces an empty object")
        void emptyMap() {
            assertEquals(new JsonObject(), ConfigTreeBuilder.build(Map.of()));
        }

        @Test
        @DisplayName("a null value is stored as a JSON null leaf, not dropped")
        void nullValueStoredAsNullLeaf() {
            Map<String, String> flat = new LinkedHashMap<>();
            flat.put("a.b", null);
            JsonObject result = ConfigTreeBuilder.build(flat);
            assertTrue(result.getJsonObject("a").containsKey("b"), "the leaf key should be present");
            assertTrue(result.getJsonObject("a").getValue("b") == null, "the leaf value should be JSON null");
        }
    }

    // --- Malformed keys (fail fast) ---

    @Nested
    @DisplayName("malformed keys")
    class MalformedKeys {

        @Test
        @DisplayName("a trailing dot is an empty segment and fails fast")
        void trailingDotFails() {
            assertThrows(ConfigurationException.class, () -> ConfigTreeBuilder.build(Map.of("a.", "x")));
        }

        @Test
        @DisplayName("a doubled dot is an empty segment and fails fast")
        void doubledDotFails() {
            assertThrows(ConfigurationException.class, () -> ConfigTreeBuilder.build(Map.of("a..b", "x")));
        }

        @Test
        @DisplayName("a leading dot is an empty segment and fails fast")
        void leadingDotFails() {
            assertThrows(ConfigurationException.class, () -> ConfigTreeBuilder.build(Map.of(".a", "x")));
        }

        @Test
        @DisplayName("an unclosed bracket fails fast")
        void unclosedBracketFails() {
            assertThrows(ConfigurationException.class, () -> ConfigTreeBuilder.build(Map.of("a[0", "x")));
        }

        @Test
        @DisplayName("text after a closing bracket fails fast")
        void textAfterBracketFails() {
            assertThrows(ConfigurationException.class, () -> ConfigTreeBuilder.build(Map.of("a[0]x", "v")));
        }

        @Test
        @DisplayName("a blank key fails fast")
        void blankKeyFails() {
            assertThrows(ConfigurationException.class, () -> ConfigTreeBuilder.build(Map.of("   ", "v")));
        }
    }

    // --- Bridge parity (AC-13) ---

    @Nested
    @DisplayName("bridge parity (AC-13)")
    class BridgeParity {

        /**
         * Builds the flat-key map a Spring {@code Environment} would expose for the sample logical
         * config below.
         *
         * <p>Spring relaxed binding surfaces nested config and {@code @ConfigurationProperties} maps as
         * dotted property names; a map entry whose key itself contains a dot is expressed with bracket
         * notation ({@code bindings[http.server]}). Array elements use {@code name[index]}. So the
         * Spring flat shape for our sample is dotted segments + {@code [N]} arrays + bracketed literal
         * map keys, e.g. {@code vertique.kafka.consumers.foo.topic=orders}.
         *
         * @return the Spring-style flat key map
         */
        private Map<String, String> springFlat() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("vertique.kafka.consumers.foo.topic", "orders");
            m.put("vertique.kafka.consumers.foo.group", "g1");
            m.put("vertique.audit.bindings[http.server].enabled", "true");
            m.put("vertique.servers[0].host", "h0");
            m.put("vertique.servers[1].host", "h1");
            return m;
        }

        /**
         * Builds the flat-key map for the SAME logical config in the framework's normalized
         * <strong>bracket</strong> grammar — the single key shape {@link ConfigTreeBuilder} consumes.
         *
         * <p>Both the Spring and SmallRye/MicroProfile bridges target this bracket form, so a
         * literal-dot map key is expressed here as {@code bindings["http.server"]} (not the raw
         * SmallRye quoted-dot {@code bindings."http.server"}). Converting a raw SmallRye/Spring
         * property name whose segment contains a dot into this bracket form is the <em>bridge's</em>
         * normalization step, which is out of Phase-0 scope and deferred to the Quarkus bridge PRD;
         * this fixture therefore feeds the already-normalized bracket key directly. The two fixtures
         * differ only cosmetically and must build the identical tree.
         *
         * @return the bracket-grammar flat key map (the normalized SmallRye target shape)
         */
        private Map<String, String> smallryeFlat() {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("vertique.kafka.consumers.foo.topic", "orders");
            m.put("vertique.kafka.consumers.foo.group", "g1");
            m.put("vertique.audit.bindings[\"http.server\"].enabled", "true");
            m.put("vertique.servers[0].host", "h0");
            m.put("vertique.servers[1].host", "h1");
            return m;
        }

        @Test
        @DisplayName("Spring-flat and SmallRye-flat shapes for the same logical config build the identical tree")
        void springAndSmallryeProduceIdenticalTree() {
            JsonObject fromSpring = ConfigTreeBuilder.build(springFlat());
            JsonObject fromSmallrye = ConfigTreeBuilder.build(smallryeFlat());
            assertEquals(fromSpring, fromSmallrye);
        }
    }
}
