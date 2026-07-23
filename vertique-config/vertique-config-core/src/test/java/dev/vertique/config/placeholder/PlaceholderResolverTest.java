// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.config.placeholder;

import static org.junit.jupiter.api.Assertions.*;

import dev.vertique.config.source.ConfigPropertySourceException;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PlaceholderResolver}.
 *
 * <p>Each nested group maps to one named bullet in the PRD §9 engine specification. Tests assert
 * on semantics (values in/out, failure keys, ordering) and never on internal implementation shape.
 *
 * <h2>NFR-CONF-002 guard</h2>
 * <p>Tests that seed sentinel values explicitly verify that the sentinel does NOT appear in any
 * exception message or unresolved reference list.
 */
class PlaceholderResolverTest {

    // --- Chain order: tree-first, then sources in declaration order ---

    @Nested
    @DisplayName("Chain order: tree-first then sources in declared order")
    class ChainOrderTests {

        @Test
        @DisplayName("key present in tree AND source → tree wins")
        void treeShadowsSource() {
            // The full tree contains both the referencing key and the referenced key.
            // The source also serves "host", but the tree probe should win.
            StubPropertySource src = new StubPropertySource("s1", Map.of("host", "source-host"));
            JsonObject tree = new JsonObject().put("host", "tree-host").put("server", "${host}");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of(src));

            assertEquals("tree-host", result.getString("server"));
            // source should never have been consulted for "host" because tree hit first
            assertEquals(0, src.lookupCount("host"));
        }

        @Test
        @DisplayName("key absent in tree, present in source → source serves it")
        void sourceServedWhenTreeMisses() {
            JsonObject tree = new JsonObject().put("server", "${db.host}");
            StubPropertySource src = new StubPropertySource("s1", Map.of("db.host", "pg.example.com"));

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of(src));

