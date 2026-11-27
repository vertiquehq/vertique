// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.sanitization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the sanitization vocabulary.
 *
 * <p>Covers annotation retention and targets for {@link Canonicalize}, {@link Sanitize},
 * {@link SkipCanonicalization}, and {@link SkipSanitization}, and record structure for
 * {@link CanonicalizerBinding} and {@link SanitizerBinding}.
 */
class SanitizationContractsTest {

    // --- @Canonicalize ---

    @Nested
    @DisplayName("@Canonicalize annotation")
    class CanonicalizeAnnotationTest {

        @Test
        @DisplayName("retention is RUNTIME")
        void retentionIsRuntime() {
            Retention retention = Canonicalize.class.getAnnotation(Retention.class);
            assertNotNull(retention, "@Canonicalize must have @Retention");
            assertEquals(RetentionPolicy.RUNTIME, retention.value());
        }

        @Test
        @DisplayName("targets are TYPE, FIELD, RECORD_COMPONENT, PARAMETER, METHOD, ANNOTATION_TYPE")
        void targetsAreCorrect() {
            Target target = Canonicalize.class.getAnnotation(Target.class);
            assertNotNull(target, "@Canonicalize must have @Target");
            Set<ElementType> actual = Set.of(target.value());
            Set<ElementType> expected = Set.of(
                    ElementType.TYPE,
                    ElementType.FIELD,
                    ElementType.RECORD_COMPONENT,
                    ElementType.PARAMETER,
                    ElementType.METHOD,
                    ElementType.ANNOTATION_TYPE);
            assertEquals(expected, actual);
        }

        @Test
        @DisplayName("is @Documented")
        void isDocumented() {
            assertNotNull(Canonicalize.class.getAnnotation(java.lang.annotation.Documented.class));
        }
    }

    // --- @Sanitize ---

    @Nested
    @DisplayName("@Sanitize annotation")
    class SanitizeAnnotationTest {

        @Test
        @DisplayName("retention is RUNTIME")
        void retentionIsRuntime() {
            Retention retention = Sanitize.class.getAnnotation(Retention.class);
            assertNotNull(retention, "@Sanitize must have @Retention");
            assertEquals(RetentionPolicy.RUNTIME, retention.value());
        }

        @Test
        @DisplayName("targets are TYPE, FIELD, RECORD_COMPONENT, PARAMETER, METHOD, ANNOTATION_TYPE")
        void targetsAreCorrect() {
            Target target = Sanitize.class.getAnnotation(Target.class);
            assertNotNull(target, "@Sanitize must have @Target");
            Set<ElementType> actual = Set.of(target.value());
            Set<ElementType> expected = Set.of(
                    ElementType.TYPE,
                    ElementType.FIELD,
                    ElementType.RECORD_COMPONENT,
                    ElementType.PARAMETER,
                    ElementType.METHOD,
                    ElementType.ANNOTATION_TYPE);
            assertEquals(expected, actual);
        }

        @Test
        @DisplayName("is @Documented")
        void isDocumented() {
            assertNotNull(Sanitize.class.getAnnotation(java.lang.annotation.Documented.class));
        }
    }

    // --- @SkipCanonicalization ---

    @Nested
    @DisplayName("@SkipCanonicalization annotation")
    class SkipCanonicalizationAnnotationTest {

        @Test
        @DisplayName("retention is RUNTIME")
        void retentionIsRuntime() {
            Retention retention = SkipCanonicalization.class.getAnnotation(Retention.class);
            assertNotNull(retention, "@SkipCanonicalization must have @Retention");
            assertEquals(RetentionPolicy.RUNTIME, retention.value());
        }

        @Test
        @DisplayName("targets are TYPE, FIELD, RECORD_COMPONENT, PARAMETER, METHOD")
        void targetsAreCorrect() {
            Target target = SkipCanonicalization.class.getAnnotation(Target.class);
            assertNotNull(target, "@SkipCanonicalization must have @Target");
            Set<ElementType> actual = Set.of(target.value());
            Set<ElementType> expected = Set.of(
                    ElementType.TYPE,
                    ElementType.FIELD,
                    ElementType.RECORD_COMPONENT,
                    ElementType.PARAMETER,
                    ElementType.METHOD);
            assertEquals(expected, actual);
        }

        @Test
        @DisplayName("is @Documented")
        void isDocumented() {
            assertNotNull(SkipCanonicalization.class.getAnnotation(java.lang.annotation.Documented.class));
        }
    }

    // --- @SkipSanitization ---

    @Nested
    @DisplayName("@SkipSanitization annotation")
    class SkipSanitizationAnnotationTest {

        @Test
        @DisplayName("retention is RUNTIME")
        void retentionIsRuntime() {
            Retention retention = SkipSanitization.class.getAnnotation(Retention.class);
            assertNotNull(retention, "@SkipSanitization must have @Retention");
            assertEquals(RetentionPolicy.RUNTIME, retention.value());
        }

        @Test
        @DisplayName("targets are TYPE, FIELD, RECORD_COMPONENT, PARAMETER, METHOD")
        void targetsAreCorrect() {
            Target target = SkipSanitization.class.getAnnotation(Target.class);
            assertNotNull(target, "@SkipSanitization must have @Target");
            Set<ElementType> actual = Set.of(target.value());
            Set<ElementType> expected = Set.of(
                    ElementType.TYPE,
                    ElementType.FIELD,
                    ElementType.RECORD_COMPONENT,
                    ElementType.PARAMETER,
                    ElementType.METHOD);
            assertEquals(expected, actual);
        }

        @Test
        @DisplayName("is @Documented")
        void isDocumented() {
            assertNotNull(SkipSanitization.class.getAnnotation(java.lang.annotation.Documented.class));
        }
    }

    // --- CanonicalizerBinding ---

    @Nested
    @DisplayName("CanonicalizerBinding record")
    class CanonicalizerBindingTest {

        @Test
        @DisplayName("holds type and instance")
        void holdsTypeAndInstance() {
            Canonicalizer instance = (value, ctx) -> value.trim();
            CanonicalizerBinding binding = new CanonicalizerBinding(instance.getClass(), instance);
            assertSame(instance.getClass(), binding.type());
            assertSame(instance, binding.instance());
        }

        @Test
        @DisplayName("equals and hashCode are value-based")
        void equalityIsValueBased() {
            Canonicalizer c = (value, ctx) -> value;
            CanonicalizerBinding a = new CanonicalizerBinding(c.getClass(), c);
            CanonicalizerBinding b = new CanonicalizerBinding(c.getClass(), c);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }
    }

    // --- SanitizerBinding ---

    @Nested
    @DisplayName("SanitizerBinding record")
    class SanitizerBindingTest {

        @Test
        @DisplayName("holds type and instance")
        void holdsTypeAndInstance() {
            Sanitizer instance = (value, ctx) -> value.strip();
            SanitizerBinding binding = new SanitizerBinding(instance.getClass(), instance);
            assertSame(instance.getClass(), binding.type());
            assertSame(instance, binding.instance());
        }

        @Test
        @DisplayName("equals and hashCode are value-based")
        void equalityIsValueBased() {
            Sanitizer s = (value, ctx) -> value;
            SanitizerBinding a = new SanitizerBinding(s.getClass(), s);
            SanitizerBinding b = new SanitizerBinding(s.getClass(), s);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }
    }
}
