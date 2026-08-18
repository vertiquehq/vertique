// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.Canonicalize;
import dev.vertique.core.sanitization.Canonicalizer;
import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.sanitization.Sanitize;
import dev.vertique.core.sanitization.Sanitizer;
import dev.vertique.core.sanitization.SkipCanonicalization;
import dev.vertique.core.sanitization.SkipSanitization;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InputPolicyMetadataResolver} — verifies annotation resolution
 * on plain classes, records, and nested types; conflict detection; and caching behavior.
 */
class InputPolicyMetadataResolverTest {

    private final InputPolicyMetadataResolver resolver = new InputPolicyMetadataResolver();

    // --- Plain class with field-level annotations ---

    @Nested
    @DisplayName("field-level annotations on regular class")
    class FieldLevelAnnotations {

        @Test
        @DisplayName("String field with @Canonicalize is resolved into field metadata")
        void canonicalizeFieldIsResolved() {
            InputPolicyMetadata meta = resolver.resolve(SimpleDto.class);
            InputPolicyMetadata.FieldPolicyMetadata field = meta.fields().get("name");
            assertNotNull(field, "expected 'name' field metadata");
            assertTrue(field.isStringType());
            assertEquals(List.of(TestTrimCanonicalizer.class), field.canonicalizerChain());
            assertTrue(field.sanitizerChain().isEmpty());
        }

        @Test
        @DisplayName("String field with @Sanitize is resolved into field metadata")
        void sanitizeFieldIsResolved() {
            InputPolicyMetadata meta = resolver.resolve(SimpleDto.class);
            InputPolicyMetadata.FieldPolicyMetadata field = meta.fields().get("description");
            assertNotNull(field, "expected 'description' field metadata");
            assertTrue(field.isStringType());
            assertEquals(List.of(TestStripControlsSanitizer.class), field.sanitizerChain());
            assertTrue(field.canonicalizerChain().isEmpty());
        }

        @Test
        @DisplayName("non-String field (int) does not appear in field metadata")
        void nonStringFieldIsSkipped() {
            InputPolicyMetadata meta = resolver.resolve(SimpleDto.class);
            assertFalse(meta.fields().containsKey("count"), "primitive int field should be excluded");
        }

        @Test
        @DisplayName("object-level chains are empty for class without type-level annotations")
        void objectLevelChainsEmptyWhenNoTypeAnnotation() {
            InputPolicyMetadata meta = resolver.resolve(SimpleDto.class);
            assertTrue(meta.objectCanonicalizerChain().isEmpty());
            assertTrue(meta.objectSanitizerChain().isEmpty());
        }
    }

    // --- Object-level annotations ---

    @Nested
    @DisplayName("object-level annotations on type")
    class ObjectLevelAnnotations {

        @Test
        @DisplayName("type-level @Canonicalize populates objectCanonicalizerChain")
        void typeLevelCanonicalize() {
            InputPolicyMetadata meta = resolver.resolve(ObjectLevelDto.class);
            assertEquals(List.of(TestNfkcCanonicalizer.class), meta.objectCanonicalizerChain());
        }

        @Test
        @DisplayName("string fields without their own annotation appear in fields map")
        void unlabelledStringFieldsArePresent() {
            InputPolicyMetadata meta = resolver.resolve(ObjectLevelDto.class);
            assertTrue(meta.fields().containsKey("a"));
            assertTrue(meta.fields().containsKey("b"));
        }
    }

    // --- Skip annotations ---

    @Nested
    @DisplayName("skip annotations")
    class SkipAnnotations {

        @Test
        @DisplayName("@SkipCanonicalization on field sets skipCanonicalization flag")
        void skipCanonicalizationFlagSet() {
            InputPolicyMetadata meta = resolver.resolve(SkipFieldDto.class);
            InputPolicyMetadata.FieldPolicyMetadata raw = meta.fields().get("raw");
            assertNotNull(raw);
            assertTrue(raw.skipCanonicalization());
        }

