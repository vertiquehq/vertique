// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputLocation;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.sanitization.SkipCanonicalization;
import dev.vertique.core.sanitization.SkipSanitization;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link DefaultInputObjectProcessor} — verifies map traversal, chain application,
 * skip semantics, nested maps, lists, and passthrough of non-string values.
 */
class DefaultInputObjectProcessorTest {

    private InputPolicyMetadataResolver metadataResolver;
    private DefaultInputObjectProcessor processor;

    @BeforeEach
    void setUp() {
        metadataResolver = new InputPolicyMetadataResolver();
        processor = new DefaultInputObjectProcessor(
                metadataResolver,
                cls -> {
                    if (cls == TestTrimCanonicalizer.class) return new TestTrimCanonicalizer();
                    if (cls == TestUpperCanonicalizer.class) return new TestUpperCanonicalizer();
                    throw new IllegalArgumentException("Unknown canonicalizer: " + cls);
                },
                cls -> {
                    if (cls == TestStripControlsSanitizer.class) return new TestStripControlsSanitizer();
                    if (cls == TestPrefixSanitizer.class) return new TestPrefixSanitizer();
                    throw new IllegalArgumentException("Unknown sanitizer: " + cls);
                });
    }

    // --- Null / empty ---

    @Nested
    @DisplayName("null and empty inputs")
    class NullAndEmpty {