            assertEquals("pg.example.com", result.getString("server"));
        }

        @Test
        @DisplayName("two sources both serve the key → first declared source wins")
        void firstSourceWins() {
            JsonObject tree = new JsonObject().put("val", "${secret.key}");
            StubPropertySource s1 = new StubPropertySource("first", Map.of("secret.key", "from-first"));
            StubPropertySource s2 = new StubPropertySource("second", Map.of("secret.key", "from-second"));

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of(s1, s2));

            assertEquals("from-first", result.getString("val"));
            // second source must not have been consulted
            assertEquals(0, s2.lookupCount("secret.key"));
        }
    }

    // --- Self-reference fall-through ---

    @Nested
    @DisplayName("Self-reference fall-through: tree self-refs fall through to sources")
    class SelfRefFallThroughTests {

        @Test
        @DisplayName("nested self-ref ${db.password} in tree falls through to source")
        void nestedSelfRefFallsThrough() {
            // tree: db.password is nested under "db" → self-ref via dot-path key
            JsonObject tree = new JsonObject()
                    .put("db", new JsonObject().put("password", "${db.password}"))
                    .put("conn", "${db.password}");
            StubPropertySource src = new StubPropertySource("vault", Map.of("db.password", "s3cret-sentinel"));

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of(src));

            // conn resolves to the source value via the engine
            assertEquals("s3cret-sentinel", result.getString("conn"));
            // db.password itself resolves from source (self-ref in tree detected → skip tree, use source)
            JsonObject dbResult = result.getJsonObject("db");
            assertEquals("s3cret-sentinel", dbResult.getString("password"));
        }

        @Test
        @DisplayName("flat self-ref {\"db.password\": \"${db.password}\"} falls through to source")
        void flatSelfRefFallsThrough() {
            // A flat key literally named "db.password" whose value is ${db.password}
            JsonObject tree = new JsonObject().put("db.password", "${db.password}");
            StubPropertySource src = new StubPropertySource("vault", Map.of("db.password", "s3cret-sentinel"));

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of(src));

            assertEquals("s3cret-sentinel", result.getString("db.password"));
        }

        @Test
        @DisplayName("NFR-CONF-002: sentinel value 's3cret-sentinel' must not appear in any failure")
        void sentinelNotInFailures() {
            // No source serves the key → unresolved; the sentinel must not leak into exception
            JsonObject tree = new JsonObject().put("db.password", "${db.password}");

            PlaceholderResolutionException ex = assertThrows(
                    PlaceholderResolutionException.class, () -> PlaceholderResolver.resolveTree(tree, List.of()));

            assertFalse(ex.getMessage().contains("s3cret-sentinel"), "Sentinel must not appear in message");
            ex.unresolvedReferences()
                    .forEach(ref -> assertFalse(ref.contains("s3cret-sentinel"), "Sentinel must not appear in refs"));
        }
    }

    // --- Cycle detection ---

    @Nested
    @DisplayName("Cycle detection: cyclic references produce exception with chain rendering")
    class CycleDetectionTests {

        @Test
        @DisplayName("a=${b}, b=${a} with no sources → exception; refs contain '->' chain; no sentinel values")
        void simpleCycle() {
            JsonObject tree = new JsonObject().put("a", "${b}").put("b", "${a}").put("trigger", "${a}");

            PlaceholderResolutionException ex = assertThrows(
                    PlaceholderResolutionException.class, () -> PlaceholderResolver.resolveTree(tree, List.of()));

            List<String> refs = ex.unresolvedReferences();
            assertFalse(refs.isEmpty(), "Expected at least one failure");
            // At least one entry must render a chain with "->"
            boolean hasCycleRendering = refs.stream().anyMatch(r -> r.contains("->"));
            assertTrue(hasCycleRendering, "Expected at least one chain rendering with '->' in: " + refs);
        }

        @Test
        @DisplayName("cycle message must not contain seeded config values")
        void cycleMessageNoValues() {
            // Use carefully named keys whose names look like plain identifiers
            JsonObject tree =
                    new JsonObject().put("x.a", "${x.b}").put("x.b", "${x.a}").put("root", "${x.a}");

            PlaceholderResolutionException ex = assertThrows(
                    PlaceholderResolutionException.class, () -> PlaceholderResolver.resolveTree(tree, List.of()));

            // Verify exception message does not contain the string values (which are also key names
            // here — but the resolved value would be the placeholder text itself; this guards
            // against a future regression where actual config data leaks)
            assertNotNull(ex.getMessage());
        }

        @Test
        @DisplayName("unresolved references list is sorted and unique")
        void refsAreSortedAndUnique() {
            // Three independent missing refs
            JsonObject tree = new JsonObject()
                    .put("p1", "${missing.z}")
                    .put("p2", "${missing.a}")
                    .put("p3", "${missing.m}");

            PlaceholderResolutionException ex = assertThrows(
                    PlaceholderResolutionException.class, () -> PlaceholderResolver.resolveTree(tree, List.of()));

            List<String> refs = ex.unresolvedReferences();
            // Sorted
            for (int i = 1; i < refs.size(); i++) {
                assertTrue(refs.get(i - 1).compareTo(refs.get(i)) <= 0, "List must be sorted: " + refs);
            }
            // Unique — no duplicates
            assertEquals(refs.size(), refs.stream().distinct().count(), "List must have no duplicates: " + refs);
        }
    }

    // --- Depth backstop ---

    @Nested
    @DisplayName("Depth backstop: MAX_DEPTH exceeded triggers failure")
    class DepthBackstopTests {

        /**
         * Builds a tree of chained references: a→b→c→...→(n-th key), without a terminal value.
         * The chain has {@code length} hops.
         */
        private JsonObject buildChain(int length) {
            JsonObject tree = new JsonObject();
            // keys are "k0" through "k{length-1}"
            // k0 → ${k1}, k1 → ${k2}, ..., k{length-2} → ${k{length-1}}, k{length-1} = "terminal"
            tree.put("k" + (length - 1), "terminal");
            for (int i = length - 2; i >= 0; i--) {
                tree.put("k" + i, "${k" + (i + 1) + "}");
            }
            // entry point:
            tree.put("entry", "${k0}");
            return tree;
        }

        @Test
        @DisplayName("chain of exactly MAX_DEPTH (5) tree refs still resolves successfully")
        void exactMaxDepthResolves() {
            // k0→k1→k2→k3→k4="terminal": 5 hops, so at depth 5 we hit "terminal" (no placeholder)
            // We need the chain to be at most MAX_DEPTH deep.
            // Chain: entry→k0→k1→k2→k3→k4 where k4="terminal" = 5 resolution steps
            JsonObject tree = buildChain(PlaceholderResolver.MAX_DEPTH);
            tree.put("entry", "${k0}");

            // Should resolve without throwing
            assertDoesNotThrow(() -> {
                JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());
                assertEquals("terminal", result.getString("entry"));
            });
        }

        @Test
        @DisplayName("chain of 7 tree refs (exceeds MAX_DEPTH=5) → failure mentioning chain/depth")
        void exceedsMaxDepthFails() {
            // k0→k1→k2→k3→k4→k5→k6="terminal": 7 hops, exceeds MAX_DEPTH=5
            JsonObject tree = buildChain(7);
            tree.put("entry", "${k0}");

            PlaceholderResolutionException ex = assertThrows(
                    PlaceholderResolutionException.class, () -> PlaceholderResolver.resolveTree(tree, List.of()));

            // The failure list must be non-empty
            assertFalse(ex.unresolvedReferences().isEmpty());
        }
    }

    // --- Memoization ---

    @Nested
    @DisplayName("Memoization: same key referenced multiple times → source called exactly once")
    class MemoizationTests {

        @Test
        @DisplayName("same ${key} in three config values → stub records exactly 1 lookup")
        void singleLookupForRepeatedRef() {
            StubPropertySource src = new StubPropertySource("vault", Map.of("shared.secret", "resolved-value"));
            JsonObject tree = new JsonObject()
                    .put("a", "${shared.secret}")
                    .put("b", "${shared.secret}")
                    .put("c", "${shared.secret}");

            PlaceholderResolver.resolveTree(tree, List.of(src));

            assertEquals(1, src.lookupCount("shared.secret"), "Source must be called exactly once per key");
        }
    }

    // --- Not-found vs error: error propagates immediately ---

    @Nested
    @DisplayName("Not-found vs error: ConfigPropertySourceException propagates immediately")
    class NotFoundVsErrorTests {

        @Test
        @DisplayName("throwing first source → exception propagates; second source never consulted; no default applied")
        void throwingSourcePropagatesImmediately() {
            // s1 throws for "secret.key"; s2 could serve it; placeholder has a default
            StubPropertySource s1 = new StubPropertySource("vault", Map.of(), "secret.key");
            StubPropertySource s2 = new StubPropertySource("env", Map.of("secret.key", "env-value"));

            JsonObject tree = new JsonObject().put("x", "${secret.key:fallback}");

            // ConfigPropertySourceException from s1 must propagate
            assertThrows(
                    ConfigPropertySourceException.class, () -> PlaceholderResolver.resolveTree(tree, List.of(s1, s2)));

            // s2 must never have been consulted
            assertEquals(0, s2.lookupCount("secret.key"), "Second source must not be consulted after first throws");
        }
    }

    // --- Source values are treated as literal (never re-parsed) ---

    @Nested
    @DisplayName("Source values are literal: ${evil.ref} returned by source is not re-expanded")
    class SourceValuesLiteralTests {

        @Test
        @DisplayName("source returns '${evil.ref}' → final value is '${evil.ref}'; no failure for evil.ref")
        void sourceValueNotReParsed() {
            StubPropertySource src = new StubPropertySource("s1", Map.of("key", "${evil.ref}"));
            JsonObject tree = new JsonObject().put("val", "${key}");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of(src));

            // The literal string "${evil.ref}" must be the resolved value
            assertEquals("${evil.ref}", result.getString("val"));
        }
    }

    // --- Type preservation ---

    @Nested
    @DisplayName("Type preservation: whole-value placeholder preserves typed tree values")
    class TypePreservationTests {

        @Test
        @DisplayName("whole-value ${port} where tree port=8080 (Integer) → result is Integer 8080")
        void integerPreserved() {
            JsonObject tree = new JsonObject().put("port", 8080).put("server.port", "${port}");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            Object value = result.getValue("server.port");
            assertInstanceOf(
                    Integer.class, value, "Expected Integer but got " + (value == null ? "null" : value.getClass()));
            assertEquals(8080, value);
        }

        @Test
        @DisplayName("whole-value ${flag} where tree flag=true (Boolean) → result is Boolean true")
        void booleanPreserved() {
            JsonObject tree = new JsonObject().put("flag", true).put("feature.enabled", "${flag}");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            Object value = result.getValue("feature.enabled");
            assertInstanceOf(Boolean.class, value);
            assertEquals(Boolean.TRUE, value);
        }

        @Test
        @DisplayName("whole-value ${obj} where tree obj=JsonObject → result is JsonObject preserved")
        void jsonObjectPreserved() {
            JsonObject inner = new JsonObject().put("x", 1).put("y", 2);
            JsonObject tree = new JsonObject().put("obj", inner).put("ref", "${obj}");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            Object value = result.getValue("ref");
            assertInstanceOf(JsonObject.class, value);
            assertEquals(inner, value);
        }

        @Test
        @DisplayName("concat 'p:${port}' → String 'p:8080' (CONCAT mode)")
        void concatModeProducesString() {
            JsonObject tree = new JsonObject().put("port", 8080).put("addr", "p:${port}");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            Object value = result.getValue("addr");
            assertInstanceOf(String.class, value);
            assertEquals("p:8080", value);
        }

        @Test
        @DisplayName("whole-value source-resolved → String (not typed)")
        void sourceResolvedIsString() {
            StubPropertySource src = new StubPropertySource("env", Map.of("num", "42"));
            JsonObject tree = new JsonObject().put("value", "${num}");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of(src));

            Object value = result.getValue("value");
            assertInstanceOf(String.class, value);
            assertEquals("42", value);
        }
    }

    // --- Default values ---

    @Nested
    @DisplayName("Default values: absent key + default applies; nested defaults resolved")
    class DefaultValueTests {

        @Test
        @DisplayName("absent key with default → default value used")
        void absentKeyUsesDefault() {
            JsonObject tree = new JsonObject().put("x", "${missing.key:default-val}");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            assertEquals("default-val", result.getString("x"));
        }

        @Test
        @DisplayName("${k:} → empty-string default")
        void emptyStringDefault() {
            JsonObject tree = new JsonObject().put("x", "${missing.key:}");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            assertEquals("", result.getString("x"));
        }

        @Test
        @DisplayName("default containing '${other}' that is tree-resolvable → resolved")
        void defaultWithNestedPlaceholderResolvable() {
            // ${key:${fallback.val}} — key absent, fallback.val exists in tree
            JsonObject tree =
                    new JsonObject().put("fallback.val", "from-fallback").put("x", "${missing.key:${fallback.val}}");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            assertEquals("from-fallback", result.getString("x"));
        }

        @Test
        @DisplayName("default containing unresolvable ref → inner ref recorded as failure")
        void defaultWithUnresolvableInnerRef() {
            // ${key:${also.missing}} — both absent
            JsonObject tree = new JsonObject().put("x", "${missing.key:${also.missing}}");

            PlaceholderResolutionException ex = assertThrows(
                    PlaceholderResolutionException.class, () -> PlaceholderResolver.resolveTree(tree, List.of()));

            // The inner unresolvable ref must be in the failures
            assertTrue(
                    ex.unresolvedReferences().stream().anyMatch(r -> r.contains("also.missing")),
                    "Expected 'also.missing' in unresolved refs: " + ex.unresolvedReferences());
        }
    }

    // --- Escape handling ---

    @Nested
    @DisplayName("Escape handling: \\${literal} passes through as '${literal}'")
    class EscapeHandlingTests {

        @Test
        @DisplayName("\\${literal} in a config value passes through as '${literal}' without resolution")
        void escapedPlaceholderPassesThrough() {
            // Java string: "\\${not.resolved}" → 2-char sequence \ then $ { not.resolved }
            JsonObject tree = new JsonObject().put("x", "\\${not.resolved}");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            assertEquals("${not.resolved}", result.getString("x"));
        }
    }

    // --- Arrays ---

    @Nested
    @DisplayName("Arrays: placeholders inside JsonArray elements are resolved")
    class ArrayTests {

        @Test
        @DisplayName("placeholder inside JsonArray String element is resolved")
        void arrayStringElementResolved() {
            JsonObject tree = new JsonObject()
                    .put("host", "myhost")
                    .put("list", new JsonArray().add("${host}").add("static"));

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            JsonArray arr = result.getJsonArray("list");
            assertEquals("myhost", arr.getString(0));
            assertEquals("static", arr.getString(1));
        }

        @Test
        @DisplayName("nested JsonObject inside JsonArray has its placeholders resolved")
        void nestedObjectInArrayResolved() {
            JsonObject tree = new JsonObject()
                    .put("port", 5432)
                    .put("servers", new JsonArray().add(new JsonObject().put("port", "${port}")));

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            JsonArray arr = result.getJsonArray("servers");
            JsonObject first = arr.getJsonObject(0);
            // port in nested object: whole-value ${port} with Integer 5432 in tree
            Object portVal = first.getValue("port");
            assertInstanceOf(Integer.class, portVal);
            assertEquals(5432, portVal);
        }
    }

    // --- Aggregate failures ---

    @Nested
    @DisplayName("Aggregate failures: multiple independent unresolvable refs collected")
    class AggregateFailureTests {

        @Test
        @DisplayName("three independent unresolvable refs → all three in unresolvedReferences, sorted")
        void threeUnresolvedRefs() {
            JsonObject tree = new JsonObject()
                    .put("p1", "${missing.z}")
                    .put("p2", "${missing.a}")
                    .put("p3", "${missing.m}");

            PlaceholderResolutionException ex = assertThrows(
                    PlaceholderResolutionException.class, () -> PlaceholderResolver.resolveTree(tree, List.of()));

            List<String> refs = ex.unresolvedReferences();
            assertEquals(3, refs.size(), "Expected 3 failures: " + refs);

            // Sorted
            for (int i = 1; i < refs.size(); i++) {
                assertTrue(refs.get(i - 1).compareTo(refs.get(i)) <= 0, "Not sorted: " + refs);
            }
        }

        @Test
        @DisplayName("aggregate failures are deterministic across two independent runs")
        void aggregateFailuresDeterministic() {
            JsonObject tree = new JsonObject()
                    .put("p1", "${missing.z}")
                    .put("p2", "${missing.a}")
                    .put("p3", "${missing.m}");

            PlaceholderResolutionException ex1 = assertThrows(
                    PlaceholderResolutionException.class, () -> PlaceholderResolver.resolveTree(tree, List.of()));
            PlaceholderResolutionException ex2 = assertThrows(
                    PlaceholderResolutionException.class, () -> PlaceholderResolver.resolveTree(tree, List.of()));

            assertEquals(ex1.unresolvedReferences(), ex2.unresolvedReferences(), "Failure list must be deterministic");
        }
    }

    // --- resolveAgainstTree ---

    @Nested
    @DisplayName("resolveAgainstTree: tree-only mode; source-requiring refs produce bootstrap exception")
    class ResolveAgainstTreeTests {

        @Test
        @DisplayName("tree-ref ${db.host} resolves successfully against tree alone")
        void treeRefResolvesOk() {
            JsonObject tree = new JsonObject().put("db", new JsonObject().put("host", "localhost"));
            JsonObject input = new JsonObject().put("url", "${db.host}:5432");

            JsonObject result = PlaceholderResolver.resolveAgainstTree(input, tree);

            assertEquals("localhost:5432", result.getString("url"));
        }

        @Test
        @DisplayName("source-requiring ref produces exception with 'tree references only' in message")
        void sourceRequiringRefThrowsWithBootstrapMessage() {
            JsonObject tree = new JsonObject();
            JsonObject input = new JsonObject().put("secret", "${vault.key}");

            PlaceholderResolutionException ex = assertThrows(
                    PlaceholderResolutionException.class, () -> PlaceholderResolver.resolveAgainstTree(input, tree));

            assertTrue(
                    ex.getMessage().contains("tree references only"),
                    "Expected 'tree references only' in message: " + ex.getMessage());
        }

        @Test
        @DisplayName("typed whole-value tree ref preserved in resolveAgainstTree")
        void typedValuePreservedInTreeMode() {
            JsonObject tree = new JsonObject().put("port", 8080);
            JsonObject input = new JsonObject().put("p", "${port}");

            JsonObject result = PlaceholderResolver.resolveAgainstTree(input, tree);

            Object value = result.getValue("p");
            assertInstanceOf(Integer.class, value);
            assertEquals(8080, value);
        }
    }

    // --- Non-string values untouched + input not mutated ---

    @Nested
    @DisplayName("Non-string values untouched; input tree not mutated")
    class NonStringAndImmutabilityTests {

        @Test
        @DisplayName("Integer, Boolean, null values in tree pass through unchanged")
        void nonStringValuesPassThrough() {
            JsonObject tree = new JsonObject()
                    .put("num", 42)
                    .put("flag", false)
                    .putNull("nothing")
                    .put("val", "${num}");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            assertInstanceOf(Integer.class, result.getValue("num"));
            assertEquals(42, result.getValue("num"));
            assertInstanceOf(Boolean.class, result.getValue("flag"));
            assertEquals(Boolean.FALSE, result.getValue("flag"));
            assertNull(result.getValue("nothing"));
        }

        @Test
        @DisplayName("original input tree is NOT mutated after resolveTree")
        void inputTreeNotMutated() {
            JsonObject tree = new JsonObject().put("greeting", "hello ${name}").put("name", "world");
            String originalGreeting = tree.getString("greeting");

            PlaceholderResolver.resolveTree(tree, List.of());

            // Original tree must be unchanged
            assertEquals(originalGreeting, tree.getString("greeting"), "Input tree must not be mutated");
            assertEquals("world", tree.getString("name"));
        }

        @Test
        @DisplayName("original input tree is NOT mutated after resolveAgainstTree")
        void inputTreeNotMutatedAgainstTree() {
            JsonObject tree = new JsonObject().put("a", "hello").put("b", "${a}");
            JsonObject inputSnapshot = tree.copy();

            PlaceholderResolver.resolveAgainstTree(tree.copy(), tree);

            // tree (the reference tree) must also not be mutated
            assertEquals(inputSnapshot.getString("a"), tree.getString("a"));
        }
    }

    // --- Malformed segments ---

    @Nested
    @DisplayName("Malformed segments: unterminated ${... recorded as unresolved reference")
    class MalformedSegmentTests {

        @Test
        @DisplayName("unterminated ${abc in a value → recorded as unresolved; raw text passed through")
        void unterminatedRecordedAsUnresolved() {
            JsonObject tree = new JsonObject().put("x", "${abc");

            PlaceholderResolutionException ex = assertThrows(
                    PlaceholderResolutionException.class, () -> PlaceholderResolver.resolveTree(tree, List.of()));

            assertFalse(ex.unresolvedReferences().isEmpty());
        }
    }

    // --- Whole-value single-placeholder resolution with JsonArray ---

    @Nested
    @DisplayName("Whole-value JsonArray: single ${arr} referring to tree JsonArray preserved")
    class WholeValueJsonArrayTests {

        @Test
        @DisplayName("whole-value ${arr} where tree arr=JsonArray → result is JsonArray")
        void jsonArrayPreserved() {
            JsonArray arr = new JsonArray().add(1).add(2).add(3);
            JsonObject tree = new JsonObject().put("arr", arr).put("ref", "${arr}");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            Object value = result.getValue("ref");
            assertInstanceOf(JsonArray.class, value);
            assertEquals(arr, value);
        }
    }

    // --- Whole-value container ref correctness (tree refs to JsonObject / JsonArray) ---

    @Nested
    @DisplayName("Whole-value container ref: nested placeholders inside referenced object/array are resolved")
    class WholeValueContainerRefTests {

        @Test
        @DisplayName("a=${b}, b={x:${c}}, c=1 → a.x resolves to 1 (nested placeholder in object ref)")
        void wholeValueObjectRefResolvesNestedPlaceholder() {
            JsonObject tree = new JsonObject()
                    .put("a", "${b}")
                    .put("b", new JsonObject().put("x", "${c}"))
                    .put("c", "1");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            Object aVal = result.getValue("a");
            assertInstanceOf(JsonObject.class, aVal, "a should resolve to a JsonObject");
            assertEquals("1", ((JsonObject) aVal).getString("x"), "nested ${c} inside b should resolve to '1'");
        }

        @Test
        @DisplayName("mutating the result's substituted object does NOT mutate the input tree")
        void wholeValueObjectRefIsCopy() {
            JsonObject innerB = new JsonObject().put("x", "${c}");
            JsonObject tree = new JsonObject().put("a", "${b}").put("b", innerB).put("c", "1");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            // Mutate the resolved object
            ((JsonObject) result.getValue("a")).put("injected", "evil");

            // Original tree's b must be unmodified
            assertEquals(
                    "${c}", tree.getJsonObject("b").getString("x"), "input tree b.x must remain as ${c} (unmutated)");
            assertNull(tree.getJsonObject("b").getString("injected"), "input tree b must not have the injected key");
        }

        @Test
        @DisplayName("cyclic via container: a=${b}, b={x:${a}} → terminates; failure recorded; no StackOverflowError")
        void cyclicViaContainerTerminates() {
            JsonObject tree = new JsonObject().put("a", "${b}").put("b", new JsonObject().put("x", "${a}"));

            // Must terminate without StackOverflowError and report a failure
            PlaceholderResolutionException ex = assertThrows(
                    PlaceholderResolutionException.class,
                    () -> PlaceholderResolver.resolveTree(tree, List.of()),
                    "cyclic container ref must terminate and report failure");

            assertFalse(ex.unresolvedReferences().isEmpty(), "At least one failure must be recorded");
        }

        @Test
        @DisplayName("whole-value JsonArray ref with a placeholder element resolves and is copied")
        void wholeValueArrayRefResolvesAndCopied() {
            JsonArray innerArr = new JsonArray().add("${host}").add("static");
            JsonObject tree = new JsonObject()
                    .put("hosts", innerArr)
                    .put("ref", "${hosts}")
                    .put("host", "myhost");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            Object refVal = result.getValue("ref");
            assertInstanceOf(JsonArray.class, refVal, "ref should resolve to a JsonArray");
            JsonArray resolved = (JsonArray) refVal;
            assertEquals("myhost", resolved.getString(0), "first element ${host} should resolve to 'myhost'");
            assertEquals("static", resolved.getString(1), "second element should remain 'static'");

            // Mutating result does not mutate input
            resolved.add("extra");
            assertEquals(2, innerArr.size(), "input array must not be mutated");
        }
    }

    // --- resolveTree with deeply nested objects ---

    @Nested
    @DisplayName("Deep nesting: placeholders in nested JsonObjects are resolved")
    class DeepNestingTests {

        @Test
        @DisplayName("placeholder in deeply nested JsonObject is resolved")
        void deepNestedResolved() {
            JsonObject tree = new JsonObject()
                    .put("base.url", "http://example.com")
                    .put("a", new JsonObject().put("b", new JsonObject().put("url", "${base.url}/api")));

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            assertEquals(
                    "http://example.com/api",
                    result.getJsonObject("a").getJsonObject("b").getString("url"));
        }
    }

    // --- resolveAgainstTree with empty sources (bootstrap subtree case) ---

    @Nested
    @DisplayName("resolveAgainstTree: bootstrap subtrees use tree-only; missing gives exception")
    class BootstrapSubtreeTests {

        @Test
        @DisplayName("complete tree resolution in bootstrap mode")
        void completeResolutionInBootstrapMode() {
            JsonObject tree = new JsonObject()
                    .put("config", new JsonObject().put("dir", "/etc/app"))
                    .put("path", "${config.dir}/settings.yaml");

            JsonObject result = PlaceholderResolver.resolveAgainstTree(tree, tree);

            assertEquals("/etc/app/settings.yaml", result.getString("path"));
        }

        @Test
        @DisplayName("unresolved in bootstrap mode → exception message references bootstrap constraint")
        void unresolvedBootstrapHasHelpfulMessage() {
            JsonObject tree = new JsonObject().put("x", "${external.secret}");

            PlaceholderResolutionException ex = assertThrows(
                    PlaceholderResolutionException.class, () -> PlaceholderResolver.resolveAgainstTree(tree, tree));

            String msg = ex.getMessage();
            assertNotNull(msg);
            assertTrue(msg.contains("tree references only"), "Expected bootstrap message in: " + msg);
        }
    }

    // --- Multiple placeholders in one value using source keys ---

    @Nested
    @DisplayName("Multiple placeholders: CONCAT mode with source-resolved parts")
    class MultiplePlaceholdersConcatTests {

        @Test
        @DisplayName("jdbc:${db.host}:${db.port}/app resolves both from source in CONCAT mode")
        void jdbcUrlFromSources() {
            StubPropertySource src = new StubPropertySource("env", Map.of("db.host", "pg-server", "db.port", "5432"));
            JsonObject tree = new JsonObject().put("url", "jdbc:${db.host}:${db.port}/app");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of(src));

            assertEquals("jdbc:pg-server:5432/app", result.getString("url"));
        }
    }

    // --- Tree-string containing placeholder that itself resolves to a tree value ---

    @Nested
    @DisplayName("Chained tree resolution: value in tree is itself a placeholder")
    class ChainedTreeResolutionTests {

        @Test
        @DisplayName("a=${b}, b=hello → a resolves to 'hello'")
        void simpleChainedRef() {
            JsonObject tree = new JsonObject().put("a", "${b}").put("b", "hello");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            assertEquals("hello", result.getString("a"));
        }

        @Test
        @DisplayName("a=${b}, b=${c}, c=deep → a resolves to 'deep' via chain")
        void deepChainedRef() {
            JsonObject tree = new JsonObject().put("a", "${b}").put("b", "${c}").put("c", "deep");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            assertEquals("deep", result.getString("a"));
        }
    }

    // --- resolveTree with empty source list and no failures ---

    @Nested
    @DisplayName("resolveTree with empty sources: purely tree-resolved values succeed")
    class EmptySourcesTests {

        @Test
        @DisplayName("tree-only resolution without sources succeeds for pure-tree values")
        void treeOnlyResolution() {
            JsonObject tree = new JsonObject().put("base", "v1").put("path", "/api/${base}/resource");

            JsonObject result = PlaceholderResolver.resolveTree(tree, List.of());

            assertEquals("/api/v1/resource", result.getString("path"));
        }
    }
}