        @Test
        @DisplayName("non-skip field does not set skipCanonicalization flag")
        void normalFieldSkipFlagIsFalse() {
            InputPolicyMetadata meta = resolver.resolve(SkipFieldDto.class);
            InputPolicyMetadata.FieldPolicyMetadata normal = meta.fields().get("normal");
            assertNotNull(normal);
            assertFalse(normal.skipCanonicalization());
        }

        @Test
        @DisplayName("type-level @SkipSanitization sets skipSanitization on metadata")
        void typeLevelSkipSanitizationFlag() {
            InputPolicyMetadata meta = resolver.resolve(SkipTypeDto.class);
            assertTrue(meta.skipSanitization());
        }
    }

    // --- Nested DTO ---

    @Nested
    @DisplayName("nested DTO resolution")
    class NestedDtoResolution {

        @Test
        @DisplayName("nested field carries its declared type, whose metadata resolves separately")
        void nestedFieldCarriesItsDeclaredType() {
            InputPolicyMetadata meta = resolver.resolve(NestedDto.class);
            InputPolicyMetadata.FieldPolicyMetadata nestedField = meta.fields().get("nested");
            assertNotNull(nestedField, "expected 'nested' field metadata");
            assertEquals(
                    SimpleDto.class,
                    nestedField.fieldType(),
                    "the nested field must carry its declared type — that is what the walker resolves at descent");

            // The nested type's own policies are read back through the same per-type resolution the
            // walker performs when it descends, not from metadata embedded in the parent.
            assertFalse(resolver.resolve(nestedField.fieldType()).fields().isEmpty());
        }

        @Test
        @DisplayName("String field in outer type is resolved correctly")
        void outerStringFieldResolved() {
            InputPolicyMetadata meta = resolver.resolve(NestedDto.class);
            assertTrue(meta.fields().containsKey("name"));
        }
    }

    // --- Record type ---

    @Nested
    @DisplayName("record component annotation resolution")
    class RecordAnnotations {

        @Test
        @DisplayName("@Canonicalize on record component is resolved")
        void recordComponentCanonicalize() {
            InputPolicyMetadata meta = resolver.resolve(SimpleRecord.class);
            InputPolicyMetadata.FieldPolicyMetadata field = meta.fields().get("name");
            assertNotNull(field);
            assertEquals(List.of(TestTrimCanonicalizer.class), field.canonicalizerChain());
        }

        @Test
        @DisplayName("@SkipSanitization on record component sets flag")
        void recordComponentSkipSanitization() {
            InputPolicyMetadata meta = resolver.resolve(SimpleRecord.class);
            InputPolicyMetadata.FieldPolicyMetadata field = meta.fields().get("raw");
            assertNotNull(field);
            assertTrue(field.skipSanitization());
        }
    }

    // --- Conflict detection ---

    @Nested
    @DisplayName("conflicting annotation detection")
    class ConflictDetection {

        @Test
        @DisplayName("@Canonicalize + @SkipCanonicalization on same field throws IllegalStateException")
        void fieldCanonicalizationConflictThrows() {
            assertThrows(IllegalStateException.class, () -> resolver.resolve(ConflictingFieldDto.class));
        }

        @Test
        @DisplayName("@Sanitize + @SkipSanitization on same type throws IllegalStateException")
        void typeSanitizationConflictThrows() {
            assertThrows(IllegalStateException.class, () -> resolver.resolve(ConflictingTypeDto.class));
        }
    }

    // --- Empty DTO ---

    @Test
    @DisplayName("type with no annotations returns metadata with empty chains")
    void emptyDtoReturnsEmptyMetadata() {
        InputPolicyMetadata meta = resolver.resolve(EmptyDto.class);
        assertTrue(meta.objectCanonicalizerChain().isEmpty());
        assertTrue(meta.objectSanitizerChain().isEmpty());
        assertFalse(meta.skipCanonicalization());
        assertFalse(meta.skipSanitization());
    }

    // --- Caching ---

