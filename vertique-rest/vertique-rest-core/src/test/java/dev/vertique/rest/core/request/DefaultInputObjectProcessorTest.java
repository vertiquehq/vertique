// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.rest.core.request;

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
            Object result = processor.processStructuredBody(
                    null, EmptyDto.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            assertNull(result);
        }

        @Test
        @DisplayName("empty map returns empty map")
        void emptyMapReturnsEmptyMap() {
            Object result = processor.processStructuredBody(
                    Map.of(), EmptyDto.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
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

            Object result = processor.processStructuredBody(input, EmptyDto.class, policies, InputLocation.BODY);
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

            Object result = processor.processStructuredBody(input, EmptyDto.class, policies, InputLocation.BODY);
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

            Object result = processor.processStructuredBody(
                    input, FieldAnnotatedDto.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            assertEquals("alice", map.get("name"));
            assertEquals(42, map.get("count"));
        }

        @Test
        @DisplayName("field-level @Sanitize is applied to annotated field")
        void fieldSanitizerApplied() {
            Map<String, Object> input = Map.of("description", "hello\u0000world");

            Object result = processor.processStructuredBody(
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

        Object result = processor.processStructuredBody(
                input, ObjectLevelDto.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
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

            Object result = processor.processStructuredBody(input, SkipFieldDto.class, policies, InputLocation.BODY);
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

            Object result =
                    processor.processStructuredBody(input, SkipSanitizeFieldDto.class, policies, InputLocation.BODY);
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

        Object result =
                processor.processStructuredBody(input, OuterDto.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
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

        Object result = processor.processStructuredBody(input, EmptyDto.class, policies, InputLocation.BODY);
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

        Object result = processor.processStructuredBody(input, EmptyDto.class, policies, InputLocation.BODY);
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

        Object result =
                processor.processStructuredBody(input, EmptyDto.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
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

        processor.processStructuredBody(input, EmptyDto.class, policies, InputLocation.BODY);
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

            Object result = processor.processStructuredBody(input, listType, policies, InputLocation.BODY);
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

            Object result =
                    processor.processStructuredBody(input, listType, EffectiveInputPolicies.NONE, InputLocation.BODY);
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

            Object result = processor.processStructuredBody(
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

            Object result = processor.processStructuredBody(
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

            Object result = processor.processStructuredBody(
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

            Object result = processor.processStructuredBody(
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

            Object result = processor.processStructuredBody(
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

            Object result = processor.processStructuredBody(input, OuterSkipCanon.class, policies, InputLocation.BODY);
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

            Object result = processor.processStructuredBody(
                    input, UriHolder.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
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

            Object result = processor.processStructuredBody(input, UriHolder.class, policies, InputLocation.BODY);
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

            Object result = processor.processStructuredBody(
                    input, UriHolder.class, EffectiveInputPolicies.NONE, InputLocation.BODY);
            Map<?, ?> map = (Map<?, ?>) result;
            assertEquals(42, map.get("homepage"), "Non-string fragments are still returned unchanged");
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
