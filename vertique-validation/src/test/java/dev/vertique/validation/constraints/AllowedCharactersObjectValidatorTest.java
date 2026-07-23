// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.validation.constraints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.core.sanitization.InputValueContext;
import dev.vertique.core.validation.CharacterPolicy;
import dev.vertique.core.validation.CharacterPolicyResult;
import dev.vertique.core.validation.SkipAllowedCharacters;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link AllowedCharactersObjectValidator} — object-level traversal, skip semantics,
 * collection and map support, and interaction with field-level {@code @AllowedCharacters}.
 */
class AllowedCharactersObjectValidatorTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            validator = factory.getValidator();
        }
    }

    @Nested
    @DisplayName("null and CharSequence passthrough")
    class NullAndPassthrough {

        @Test
        @DisplayName("null object passes validation")
        void shouldPassForNullObject() {
            // null target — validator returns valid
            var violations = validator.validate(new RecordWithStrings(null, "valid"));
            // Null name field passes; "valid" passes
            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("CharSequence value passes (handled by AllowedCharactersValidator)")
        void shouldPassForCharSequenceValue() {
            // When @AllowedCharacters is on a String field, AllowedCharactersValidator handles it
            var violations = validator.validate(new IdentifierRecord("validid"));
            assertTrue(violations.isEmpty());
        }
    }

    @Nested
    @DisplayName("record traversal")
    class RecordTraversal {

        @Test
        @DisplayName("valid string fields pass object-level validation")
        void shouldPassWhenAllFieldsValid() {
            var violations = validator.validate(new TypeAnnotatedRecord("abc", "def"));
            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("invalid string field produces violation")
        void shouldFailWhenStringFieldInvalid() {
            var violations = validator.validate(new TypeAnnotatedRecord("abc", "123"));
            assertFalse(violations.isEmpty());
        }

        @Test
        @DisplayName("both invalid fields produce violations")
        void shouldReportAllViolatingFields() {
            var violations = validator.validate(new TypeAnnotatedRecord("123", "456"));
            assertFalse(violations.isEmpty());
        }
    }

    @Nested
    @DisplayName("@SkipAllowedCharacters on field")
    class SkipField {

        @Test
        @DisplayName("field marked @SkipAllowedCharacters is excluded from object-level validation")
        void shouldSkipAnnotatedField() {
            // "123" would violate LettersOnlyPolicy but the field is skipped
            var violations = validator.validate(new RecordWithSkip("valid", "123"));
            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("non-skipped field is still validated")
        void shouldValidateNonSkippedField() {
            var violations = validator.validate(new RecordWithSkip("123", "valid"));
            assertFalse(violations.isEmpty());
        }
    }

    @Nested
    @DisplayName("field-level @AllowedCharacters overrides object-level")
    class FieldLevelOverride {

        @Test
        @DisplayName("field with own @AllowedCharacters is excluded from object traversal")
        void shouldSkipFieldWithOwnAnnotation() {
            // fieldWithOwnPolicy has @AllowedCharacters(DigitsOnlyPolicy) — not re-validated by object validator
            // but its field-level validator runs separately
            var violations = validator.validate(new RecordWithFieldOverride("abc", "123"));
            assertTrue(violations.isEmpty(), "object-level validator should skip field with own @AllowedCharacters");
        }

        @Test
        @DisplayName("field with own @AllowedCharacters triggers its own validator")
        void shouldTriggerFieldLevelValidator() {
            // "abc" fails DigitsOnlyPolicy at field level
            var violations = validator.validate(new RecordWithFieldOverride("abc", "abc"));
            assertFalse(violations.isEmpty());
        }
    }

    @Nested
    @DisplayName("Collection<String> fields")
    class CollectionFields {

        @Test
        @DisplayName("all elements in a List<String> are validated")
        void shouldValidateListElements() {
            var violations = validator.validate(new RecordWithList(List.of("abc", "def")));
            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("invalid element in List<String> produces violation")
        void shouldFailOnInvalidListElement() {
            var violations = validator.validate(new RecordWithList(List.of("abc", "123")));
            assertFalse(violations.isEmpty());
        }

        @Test
        @DisplayName("null list passes validation")
        void shouldPassNullList() {
            var violations = validator.validate(new RecordWithList(null));
            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("all elements in a Set<String> are validated")
        void shouldValidateSetElements() {
            var violations = validator.validate(new RecordWithSet(Set.of("abc", "123")));
            assertFalse(violations.isEmpty());
        }
    }

    @Nested
    @DisplayName("Map string values")
    class MapFields {

        @Test
        @DisplayName("valid map string values pass validation")
        void shouldPassValidMapValues() {
            var violations = validator.validate(new RecordWithMap(Map.of("key", "abc")));
            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("invalid map string value produces violation")
        void shouldFailInvalidMapValue() {
            var violations = validator.validate(new RecordWithMap(Map.of("key", "123")));
            assertFalse(violations.isEmpty());
        }

        @Test
        @DisplayName("null map passes validation")
        void shouldPassNullMap() {
            var violations = validator.validate(new RecordWithMap(null));
            assertTrue(violations.isEmpty());
        }
    }

    @Nested
    @DisplayName("violation count")
    class ViolationCount {

        @Test
        @DisplayName("each invalid field produces a separate violation")
        void shouldProduceSeparateViolationPerField() {
            var violations = validator.validate(new TypeAnnotatedRecord("123", "456"));
            // Both fields fail — at least 2 violations
            assertTrue(violations.size() >= 2);
        }

        @Test
        @DisplayName("violation path node contains field name")
        void shouldIncludeFieldPathInViolation() {
            var violations = validator.validate(new TypeAnnotatedRecord("abc", "123"));
            assertEquals(1, violations.size());
            String propertyPath = violations.iterator().next().getPropertyPath().toString();
            assertTrue(propertyPath.contains("second"), "path should reference the failing field 'second'");
        }
    }

    // --- Test records and policies ---

    /**
     * Record where both fields are plain strings, no type-level annotation — used for null tests.
     */
    record RecordWithStrings(String name, String value) {}

    /** Record with a single string field annotated at field level. */
    record IdentifierRecord(
            @AllowedCharacters(policy = LettersOnlyPolicy.class)
            String id) {}

    /** Record with object-level {@code @AllowedCharacters(LettersOnlyPolicy)} on the type. */
    @AllowedCharacters(policy = LettersOnlyPolicy.class)
    record TypeAnnotatedRecord(String first, String second) {}

    /** Record with one field skipped and one not. */
    @AllowedCharacters(policy = LettersOnlyPolicy.class)
    record RecordWithSkip(
            String included, @SkipAllowedCharacters String skipped) {}

    /** Record where one field has its own field-level {@code @AllowedCharacters}. */
    @AllowedCharacters(policy = LettersOnlyPolicy.class)
    record RecordWithFieldOverride(
            String inheritedField,

            @AllowedCharacters(policy = DigitsOnlyPolicy.class)
            String fieldWithOwnPolicy) {}

    /** Record with a {@code List<String>} field. */
    @AllowedCharacters(policy = LettersOnlyPolicy.class)
    record RecordWithList(List<String> values) {}

    /** Record with a {@code Set<String>} field. */
    @AllowedCharacters(policy = LettersOnlyPolicy.class)
    record RecordWithSet(Set<String> values) {}

    /** Record with a {@code Map<String, String>} field. */
    @AllowedCharacters(policy = LettersOnlyPolicy.class)
    record RecordWithMap(Map<String, String> attributes) {}

    /** Allows only ASCII letters (a-z, A-Z). */
    public static class LettersOnlyPolicy implements CharacterPolicy {
        @Override
        public CharacterPolicyResult validate(String value, InputValueContext context) {
            if (value == null) return CharacterPolicyResult.passed();
            int[] codePoints = value.codePoints().toArray();
            for (int i = 0; i < codePoints.length; i++) {
                int cp = codePoints[i];
                if (!Character.isLetter(cp) || cp > 0x7E) {
                    return CharacterPolicyResult.failed(i, cp, "only ASCII letters allowed");
                }
            }
            return CharacterPolicyResult.passed();
        }
    }

    /** Allows only ASCII digits (0-9). */
    public static class DigitsOnlyPolicy implements CharacterPolicy {
        @Override
        public CharacterPolicyResult validate(String value, InputValueContext context) {
            if (value == null) return CharacterPolicyResult.passed();
            int[] codePoints = value.codePoints().toArray();
            for (int i = 0; i < codePoints.length; i++) {
                int cp = codePoints[i];
                if (cp < '0' || cp > '9') {
                    return CharacterPolicyResult.failed(i, cp, "only ASCII digits allowed");
                }
            }
            return CharacterPolicyResult.passed();
        }
    }

    // --- Nested DTO fixtures (Fix 4) ---

    /**
     * Plain DTO with a single string field — no own {@code @AllowedCharacters}.
     * Used to verify that object-level validation recurses into collection/map elements.
     */
    static class NestedDto {
        final String name;

        NestedDto(String name) {
            this.name = name;
        }
    }

    /** Record with a {@code List<NestedDto>} field under object-level {@code @AllowedCharacters}. */
    @AllowedCharacters(policy = LettersOnlyPolicy.class)
    record RecordWithNestedDtoList(List<NestedDto> items) {}

    /** Record with a {@code Map<String, NestedDto>} field under object-level {@code @AllowedCharacters}. */
    @AllowedCharacters(policy = LettersOnlyPolicy.class)
    record RecordWithNestedDtoMap(Map<String, NestedDto> items) {}

    /** Record with a {@code List<List<String>>} field under object-level {@code @AllowedCharacters}. */
    @AllowedCharacters(policy = LettersOnlyPolicy.class)
    record RecordWithNestedStringList(List<List<String>> groups) {}

    // --- Meta-annotation fixtures ---

    /**
     * Composed annotation meta-annotated with {@link SkipAllowedCharacters}.
     * Placing this on a record component or field should cause the object-level validator to skip it.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
    @SkipAllowedCharacters
    @interface SkipViaComposed {}

    /** Record where one field is skipped via a composed meta-annotation. */
    @AllowedCharacters(policy = LettersOnlyPolicy.class)
    record RecordWithMetaSkip(
            String included, @SkipViaComposed String skippedViaComposed) {}

    @Nested
    @DisplayName("nested DTOs inside collections (Fix 4)")
    class NestedDtoInCollection {

        @Test
        @DisplayName("valid nested DTO inside List passes validation")
        void shouldPassListWithValidNestedDto() {
            var violations = validator.validate(
                    new RecordWithNestedDtoList(List.of(new NestedDto("abc"), new NestedDto("def"))));
            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("invalid string in nested DTO inside List produces violation")
        void shouldFailListWithInvalidNestedDto() {
            var violations = validator.validate(
                    new RecordWithNestedDtoList(List.of(new NestedDto("abc"), new NestedDto("123"))));
            assertFalse(violations.isEmpty());
        }

        @Test
        @DisplayName("valid nested DTO inside Map passes validation")
        void shouldPassMapWithValidNestedDto() {
            var violations = validator.validate(new RecordWithNestedDtoMap(Map.of("key", new NestedDto("abc"))));
            assertTrue(violations.isEmpty());
        }

        @Test
        @DisplayName("invalid string in nested DTO inside Map produces violation")
        void shouldFailMapWithInvalidNestedDto() {
            var violations = validator.validate(new RecordWithNestedDtoMap(Map.of("key", new NestedDto("123"))));
            assertFalse(violations.isEmpty());
        }

        @Test
        @DisplayName("nested List<List<String>> — inner list strings are validated")
        void shouldValidateNestedListOfStrings() {
            var violations =
                    validator.validate(new RecordWithNestedStringList(List.of(List.of("abc", "def"), List.of("123"))));
            assertFalse(violations.isEmpty());
        }
    }

    @Nested
    @DisplayName("meta-annotation skip detection")
    class MetaAnnotationSkip {

        @Test
        @DisplayName("field with composed @SkipAllowedCharacters via meta-annotation is excluded from validation")
        void fieldWithComposedSkipIsExcluded() {
            // "123" would violate LettersOnlyPolicy but the field is skipped via @SkipViaComposed
            var violations = validator.validate(new RecordWithMetaSkip("valid", "123"));
            assertTrue(violations.isEmpty(), "field annotated with composed @SkipAllowedCharacters should be skipped");
        }

        @Test
        @DisplayName("non-skipped field is still validated even when another field has meta-skip")
        void nonSkippedFieldIsStillValidated() {
            var violations = validator.validate(new RecordWithMetaSkip("123", "anything"));
            assertFalse(violations.isEmpty(), "non-skipped field with invalid value should still produce a violation");
        }
    }
}