    @Test
    @DisplayName("same type returns the same metadata instance on subsequent calls")
    void sameTypeReturnsCachedInstance() {
        InputPolicyMetadata first = resolver.resolve(SimpleDto.class);
        InputPolicyMetadata second = resolver.resolve(SimpleDto.class);
        assertSame(first, second);
    }

    // --- Cycle detection ---

    @Test
    @DisplayName("self-referencing type does not cause a stack overflow")
    void selfReferencingTypeIsCycleSafe() {
        InputPolicyMetadata meta = resolver.resolve(SelfRefDto.class);
        assertNotNull(meta);
    }

    // --- Recursive type resolution ---

    @Nested
    @DisplayName("recursive type resolution")
    class RecursiveTypeResolution {

        @Test
        @DisplayName("self-referential type keeps the policies declared below the self-reference")
        void shouldResolveSelfReferentialTypeWithoutEmptyingNestedPolicies() {
            InputPolicyMetadata meta = resolver.resolve(Node.class);

            InputPolicyMetadata.FieldPolicyMetadata child = meta.fields().get("child");
            assertNotNull(
                    child,
                    "the self-referential 'child' field must be present in the resolved metadata — "
                            + "dropping it discards every policy declared below the self-reference, so the "
                            + "nested subtree is walked with no schema at all");
            assertEquals(Node.class, child.fieldType(), "'child' must carry its declared nested type");

            // The nested subtree's policies are read back through the same per-type resolution the
            // walker performs when it descends into 'child'.
            InputPolicyMetadata nested = resolver.resolve(child.fieldType());
            InputPolicyMetadata.FieldPolicyMetadata nestedName = nested.fields().get("name");
            assertNotNull(nestedName, "the nested level must still expose the 'name' field");
            assertEquals(
                    List.of(TestStripControlsSanitizer.class),
                    nestedName.sanitizerChain(),
                    "the nested level must still carry the @Sanitize chain declared on 'name'");
        }

        @Test
        @DisplayName("direct A->A and mutual A->B->A recursion resolve to the same metadata shape")
        void shouldTreatDirectAndMutualRecursionIdentically() {
            String direct = shapeOf(DirectRecursive.class, 3);
            String mutual = shapeOf(MutualA.class, 3);

            assertEquals(
                    direct,
                    mutual,
                    "direct and mutual recursion declare equivalent policies at equivalent depths, so "
                            + "their resolved metadata must be shape-equivalent; a difference means "
                            + "resolution is path-dependent rather than per-type");
        }

        /**
         * Renders the resolved metadata of {@code type} as a type-name-free structural string:
         * object-level chains and skip flags, then each field's chains, skip flags, and — for
         * non-string fields — the shape of its declared nested type, resolved the way the walker
         * resolves it at descent. {@code remainingDepth} bounds the expansion of recursive graphs.
         *
         * @param type           the type to render
         * @param remainingDepth how many further nested levels to expand
         * @return the structural shape string
         */
        private String shapeOf(Class<?> type, int remainingDepth) {
            InputPolicyMetadata meta = resolver.resolve(type);
            StringBuilder shape = new StringBuilder("type[canon=")
                    .append(simpleNames(meta.objectCanonicalizerChain()))
                    .append(",sanit=")
                    .append(simpleNames(meta.objectSanitizerChain()))
                    .append(",skipCanon=")
                    .append(meta.skipCanonicalization())
                    .append(",skipSanit=")
                    .append(meta.skipSanitization())
                    .append("]{");
            for (Map.Entry<String, InputPolicyMetadata.FieldPolicyMetadata> entry :
                    meta.fields().entrySet()) {
                InputPolicyMetadata.FieldPolicyMetadata field = entry.getValue();
                shape.append(entry.getKey())
                        .append("[canon=")
                        .append(simpleNames(field.canonicalizerChain()))
                        .append(",sanit=")
                        .append(simpleNames(field.sanitizerChain()))
                        .append(",skipCanon=")
                        .append(field.skipCanonicalization())
                        .append(",skipSanit=")
                        .append(field.skipSanitization())
                        .append("]");
                Class<?> fieldType = field.fieldType();
                if (fieldType != null && fieldType != String.class) {
                    shape.append("->").append(remainingDepth > 0 ? shapeOf(fieldType, remainingDepth - 1) : "(elided)");
                }
                shape.append(" ");
            }
            return shape.append("}").toString();
        }

