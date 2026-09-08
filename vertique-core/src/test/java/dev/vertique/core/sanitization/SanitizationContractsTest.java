// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.sanitization;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the sanitization contracts package.
 *
 * <p>Covers annotation retention and targets for {@link Canonicalize}, {@link Sanitize},
 * {@link SkipCanonicalization}, and {@link SkipSanitization}; record equality and accessors
 * for {@link InputValueContext}; {@link InputLocation} enum values; the frozen member ledger of
 * {@link InputFieldNameResolver}; and record structure for
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

    // --- InputLocation ---

    @Nested
    @DisplayName("InputLocation enum")
    class InputLocationTest {

        @Test
        @DisplayName("declares all expected constants")
        void hasAllConstants() {
            Set<String> names =
                    Set.of(Arrays.stream(InputLocation.values()).map(Enum::name).toArray(String[]::new));
            assertTrue(names.contains("PATH"));
            assertTrue(names.contains("QUERY"));
            assertTrue(names.contains("HEADER"));
            assertTrue(names.contains("COOKIE"));
            assertTrue(names.contains("FORM"));
            assertTrue(names.contains("BODY"));
            assertTrue(names.contains("BEAN_PARAM"));
            assertTrue(names.contains("PAYLOAD"));
            assertEquals(8, names.size());
        }
    }

    // --- InputFieldNameResolver ---

    /**
     * Freezes the published surface of {@link InputFieldNameResolver}.
     *
     * <p>The contract is a lambda target implemented outside this module (the Jackson-backed
     * projection lives in {@code vertique-json}) and is consumed by the input-processing engine, so
     * its member set is a compatibility commitment. This ledger moved here with the interface when
     * it was relocated from {@code dev.vertique.input.processing}; the assertions are unchanged.
     */
    @Nested
    @DisplayName("InputFieldNameResolver contract")
    class InputFieldNameResolverTest {

        @Test
        @DisplayName(
                "public members match the frozen ledger (one abstract method, five default methods, plus IDENTITY)")
        void publicMembersMatchLedger() {
            Set<String> methods = Arrays.stream(InputFieldNameResolver.class.getDeclaredMethods())
                    .filter(method -> Modifier.isPublic(method.getModifiers()))
                    .filter(method -> !method.isSynthetic())
                    .map(method -> method.getName() + "("
                            + Arrays.stream(method.getParameterTypes())
                                    .map(Class::getSimpleName)
                                    .collect(Collectors.joining(","))
                            + ")")
                    .collect(Collectors.toCollection(TreeSet::new));
            // promotedFields, promotedField, boundJavaNames and unroutableWireNames joined the
            // ledger with ADR-0247, all as default methods: an implementation that ignores them is
            // unaffected, which is what keeps the addition compatible on a Stable interface.
            assertEquals(
                    new TreeSet<>(Set.of(
                            "boundJavaNames(Class)",
                            "logicalName(Class,String)",
                            "precompute(Class)",
                            "promotedField(Class,String)",
                            "promotedFields(Class)",
                            "unroutableWireNames(Class)")),
                    methods);

            Set<String> fields = Arrays.stream(InputFieldNameResolver.class.getDeclaredFields())
                    .filter(field -> Modifier.isPublic(field.getModifiers()))
                    .filter(field -> !field.isSynthetic())
                    .map(Field::getName)
                    .collect(Collectors.toCollection(TreeSet::new));
            assertEquals(new TreeSet<>(Set.of("IDENTITY")), fields);

            assertEquals(0, InputFieldNameResolver.class.getDeclaredConstructors().length);
        }

        @Test
        @DisplayName("the contract stays a single-abstract-method interface")
        void resolverIsFunctional() {
            assertTrue(
                    InputFieldNameResolver.class.isAnnotationPresent(FunctionalInterface.class),
                    "InputFieldNameResolver is published as a lambda target and must stay "
                            + "@FunctionalInterface — a second abstract method would break every "
                            + "implementation");
        }

        @Test
        @DisplayName("IDENTITY inherits the no-op precompute")
        void identityPrecomputeIsANoOp() {
            assertDoesNotThrow(() -> InputFieldNameResolver.IDENTITY.precompute(Object.class));
        }

        @Test
        @DisplayName("IDENTITY inherits the empty promotedFields, so a codec that promotes nothing is unaffected")
        void identityPromotesNothing() {
            assertTrue(
                    InputFieldNameResolver.IDENTITY.promotedFields(Object.class).isEmpty());
        }

        @Test
        @DisplayName("IDENTITY cannot enumerate what it binds, so the default boundJavaNames is null")
        void identityBoundNamesAreUnknown() {
            assertNull(InputFieldNameResolver.IDENTITY.boundJavaNames(Object.class));
        }

        @Test
        @DisplayName("IDENTITY routes every key, so the default unroutableWireNames is empty")
        void identityHasNoUnroutableKeys() {
            assertTrue(InputFieldNameResolver.IDENTITY
                    .unroutableWireNames(Object.class)
                    .isEmpty());
        }

        @Test
        @DisplayName("PromotedField copies its enclosing path, so a live list is never published")
        void promotedFieldCopiesItsPath() {
            List<String> live = new java.util.ArrayList<>(List.of("holder"));
            InputFieldNameResolver.PromotedField field =
                    new InputFieldNameResolver.PromotedField(String.class, "value", live);
            live.add("mutated");
            assertEquals(List.of("holder"), field.enclosingPath());
            assertThrows(UnsupportedOperationException.class, () -> field.enclosingPath()
                    .add("x"));
        }

        @Test
        @DisplayName("PromotedField carries the declaring type, the field name, and the enclosing path")
        void promotedFieldCarriesItsThreeParts() {
            InputFieldNameResolver.PromotedField field =
                    new InputFieldNameResolver.PromotedField(String.class, "value", List.of("holder"));
            assertEquals(String.class, field.declaringType());
            assertEquals("value", field.fieldName());
            assertEquals(List.of("holder"), field.enclosingPath());
        }

        @Test
        @DisplayName("the default promotedField reads promotedFields, so one override serves both")
        void defaultPromotedFieldReadsTheMap() {
            InputFieldNameResolver.PromotedField promotion =
                    new InputFieldNameResolver.PromotedField(String.class, "value", List.of("holder"));
            InputFieldNameResolver resolver = new InputFieldNameResolver() {
                @Override
                public String logicalName(Class<?> ownerType, String wireName) {
                    return wireName;
                }

                @Override
                public Map<String, InputFieldNameResolver.PromotedField> promotedFields(Class<?> ownerType) {
                    return Map.of("wire_value", promotion);
                }
            };
            assertEquals(promotion, resolver.promotedField(Object.class, "wire_value"));
            assertNull(resolver.promotedField(Object.class, "absent"));
        }
    }

    // --- InputValueContext ---

    @Nested
    @DisplayName("InputValueContext record")
    class InputValueContextTest {

        @Test
        @DisplayName("accessors return constructor-supplied values")
        void accessorsReturnValues() {
            InputValueContext ctx = new InputValueContext(InputLocation.QUERY, "address.city", "city", String.class);
            assertEquals(InputLocation.QUERY, ctx.location());
            assertEquals("address.city", ctx.path());
            assertEquals("city", ctx.logicalName());
            assertEquals(String.class, ctx.ownerType());
        }

        @Test
        @DisplayName("equals and hashCode are value-based")
        void equalityIsValueBased() {
            InputValueContext a = new InputValueContext(InputLocation.BODY, "name", "name", Object.class);
            InputValueContext b = new InputValueContext(InputLocation.BODY, "name", "name", Object.class);
            assertEquals(a, b);
            assertEquals(a.hashCode(), b.hashCode());
        }

        @Test
        @DisplayName("records with different fields are not equal")
        void differentFieldsNotEqual() {
            InputValueContext a = new InputValueContext(InputLocation.PATH, "id", "id", String.class);
            InputValueContext b = new InputValueContext(InputLocation.QUERY, "id", "id", String.class);
            assertNotEquals(a, b);
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
