// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.validation;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for character policy contracts.
 *
 * <p>Covers factory methods and accessors for {@link CharacterPolicyResult}, record structure
 * for {@link CharacterPolicyBinding}, and annotation retention and targets for
 * {@link SkipAllowedCharacters}.
 */
class CharacterPolicyContractsTest {

    // --- CharacterPolicyResult ---

    @Nested
    @DisplayName("CharacterPolicyResult")
    class CharacterPolicyResultTest {

        @Test
        @DisplayName("passed() returns valid=true with null detail fields")
        void validFactoryReturnsValidResult() {
            CharacterPolicyResult result = CharacterPolicyResult.passed();
            assertTrue(result.valid());
            assertNull(result.invalidIndex());
            assertNull(result.invalidCodePoint());
            assertNull(result.reason());
        }

        @Test
        @DisplayName("failed(index, codePoint, reason) returns valid=false with correct fields")
        void invalidFactoryReturnsInvalidResult() {
            CharacterPolicyResult result = CharacterPolicyResult.failed(3, 0x1F600, "emoji not allowed");
            assertFalse(result.valid());
            assertEquals(3, result.invalidIndex());
            assertEquals(0x1F600, result.invalidCodePoint());
            assertEquals("emoji not allowed", result.reason());
        }

        @Test
        @DisplayName("passed() instances are equal")
        void validInstancesAreEqual() {
            assertEquals(CharacterPolicyResult.passed(), CharacterPolicyResult.passed());
        }

        @Test
        @DisplayName("failed() instances with same args are equal")
        void invalidInstancesWithSameArgsAreEqual() {
            CharacterPolicyResult a = CharacterPolicyResult.failed(0, 60, "angle bracket");
            CharacterPolicyResult b = CharacterPolicyResult.failed(0, 60, "angle bracket");
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("passed and failed are not equal")
        void validAndInvalidAreNotEqual() {
            assertNotEquals(CharacterPolicyResult.passed(), CharacterPolicyResult.failed(0, 60, "x"));
        }
    }

    // --- CharacterPolicyBinding ---

    @Nested
    @DisplayName("CharacterPolicyBinding record")
    class CharacterPolicyBindingTest {

        @Test
        @DisplayName("holds type and instance")
        void holdsTypeAndInstance() {
            CharacterPolicy policy = (value, ctx) -> CharacterPolicyResult.passed();
            CharacterPolicyBinding binding = new CharacterPolicyBinding(policy.getClass(), policy);
            assertSame(policy.getClass(), binding.type());
            assertSame(policy, binding.instance());
        }

        @Test
        @DisplayName("equals and hashCode are value-based")
        void equalityIsValueBased() {
            CharacterPolicy p = (value, ctx) -> CharacterPolicyResult.passed();
            CharacterPolicyBinding a = new CharacterPolicyBinding(p.getClass(), p);
            CharacterPolicyBinding b = new CharacterPolicyBinding(p.getClass(), p);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }
    }

    // --- @SkipAllowedCharacters ---

    @Nested
    @DisplayName("@SkipAllowedCharacters annotation")
    class SkipAllowedCharactersAnnotationTest {

        @Test
        @DisplayName("retention is RUNTIME")
        void retentionIsRuntime() {
            Retention retention = SkipAllowedCharacters.class.getAnnotation(Retention.class);
            assertNotNull(retention, "@SkipAllowedCharacters must have @Retention");
            assertEquals(RetentionPolicy.RUNTIME, retention.value());
        }

        @Test
        @DisplayName("targets are TYPE, FIELD, RECORD_COMPONENT, PARAMETER")
        void targetsAreCorrect() {
            Target target = SkipAllowedCharacters.class.getAnnotation(Target.class);
            assertNotNull(target, "@SkipAllowedCharacters must have @Target");
            Set<ElementType> actual = Set.of(target.value());
            Set<ElementType> expected =
                    Set.of(ElementType.TYPE, ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER);
            assertEquals(expected, actual);
        }

        @Test
        @DisplayName("is @Documented")
        void isDocumented() {
            assertNotNull(SkipAllowedCharacters.class.getAnnotation(java.lang.annotation.Documented.class));
        }
    }
}