        private List<String> simpleNames(List<? extends Class<?>> classes) {
            return classes.stream().map(Class::getSimpleName).toList();
        }
    }

    // --- Meta-annotation resolution ---

    @Nested
    @DisplayName("meta-annotation resolution")
    class MetaAnnotationResolution {

        @Test
        @DisplayName("record field with composed @NormalizedInput resolves @Canonicalize chain")
        void recordFieldWithComposedAnnotationResolvesCanonicalizerChain() {
            InputPolicyMetadata meta = resolver.resolve(MetaAnnotatedRecord.class);
            InputPolicyMetadata.FieldPolicyMetadata field = meta.fields().get("name");
            assertNotNull(field, "expected 'name' field metadata");
            assertEquals(
                    List.of(TestTrimCanonicalizer.class),
                    field.canonicalizerChain(),
                    "canonicalizer chain should be resolved via @NormalizedInput meta-annotation");
        }

        @Test
        @DisplayName("plain class field with composed @NormalizedInput resolves @Canonicalize chain")
        void classFieldWithComposedAnnotationResolvesCanonicalizerChain() {
            InputPolicyMetadata meta = resolver.resolve(MetaAnnotatedDto.class);
            InputPolicyMetadata.FieldPolicyMetadata field = meta.fields().get("name");
            assertNotNull(field, "expected 'name' field metadata");
            assertEquals(
                    List.of(TestTrimCanonicalizer.class),
                    field.canonicalizerChain(),
                    "canonicalizer chain should be resolved via @NormalizedInput meta-annotation");
        }

        @Test
        @DisplayName("type with composed @NormalizedInput resolves object-level canonicalizer chain")
        void typeWithComposedAnnotationResolvesObjectCanonicalizerChain() {
            InputPolicyMetadata meta = resolver.resolve(MetaAnnotatedTypeDto.class);
            assertEquals(
                    List.of(TestTrimCanonicalizer.class),
                    meta.objectCanonicalizerChain(),
                    "object canonicalizer chain should be resolved via type-level @NormalizedInput");
        }

        @Test
        @DisplayName("record field with composed @SkipProcessing sets skipCanonicalization flag")
        void recordFieldWithComposedSkipAnnotationSetsSkipFlag() {
            InputPolicyMetadata meta = resolver.resolve(MetaSkipRecord.class);
            InputPolicyMetadata.FieldPolicyMetadata field = meta.fields().get("raw");
            assertNotNull(field, "expected 'raw' field metadata");
            assertTrue(
                    field.skipCanonicalization(),
                    "skipCanonicalization flag should be set via @SkipProcessing meta-annotation");
        }
    }

    // --- Collection fields ---

    @Test
    @DisplayName("List<String> field is recognized as isCollectionOfStrings")
    void listStringFieldIsRecognized() {
        InputPolicyMetadata meta = resolver.resolve(CollectionDto.class);
        InputPolicyMetadata.FieldPolicyMetadata field = meta.fields().get("tags");
        assertNotNull(field);
        assertTrue(field.isCollectionOfStrings());
    }

    @Test
    @DisplayName("List<SimpleDto> field has collectionElementType set")
    void listObjectFieldHasElementType() {
        InputPolicyMetadata meta = resolver.resolve(CollectionDto.class);
        InputPolicyMetadata.FieldPolicyMetadata field = meta.fields().get("items");
        assertNotNull(field);
        assertFalse(field.isCollectionOfStrings());
        assertEquals(SimpleDto.class, field.collectionElementType());
    }

