// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.input.processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.util.List;
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
        @DisplayName("nested field has resolved nested metadata")
        void nestedFieldHasNestedMetadata() {
            InputPolicyMetadata meta = resolver.resolve(NestedDto.class);
            InputPolicyMetadata.FieldPolicyMetadata nestedField = meta.fields().get("nested");
            assertNotNull(nestedField, "expected 'nested' field metadata");
            assertNotNull(nestedField.nestedMetadata(), "expected nested metadata to be resolved");
            assertFalse(nestedField.nestedMetadata().fields().isEmpty());
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

    static class CollectionDto {
        @Canonicalize(TestTrimCanonicalizer.class)
        List<String> tags;

        List<SimpleDto> items;
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