        @Test
        @DisplayName("null body returns null")
        void nullBodyReturnsNull() {
            Object result =
                    processor.processInput(null, EmptyDto.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            assertNull(result);
        }

        @Test
        @DisplayName("empty map returns empty map")
        void emptyMapReturnsEmptyMap() {
            Object result =
                    processor.processInput(Map.of(), EmptyDto.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            assertTrue(result instanceof Map);
            assertTrue(((Map<?, ?>) result).isEmpty());
        }
    }

    // --- Route-level processing ---

    @Nested
    @DisplayName("route-level chain processing")
    class RouteLevelProcessing {

        @Test
        @DisplayName("route-level canonicalizer is applied to all string values")
        void routeCanonicizerAppliesToAllStrings() {
            var policies = new EffectiveInputPolicies(List.of(TestTrimCanonicalizer.class), List.of());
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("name", "  hello  ");
            input.put("description", "  world  ");

            Object result = processor.processInput(input, EmptyDto.class, policies, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            assertEquals("hello", map.get("name"));
            assertEquals("world", map.get("description"));
        }

        @Test
        @DisplayName("route-level sanitizer is applied after canonicalizer")
        void routeSanitizerAppliedAfterCanonicalizer() {
            var policies = new EffectiveInputPolicies(
                    List.of(TestTrimCanonicalizer.class), List.of(TestPrefixSanitizer.class));
            Map<String, Object> input = Map.of("name", "  hello  ");

            Object result = processor.processInput(input, EmptyDto.class, policies, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            assertEquals("safe:hello", map.get("name"));
        }
    }

    // --- Field-level annotations ---

    @Nested
    @DisplayName("field-level chain processing")
    class FieldLevelProcessing {

        @Test
        @DisplayName("field-level @Canonicalize is applied to annotated field")
        void fieldCanonicalizerApplied() {
            Map<String, Object> input = Map.of("name", "  alice  ", "count", 42);

            Object result = processor.processInput(
                    input, FieldAnnotatedDto.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            assertEquals("alice", map.get("name"));
            assertEquals(42, map.get("count"));
        }

        @Test
        @DisplayName("field-level @Sanitize is applied to annotated field")
        void fieldSanitizerApplied() {
            Map<String, Object> input = Map.of("description", "hello\u0000world");

            Object result = processor.processInput(
                    input, FieldAnnotatedDto.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            assertEquals("helloworld", map.get("description"));
        }
    }

    // --- Object-level annotations ---

    @Test
    @DisplayName("object-level @Canonicalize is applied to all string fields")
    void objectLevelCanonicalizerAppliesToAllStringFields() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("a", "  foo  ");
        input.put("b", "  bar  ");

        Object result =
                processor.processInput(input, ObjectLevelDto.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
        Map<?, ?> map = (Map<?, ?>) result;
        assertEquals("foo", map.get("a"));
        assertEquals("bar", map.get("b"));
    }

    // --- Skip annotation ---

    @Nested
    @DisplayName("@Skip annotations")
    class SkipAnnotations {

        @Test
        @DisplayName("field with @SkipCanonicalization is not canonicalized even with route-level chain")
        void skipCanonicalizationOnFieldPreventsProcessing() {
            var policies = new EffectiveInputPolicies(List.of(TestTrimCanonicalizer.class), List.of());
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("raw", "  untouched  ");
            input.put("normal", "  touched  ");

            Object result = processor.processInput(input, SkipFieldDto.class, policies, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            assertEquals("  untouched  ", map.get("raw"));
            assertEquals("touched", map.get("normal"));
        }

        @Test
        @DisplayName("field with @SkipSanitization is not sanitized even with route-level sanitizer")
        void skipSanitizationOnFieldPreventsProcessing() {
            var policies = new EffectiveInputPolicies(List.of(), List.of(TestPrefixSanitizer.class));
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("raw", "data");
            input.put("normal", "data");

            Object result = processor.processInput(input, SkipSanitizeFieldDto.class, policies, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            assertEquals("data", map.get("raw")); // skip
            assertEquals("safe:data", map.get("normal")); // not skipped
        }
    }

    // --- Nested map ---

    @Test
    @DisplayName("nested map is processed recursively using nested type metadata")
    void nestedMapProcessedRecursively() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("name", "  alice  ");
        nested.put("description", "desc\u0000");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("outerName", "  outer  ");
        input.put("inner", nested);

        Object result = processor.processInput(input, OuterDto.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
        Map<?, ?> map = (Map<?, ?>) result;
        assertEquals("outer", map.get("outerName")); // outer has @Canonicalize on the field
        Map<?, ?> innerMap = (Map<?, ?>) map.get("inner");
        assertNotNull(innerMap);
        assertEquals("alice", innerMap.get("name")); // FieldAnnotatedDto.name has @Canonicalize
        assertEquals("descdesc".substring(0, 4), innerMap.get("description")); // stripped control char
    }

    // --- List processing ---

    @Test
    @DisplayName("List<String> values in map are processed by route-level chain")
    void listStringValuesAreProcessed() {
        var policies = new EffectiveInputPolicies(List.of(TestTrimCanonicalizer.class), List.of());
        List<Object> listValue = new ArrayList<>();
        listValue.add("  hello  ");
        listValue.add("  world  ");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tags", listValue);

        Object result = processor.processInput(input, EmptyDto.class, policies, InputLocation.BODY);
        Map<?, ?> map = (Map<?, ?>) result;
        List<?> tags = (List<?>) map.get("tags");
        assertEquals("hello", tags.get(0));
        assertEquals("world", tags.get(1));
    }

    // --- Non-string passthrough ---

    @Test
    @DisplayName("integer and boolean values pass through unchanged")
    void nonStringValuesPassThrough() {
        var policies = new EffectiveInputPolicies(List.of(TestTrimCanonicalizer.class), List.of());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("count", 42);
        input.put("active", true);

        Object result = processor.processInput(input, EmptyDto.class, policies, InputLocation.BODY);
        Map<?, ?> map = (Map<?, ?>) result;
        assertEquals(42, map.get("count"));
        assertEquals(true, map.get("active"));
    }

    // --- Null values in map ---

    @Test
    @DisplayName("null values in map are passed through as null")
    void nullMapValuesPassThrough() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", null);

        Object result = processor.processInput(input, EmptyDto.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
        Map<?, ?> map = (Map<?, ?>) result;
        assertTrue(map.containsKey("name"));
        assertNull(map.get("name"));
    }

    // --- Input immutability ---

    @Test
    @DisplayName("original input map is not mutated")
    void originalInputNotMutated() {
        var policies = new EffectiveInputPolicies(List.of(TestTrimCanonicalizer.class), List.of());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("name", "  hello  ");

        processor.processInput(input, EmptyDto.class, policies, InputLocation.BODY);
        assertEquals("  hello  ", input.get("name"));
    }

    // --- Fix 1: Top-level list body processing ---

    @Nested
    @DisplayName("top-level List body processing (Fix 1)")
    class TopLevelListBody {

        @Test
        @DisplayName("List<String> body: route-level canonicalizer is applied to each element")
        void listStringBodyProcessedWithRouteChain() {
            var policies = new EffectiveInputPolicies(List.of(TestTrimCanonicalizer.class), List.of());
            java.lang.reflect.ParameterizedType listType = new TestParameterizedType(List.class, String.class);
            List<Object> input = new ArrayList<>(List.of("  hello  ", "  world  "));

            Object result = processor.processInput(input, listType, policies, InputLocation.BODY);
            List<?> list = (List<?>) result;
            assertEquals("hello", list.get(0));
            assertEquals("world", list.get(1));
        }

        @Test
        @DisplayName("List<MyDto> body: field annotations on element type are applied to each element")
        void listDtoBodyProcessedWithFieldAnnotations() {
            java.lang.reflect.ParameterizedType listType =
                    new TestParameterizedType(List.class, FieldAnnotatedDto.class);
            Map<String, Object> elem1 = new LinkedHashMap<>();
            elem1.put("name", "  alice  ");
            elem1.put("description", "hello\u0000world");
            Map<String, Object> elem2 = new LinkedHashMap<>();
            elem2.put("name", "  bob  ");
            elem2.put("description", "clean");
            List<Object> input = new ArrayList<>(List.of(elem1, elem2));

            Object result = processor.processInput(input, listType, EffectiveInputPolicies.NONE, InputLocation.BODY);
            List<?> list = (List<?>) result;
            Map<?, ?> r1 = (Map<?, ?>) list.get(0);
            assertEquals("alice", r1.get("name")); // @Canonicalize(Trim) on name
            assertEquals("helloworld", r1.get("description")); // @Sanitize(StripControls) on description
            Map<?, ?> r2 = (Map<?, ?>) list.get(1);
            assertEquals("bob", r2.get("name"));
            assertEquals("clean", r2.get("description"));
        }
    }

    // --- Fix 3: Ancestor chain propagation and sticky skip ---

    @Nested
    @DisplayName("ancestor chain propagation (Fix 3)")
    class AncestorChainPropagation {

        @Test
        @DisplayName("root DTO @Canonicalize propagates into nested DTO string fields")
        void rootObjectCanonicalizerPropagatesIntoNestedDto() {
            // OuterWithObjectCanon has @Canonicalize(Trim) at the type level; InnerDto has plain strings
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("value", "  nested  ");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("inner", nested);

            Object result = processor.processInput(
                    input, OuterWithObjectCanon.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            Map<?, ?> innerMap = (Map<?, ?>) map.get("inner");
            assertNotNull(innerMap);
            assertEquals("nested", innerMap.get("value")); // trim propagated from root
        }

        @Test
        @DisplayName("field-level @Canonicalize on nested-object field propagates into the nested DTO")
        void fieldCanonicalizerPropagatesIntoNestedDto() {
            // OuterWithFieldCanon has @Canonicalize(Upper) on its 'inner' field; InnerDto has plain strings
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("value", "hello");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("inner", nested);

            Object result = processor.processInput(
                    input, OuterWithFieldCanon.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            Map<?, ?> innerMap = (Map<?, ?>) map.get("inner");
            assertNotNull(innerMap);
            assertEquals("HELLO", innerMap.get("value")); // upper propagated from field annotation
        }

        @Test
        @DisplayName("three levels deep: chains from all ancestors accumulate")
        void threeLevelsAccumulate() {
            // Root has @Canonicalize(Trim), middle has @Canonicalize(Upper) on its field holding leaf
            Map<String, Object> leaf = new LinkedHashMap<>();
            leaf.put("value", "  hello  ");
            Map<String, Object> middle = new LinkedHashMap<>();
            middle.put("leaf", leaf);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("middle", middle);

            Object result = processor.processInput(
                    input, ThreeLevelRoot.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            Map<?, ?> middleMap = (Map<?, ?>) map.get("middle");
            assertNotNull(middleMap);
            Map<?, ?> leafMap = (Map<?, ?>) middleMap.get("leaf");
            assertNotNull(leafMap);
            // Trim (from root) applied first, then Upper (from middle's field annotation)
            assertEquals("HELLO", leafMap.get("value"));
        }

        @Test
        @DisplayName("@SkipCanonicalization on root suppresses nested DTO's own @Canonicalize")
        void skipOnRootSuppressesNestedAnnotation() {
            // OuterSkipCanon has @SkipCanonicalization; InnerWithCanon has @Canonicalize(Trim)
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("value", "  untouched  ");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("inner", nested);

            Object result = processor.processInput(
                    input, OuterSkipCanon.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            Map<?, ?> innerMap = (Map<?, ?>) map.get("inner");
            assertNotNull(innerMap);
            // Sticky skip: root @SkipCanonicalization suppresses InnerWithCanon's @Canonicalize
            assertEquals("  untouched  ", innerMap.get("value"));
        }

        @Test
        @DisplayName("field-level @SkipCanonicalization on nested field suppresses that subtree")
        void skipOnFieldSuppressesSubtree() {
            // OuterWithSkipField has @SkipCanonicalization on 'inner' field; InnerWithCanon has @Canonicalize(Trim)
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("value", "  untouched  ");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("inner", nested);

            Object result = processor.processInput(
                    input, OuterWithSkipField.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            Map<?, ?> innerMap = (Map<?, ?>) map.get("inner");
            assertNotNull(innerMap);
            assertEquals("  untouched  ", innerMap.get("value"));
        }

        @Test
        @DisplayName("sticky skip: ancestor @SkipCanonicalization wins over nested @Canonicalize (counter-case)")
        void stickySkipWinsOverNestedChain() {
            // Route has Trim, but root has @SkipCanonicalization — sticky, so nested @Canonicalize(Upper) also
            // suppressed
            var policies = new EffectiveInputPolicies(List.of(TestTrimCanonicalizer.class), List.of());
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("value", "  hello  ");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("inner", nested);

            Object result = processor.processInput(input, OuterSkipCanon.class, policies, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            Map<?, ?> innerMap = (Map<?, ?>) map.get("inner");
            assertNotNull(innerMap);
            // Skip is sticky: neither route Trim nor nested Upper applied
            assertEquals("  hello  ", innerMap.get("value"));
        }
    }

    // --- Reflective continuation for opaque nested types ---

    @Nested
    @DisplayName("reflective continuation for a nested type with no generated processor")
    class ContinuationForOpaqueNestedType {

        @Test
        @DisplayName("@Sanitize'd field of an opaque declared type receives its chain when the wire value is a string")
        void opaqueNestedTypeStringValue_chainApplied() {
            // UriHolder has a hand-written companion processor whose "homepage" arm mirrors the
            // codegen NESTED_DTO shape: dispatchNested(v, URI.class, ...). No URI_InputProcessor
            // exists, so the dispatcher falls through to DefaultInputObjectProcessor.continueAt
            // with the raw string.
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("homepage", "example.test");

            Object result =
                    processor.processInput(input, UriHolder.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            assertEquals(
                    "safe:example.test",
                    map.get("homepage"),
                    "Field-level @Sanitize must survive the dispatch into an opaque nested type");
        }

        @Test
        @DisplayName("route-level chain composes ahead of the field chain on the continuation path")
        void opaqueNestedTypeStringValue_routeChainComposedFirst() {
            var policies = new EffectiveInputPolicies(List.of(TestTrimCanonicalizer.class), List.of());
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("homepage", "  example.test  ");

            Object result = processor.processInput(input, UriHolder.class, policies, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            assertEquals(
                    "safe:example.test",
                    map.get("homepage"),
                    "Route canonicalizer then field sanitizer, in that order");
        }

        @Test
        @DisplayName("non-string value on the continuation path passes through unchanged")
        void opaqueNestedTypeNonStringValue_unchanged() {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("homepage", 42);

            Object result =
                    processor.processInput(input, UriHolder.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            assertEquals(42, map.get("homepage"), "Non-string fragments are still returned unchanged");
        }
    }

    // --- Optional-wrapped nested DTOs on the reflective path ---

    @Nested
    @DisplayName("Optional-wrapped and bounded nested DTOs (reflective path)")
    class OptionalWrappedNestedDtos {

        @Test
        @DisplayName("Optional<Comment>: the nested type's own @Sanitize chain runs exactly once")
        void optionalNestedDto_nestedChainAppliedExactlyOnce() {
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("text", "ab");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("comment", nested);

            Object result = processor.processInput(
                    input, OptionalProfile.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            Map<?, ?> map = (Map<?, ?>) result;
            Map<?, ?> commentMap = (Map<?, ?>) map.get("comment");
            assertNotNull(commentMap);
            // Exactly one "safe:" marker — a double application would read "safe:safe:ab".
            assertEquals(
                    "safe:ab",
                    commentMap.get("text"),
                    "Optional<Comment> must resolve field metadata from Comment, applying its chain once");
        }

        @Test
        @DisplayName("Optional<? extends Comment>: the wildcard's upper bound supplies the nested metadata")
        void boundedOptionalNestedDto_nestedChainAppliedExactlyOnce() {
            Map<String, Object> nested = new LinkedHashMap<>();
            nested.put("text", "ab");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("comment", nested);

            Object result = processor.processInput(
                    input, BoundedOptionalProfile.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            Map<?, ?> map = (Map<?, ?>) result;
            Map<?, ?> commentMap = (Map<?, ?>) map.get("comment");
            assertNotNull(commentMap);
            assertEquals(
                    "safe:ab",
                    commentMap.get("text"),
                    "Optional<? extends Comment> must classify against the wildcard's upper bound");
        }

        @Test
        @DisplayName("List<? extends Comment>: each element's own @Sanitize chain runs exactly once")
        void boundedCollectionOfNestedDtos_nestedChainAppliedExactlyOnce() {
            Map<String, Object> first = new LinkedHashMap<>();
            first.put("text", "ab");
            Map<String, Object> second = new LinkedHashMap<>();
            second.put("text", "cd");
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("comments", new ArrayList<Object>(List.of(first, second)));

            Object result = processor.processInput(
                    input, BoundedCommentList.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            Map<?, ?> map = (Map<?, ?>) result;
            List<?> comments = (List<?>) map.get("comments");
            assertNotNull(comments);
            assertEquals("safe:ab", ((Map<?, ?>) comments.get(0)).get("text"));
            assertEquals("safe:cd", ((Map<?, ?>) comments.get(1)).get("text"));
        }

        @Test
        @DisplayName("List<?> field: no element schema, so inherited route chain still reaches string elements")
        void unboundedWildcardCollection_routeChainReachesStringElements() {
            var policies = new EffectiveInputPolicies(List.of(TestTrimCanonicalizer.class), List.of());
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("values", new ArrayList<Object>(List.of("  a  ", "  b  ")));

            Object result = processor.processInput(input, WildcardValueHolder.class, policies, InputLocation.BODY);

            Map<?, ?> map = (Map<?, ?>) result;
            assertEquals(
                    List.of("a", "b"),
                    map.get("values"),
                    "An Object-resolving element type must not be treated as a nested DTO schema — "
                            + "that would pass string elements through untouched");
        }
    }

    // --- Recursive and deeply nested DTOs ---

    @Nested
    @DisplayName("recursive and deeply nested DTOs")
    class RecursiveAndDeepDtos {

        @Test
        @DisplayName("self-referential DTO is sanitized at every level, not only the top")
        void shouldSanitizeEveryLevelOfASelfReferentialDto() {
            Map<String, Object> level4 = new LinkedHashMap<>();
            level4.put("name", "d");
            Map<String, Object> level3 = new LinkedHashMap<>();
            level3.put("name", "c");
            level3.put("child", level4);
            Map<String, Object> level2 = new LinkedHashMap<>();
            level2.put("name", "b");
            level2.put("child", level3);
            Map<String, Object> level1 = new LinkedHashMap<>();
            level1.put("name", "a");
            level1.put("child", level2);

            Object result =
                    processor.processInput(level1, SelfRefNode.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            assertEquals(
                    List.of("safe:a", "safe:b", "safe:c", "safe:d"),
                    namesAlongChain(result),
                    "SelfRefNode.name declares @Sanitize, so every level reached through the "
                            + "self-referential 'child' field must be sanitized — not just the top level");
        }

        @Test
        @DisplayName("a chain of twelve distinct types is sanitized at its deepest level")
        void shouldSanitizeBeyondTenNestingLevelsOfDistinctTypes() {
            Map<String, Object> deepest = new LinkedHashMap<>();
            deepest.put("name", "leaf");
            Map<String, Object> current = deepest;
            List<Map<String, Object>> levels = new ArrayList<>();
            levels.add(deepest);
            for (int level = 11; level >= 1; level--) {
                Map<String, Object> node = new LinkedHashMap<>();
                if (level == 1) {
                    node.put("name", "root");
                }
                node.put("child", current);
                levels.add(0, node);
                current = node;
            }

            // Control: entering the same chain at level 4 leaves nine links to the deepest level,
            // which is inside the resolver's ten-level budget — so this arm passes today and pins
            // the failure below to the depth budget rather than to the fixture.
            Object shallowResult = processor.processInput(
                    levels.get(3), Depth4.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            assertEquals(
                    "safe:leaf",
                    deepestName(shallowResult),
                    "control: the same chain entered nine levels above the leaf must be sanitized");

            Object result = processor.processInput(
                    levels.get(0), Depth1.class, EffectiveInputPolicies.NONE, InputLocation.BODY);

            Map<?, ?> map = (Map<?, ?>) result;
            assertEquals("safe:root", map.get("name"), "the root level's own @Sanitize must still run");
            assertEquals(
                    "safe:leaf",
                    deepestName(result),
                    "Depth12.name declares @Sanitize twelve levels below the root; traversal must "
                            + "terminate on the finite intermediate, not on a fixed type-graph depth budget");
        }

        /** Collects the {@code name} value of every level reachable through the {@code child} key. */
        private List<Object> namesAlongChain(Object result) {
            List<Object> names = new ArrayList<>();
            Map<?, ?> node = (Map<?, ?>) result;
            while (node != null) {
                names.add(node.get("name"));
                node = (Map<?, ?>) node.get("child");
            }
            return names;
        }

        /** Returns the {@code name} value of the deepest level reachable through {@code child}. */
        private Object deepestName(Object result) {
            Map<?, ?> node = (Map<?, ?>) result;
            while (node.get("child") != null) {
                node = (Map<?, ?>) node.get("child");
            }
            return node.get("name");
        }
    }

    // =========================================================================
    // Test DTOs
    // =========================================================================

    static class EmptyDto {
        String name;
        String description;
    }

    static class FieldAnnotatedDto {
        @Canonicalize(TestTrimCanonicalizer.class)
        String name;

        @Sanitize(TestStripControlsSanitizer.class)
        String description;

        int count;
    }

    @Canonicalize(TestTrimCanonicalizer.class)
    static class ObjectLevelDto {
        String a;
        String b;
    }

    static class SkipFieldDto {
        @SkipCanonicalization
        String raw;

        String normal;
    }

    static class SkipSanitizeFieldDto {
        @SkipSanitization
        String raw;

        String normal;
    }

    static class OuterDto {
        @Canonicalize(TestTrimCanonicalizer.class)
        String outerName;

        FieldAnnotatedDto inner;
    }

    /** Plain DTO with a single string field — no annotations. */
    static class InnerDto {
        String value;
    }

    /** DTO with a nested object field — no own object-level annotation. */
    @Canonicalize(TestTrimCanonicalizer.class)
    static class OuterWithObjectCanon {
        InnerDto inner;
    }

    /** DTO whose {@code inner} field carries {@code @Canonicalize(Upper)}. */
    static class OuterWithFieldCanon {
        @Canonicalize(TestUpperCanonicalizer.class)
        InnerDto inner;
    }

    /** Leaf DTO for three-level nesting tests. */
    static class LeafDto {
        String value;
    }

    /** Middle-level DTO: holds a {@code LeafDto} and annotates the field with Upper. */
    static class MiddleDto {
        @Canonicalize(TestUpperCanonicalizer.class)
        LeafDto leaf;
    }

    /** Root DTO for three-level test: carries Trim at object level, contains {@code MiddleDto}. */
    @Canonicalize(TestTrimCanonicalizer.class)
    static class ThreeLevelRoot {
        MiddleDto middle;
    }

    /** DTO with object-level annotated nested type. */
    @Canonicalize(TestTrimCanonicalizer.class)
    static class InnerWithCanon {
        String value;
    }

    /** Root DTO with {@code @SkipCanonicalization} — skip is sticky for all descendants. */
    @SkipCanonicalization
    static class OuterSkipCanon {
        InnerWithCanon inner;
    }

    /** Root DTO with {@code @SkipCanonicalization} on the nested-object field only. */
    static class OuterWithSkipField {
        @SkipCanonicalization
        InnerWithCanon inner;
    }

    /** Directly self-referential DTO — its {@code @Sanitize} chain must apply at every level. */
    static class SelfRefNode {
        @Sanitize(TestPrefixSanitizer.class)
        String name;

        SelfRefNode child;
    }

    // Twelve distinct types forming an acyclic chain deeper than the resolver's ten-level budget.
    // Only the outermost and the deepest level declare a policy: an annotated intermediate would
    // re-anchor resolution at its own level (each nested descent re-resolves from depth zero), so
    // the depth budget would never be observable.

    /** Level 1 of the twelve-level chain. */
    static class Depth1 {
        @Sanitize(TestPrefixSanitizer.class)
        String name;

        Depth2 child;
    }

    /** Level 2 of the twelve-level chain. */
    static class Depth2 {
        Depth3 child;
    }

    /** Level 3 of the twelve-level chain. */
    static class Depth3 {
        Depth4 child;
    }

    /** Level 4 of the twelve-level chain; also the control entry point. */
    static class Depth4 {
        Depth5 child;
    }

    /** Level 5 of the twelve-level chain. */
    static class Depth5 {
        Depth6 child;
    }

    /** Level 6 of the twelve-level chain. */
    static class Depth6 {
        Depth7 child;
    }

    /** Level 7 of the twelve-level chain. */
    static class Depth7 {
        Depth8 child;
    }

    /** Level 8 of the twelve-level chain. */
    static class Depth8 {
        Depth9 child;
    }

    /** Level 9 of the twelve-level chain. */
    static class Depth9 {
        Depth10 child;
    }

    /** Level 10 of the twelve-level chain. */
    static class Depth10 {
        Depth11 child;
    }

    /** Level 11 of the twelve-level chain. */
    static class Depth11 {
        Depth12 child;
    }

    /** Level 12 of the twelve-level chain — carries the deepest declared policy. */
    static class Depth12 {
        @Sanitize(TestPrefixSanitizer.class)
        String name;
    }

    /**
     * Nested DTO whose own {@code @Sanitize} chain must survive an {@code Optional} wrapper or a
     * bounded type argument on the enclosing field.
     */
    static class OptionalComment {
        @Sanitize(TestPrefixSanitizer.class)
        String text;
    }

    /**
     * DTO whose nested DTO is reachable only through {@code Optional}. Jackson's {@code Jdk8Module}
     * materializes the unwrapped {@link OptionalComment}, so the resolver must too.
     *
     * @param comment the optionally-present nested comment
     */
    record OptionalProfile(Optional<OptionalComment> comment) {}

    /**
     * DTO whose nested DTO sits behind a bounded wildcard inside {@code Optional}.
     *
     * @param comment the optionally-present nested comment, typed by an upper bound
     */
    record BoundedOptionalProfile(Optional<? extends OptionalComment> comment) {}

    /**
     * DTO whose nested DTOs are collection elements behind a bounded wildcard.
     *
     * @param comments the nested comments, typed by an upper bound
     */
    record BoundedCommentList(List<? extends OptionalComment> comments) {}

    /** DTO with an unbounded-wildcard collection — its element type carries no schema. */
    static class WildcardValueHolder {
        List<?> values;
    }

    /**
     * DTO whose {@code @Sanitize}d field has an opaque, string-backed declared type with no
     * generated processor. Paired with the hand-written
     * {@link DefaultInputObjectProcessorTest_UriHolder_InputProcessor} companion, this is the
     * shape that reaches {@code DefaultInputObjectProcessor.continueAt} with a raw string value.
     */
    static class UriHolder {
        @Sanitize(TestPrefixSanitizer.class)
        java.net.URI homepage;
    }

    // =========================================================================
    // Test helpers
    // =========================================================================

    /**
     * Minimal {@link java.lang.reflect.ParameterizedType} implementation for use in tests,
     * representing a generic type such as {@code List<String>} or {@code List<MyDto>}.
     *
     * @param rawType       the raw generic class (e.g. {@code List.class})
     * @param typeArguments the actual type arguments (e.g. {@code String.class})
     */
    record TestParameterizedType(Class<?> rawType, Class<?>... typeArguments)
            implements java.lang.reflect.ParameterizedType {

        @Override
        public java.lang.reflect.Type[] getActualTypeArguments() {
            return typeArguments;
        }

        @Override
        public java.lang.reflect.Type getRawType() {
            return rawType;
        }

        @Override
        public java.lang.reflect.Type getOwnerType() {
            return null;
        }
    }

    // =========================================================================
    // Test canonicalizer / sanitizer stubs
    // =========================================================================

    static class TestTrimCanonicalizer implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value == null ? null : value.strip();
        }
    }

    static class TestUpperCanonicalizer implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value == null ? null : value.toUpperCase();
        }
    }

    static class TestStripControlsSanitizer implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext context) {
            return value == null ? null : value.replaceAll("\\p{Cc}", "");
        }
    }

    static class TestPrefixSanitizer implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext context) {
            return value == null ? null : "safe:" + value;
        }
    }
}