    // --- Nested containers carry no element schema ---

    @Nested
    @DisplayName("nested container element classification")
    class NestedContainerElements {

        @Test
        @DisplayName("List<List<String>> records no collection element type")
        void nestedListRecordsNoElementType() {
            InputPolicyMetadata meta = resolver.resolve(NestedContainerDto.class);
            assertNull(
                    meta.fields().get("rows"),
                    "an element type that is itself a container carries no property set to dispatch "
                            + "at, so the unannotated field must fall out of the metadata entirely "
                            + "and take the inherited-chain path");
        }

        @Test
        @DisplayName("Set<List<Tag>> records no collection element type")
        void nestedListInSetRecordsNoElementType() {
            InputPolicyMetadata meta = resolver.resolve(NestedContainerDto.class);
            assertNull(meta.fields().get("tagGroups"), "a Set of List has no single element schema either");
        }

        @Test
        @DisplayName("an annotated List<List<String>> keeps its own chain but records no element type")
        void annotatedNestedListKeepsChainWithoutElementType() {
            InputPolicyMetadata meta = resolver.resolve(NestedContainerDto.class);
            InputPolicyMetadata.FieldPolicyMetadata field = meta.fields().get("annotatedRows");
            assertNotNull(field, "a field declaring its own chain stays in the metadata");
            assertNull(
                    field.collectionElementType(),
                    "the inner List must not be recorded as an element type — element-wise dispatch "
                            + "would return each inner list verbatim and drop the declared chain");
            assertFalse(field.isCollectionOfStrings(), "the elements are lists, not strings");
            assertEquals(List.of(TestTrimCanonicalizer.class), field.canonicalizerChain());
        }

        @Test
        @DisplayName("List<Map<String, String>> records no collection element type")
        void listOfMapsRecordsNoElementType() {
            InputPolicyMetadata meta = resolver.resolve(NestedContainerDto.class);
            assertNull(
                    meta.fields().get("lookups"),
                    "a Map element has arbitrary keys and no statically known property set, exactly "
                            + "as a Map field type is not descendable");
        }
    }

    // --- declaresPolicies agrees with what the walker can actually reach ---

    @Nested
    @DisplayName("declaresPolicies element-type gating")
    class DeclaresPoliciesElementGating {

        @Test
        @DisplayName("a single-argument non-container generic type is not treated as a container")
        void singleArgumentNonContainerIsNotAContainer() throws NoSuchFieldException {
            Type wrapperType =
                    WrapperHolderDto.class.getDeclaredField("wrapper").getGenericType();

            assertFalse(
                    InputPolicyMetadataResolver.declaresPolicies(wrapperType),
                    "Wrapper<PolicyDto> is not a Collection, so its type argument is not an element "
                            + "type; the walker classifies Wrapper's own field to its Object bound and "
                            + "never reaches PolicyDto, and the startup gate must agree with it");
        }

        @Test
        @DisplayName("a genuine collection type argument is still reached")
        void collectionElementIsStillReached() throws NoSuchFieldException {
            Type listType = WrapperHolderDto.class.getDeclaredField("policies").getGenericType();

            assertTrue(
                    InputPolicyMetadataResolver.declaresPolicies(listType),
                    "fixture guard: the raw-type guard must not stop a real Collection element type "
                            + "from being enqueued");
        }
    }

    // --- Static and synthetic fields are not properties ---

    @Nested
    @DisplayName("static and synthetic field filtering")
    class StaticAndSyntheticFields {

        @Test
        @DisplayName("a static field is not resolved into field metadata")
        void staticFieldIsNotResolved() {
            InputPolicyMetadata meta = resolver.resolve(StaticFieldDto.class);

            assertNull(
                    meta.fields().get("log"),
                    "a static field is not a wire property; recording one makes declaresPolicies walk "
                            + "an unrelated type's graph and lets a static's type trip the startup gate "
                            + "for a policy no request path can reach");
            assertNotNull(meta.fields().get("name"), "fixture guard: the instance field is still resolved");
        }

        @Test
        @DisplayName("a non-static inner class's synthetic enclosing-instance field is not resolved")
        void syntheticFieldIsNotResolved() {
            assertTrue(
                    Arrays.stream(InnerMemberDto.class.getDeclaredFields())
                            .anyMatch(java.lang.reflect.Field::isSynthetic),
                    "fixture guard: InnerMemberDto must actually carry a synthetic field, or this test "
                            + "proves nothing — javac elides this$0 when the enclosing instance is unused");

            InputPolicyMetadata meta = resolver.resolve(InnerMemberDto.class);

            assertTrue(
                    meta.fields().keySet().stream().noneMatch(name -> name.startsWith("this$")),
                    "the synthetic enclosing-instance reference points metadata back at the enclosing "
                            + "class and is reachable under a crafted wire key; it must be filtered out. "
                            + "Resolved fields were: " + meta.fields().keySet());
            assertNotNull(meta.fields().get("label"), "fixture guard: the declared field is still resolved");
        }
    }

    // =========================================================================
    // Test DTOs
    // =========================================================================

    static class SimpleDto {
        @Canonicalize(TestTrimCanonicalizer.class)
        String name;

        @Sanitize(TestStripControlsSanitizer.class)
        String description;

        int count;
    }

    @Canonicalize(TestNfkcCanonicalizer.class)
    static class ObjectLevelDto {
        String a;
        String b;
    }

    static class SkipFieldDto {
        @SkipCanonicalization
        String raw;

        String normal;
    }

    @SkipSanitization
    static class SkipTypeDto {
        String value;
    }

    static class NestedDto {
        String name;
        SimpleDto nested;
    }

    record SimpleRecord(
            @Canonicalize(TestTrimCanonicalizer.class) String name,
            @SkipSanitization String raw) {}

    static class ConflictingFieldDto {
        @Canonicalize(TestTrimCanonicalizer.class)
        @SkipCanonicalization
        String bad;
    }

    @Sanitize(TestStripControlsSanitizer.class)
    @SkipSanitization
    static class ConflictingTypeDto {
        String value;
    }

    static class EmptyDto {
        String noAnnotations;
        int count;
    }

    static class SelfRefDto {
        String name;
        SelfRefDto self;
    }

    /**
     * Directly self-referential record: the policy on {@code name} must remain visible for the
     * nested {@code child} level, not only for the top level.
     *
     * @param name  the policy-carrying string component
     * @param child the self-reference
     */
    record Node(@Sanitize(TestStripControlsSanitizer.class) String name, Node child) {}

    /**
     * Direct {@code A -> A} recursion — the shape-equivalence baseline for {@link MutualA}.
     *
     * @param name  the policy-carrying string component
     * @param child the self-reference
     */
    record DirectRecursive(
            @Sanitize(TestStripControlsSanitizer.class) String name, DirectRecursive child) {}

    /**
     * Mutual {@code A -> B -> A} recursion, declaring the same policies at the same depths as
     * {@link DirectRecursive}.
     *
     * @param name  the policy-carrying string component
     * @param child the reference to the other half of the cycle
     */
    record MutualA(
            @Sanitize(TestStripControlsSanitizer.class) String name, MutualB child) {}

    /**
     * The other half of the {@link MutualA} cycle.
     *
     * @param name  the policy-carrying string component
     * @param child the reference back to {@link MutualA}
     */
    record MutualB(
            @Sanitize(TestStripControlsSanitizer.class) String name, MutualA child) {}

    static class CollectionDto {
        @Canonicalize(TestTrimCanonicalizer.class)
        List<String> tags;

        List<SimpleDto> items;
    }

    /** Leaf DTO used as the innermost element of a doubly-nested container field. */
    static class TagDto {
        String label;
    }

    /**
     * DTO whose fields are containers whose element type is itself a container — a nested
     * {@code List}, a {@code Set} of {@code List}, and a {@code List} of {@code Map}. None carries
     * a single element schema the walker could dispatch at.
     */
    static class NestedContainerDto {
        List<List<String>> rows;

        @Canonicalize(TestTrimCanonicalizer.class)
        List<List<String>> annotatedRows;

        Set<List<TagDto>> tagGroups;

        List<Map<String, String>> lookups;
    }

    /** DTO carrying a declared policy, used as the type argument the startup gate must not reach. */
    static class PolicyDto {
        @Canonicalize(TestTrimCanonicalizer.class)
        String value;
    }

    /**
     * Single-type-argument generic that is <em>not</em> a container: its property is typed by the
     * type variable, so the walker classifies it to the variable's {@code Object} bound and stops.
     *
     * @param <T> the wrapped type, erased to {@code Object} in the field signature
     */
    static class Wrapper<T> {
        T value;
    }

    /** Holder supplying real reflective {@link Type}s for the gate tests. */
    static class WrapperHolderDto {
        Wrapper<PolicyDto> wrapper;

        List<PolicyDto> policies;
    }

    /**
     * DTO with a {@code @Slf4j}-style static field alongside a real property. The static field is
     * not part of the wire shape and must not become field metadata.
     */
    static class StaticFieldDto {
        static final PolicyDto log = new PolicyDto();

        String name;
    }

    /**
     * Deliberately non-static member class that <em>uses</em> its enclosing instance, so javac
     * synthesizes the {@code this$0} back-reference. Since JDK 18 javac elides that field when the
     * inner class never reads the enclosing instance, so {@link #enclosingResolver()} is what makes
     * the fixture actually carry a synthetic field.
     */
    class InnerMemberDto {
        String label;

        /**
         * Reads the enclosing instance, forcing javac to emit the synthetic {@code this$0} field.
         *
         * @return the enclosing test's resolver
         */
        InputPolicyMetadataResolver enclosingResolver() {
            return resolver;
        }
    }

    // --- Meta-annotation DTOs ---

    /** Composed annotation: meta-annotated with {@code @Canonicalize(TestTrimCanonicalizer.class)}. */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target({
        java.lang.annotation.ElementType.FIELD,
        java.lang.annotation.ElementType.TYPE,
        java.lang.annotation.ElementType.RECORD_COMPONENT
    })
    @Canonicalize(TestTrimCanonicalizer.class)
    @interface NormalizedInput {}

    /** Composed annotation: meta-annotated with {@code @SkipCanonicalization}. */
    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target({
        java.lang.annotation.ElementType.FIELD,
        java.lang.annotation.ElementType.TYPE,
        java.lang.annotation.ElementType.RECORD_COMPONENT
    })
    @SkipCanonicalization
    @interface SkipProcessing {}

    /** Record with a composed {@code @NormalizedInput} annotation on a field. */
    record MetaAnnotatedRecord(@NormalizedInput String name, String other) {}

    /** Plain class with a composed {@code @NormalizedInput} annotation on a field. */
    static class MetaAnnotatedDto {
        @NormalizedInput
        String name;

        String other;
    }

    /** Plain class with type-level composed {@code @NormalizedInput}. */
    @NormalizedInput
    static class MetaAnnotatedTypeDto {
        String a;
        String b;
    }

    /** Record with a composed {@code @SkipProcessing} annotation (resolves to {@code @SkipCanonicalization}). */
    record MetaSkipRecord(@SkipProcessing String raw, String normal) {}

    // =========================================================================
    // Test canonicalizer / sanitizer stubs
    // =========================================================================

    static class TestTrimCanonicalizer implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value == null ? null : value.strip();
        }
    }

    static class TestNfkcCanonicalizer implements Canonicalizer {
        @Override
        public String canonicalize(String value, InputValueContext context) {
            return value;
        }
    }

    static class TestStripControlsSanitizer implements Sanitizer {
        @Override
        public String sanitize(String value, InputValueContext context) {
            return value == null ? null : value.replaceAll("\\p{Cc}", "");
        }
    }
}
