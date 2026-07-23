// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.core.util;

import static org.junit.jupiter.api.Assertions.*;

import jakarta.annotation.Nullable;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AnnotationResolver}.
 *
 * <p>Verifies method annotation resolution (direct, superclass, interface, parent interface) and
 * class annotation resolution (direct, superclass, interface). Also checks deduplication when the
 * same annotation appears in multiple places in the hierarchy, and the empty-annotation edge case.
 */
class AnnotationResolverTest {

    // --- Custom annotations used as fixtures ---

    /** Method-level test annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface TestMethodAnno {}

    /** Type-level test annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface TestClassAnno {}

    /** Second method-level annotation for multi-annotation tests. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    @interface AnotherMethodAnno {}

    // --- Hierarchy fixtures for method annotation tests ---

    interface ParentInterface {
        @TestMethodAnno
        void doWork();
    }

    interface ChildInterface extends ParentInterface {
        /** Inherits {@code @TestMethodAnno} from {@link ParentInterface} — no override. */
        @Override
        void doWork();
    }

    @TestClassAnno
    static class BaseClass {
        @AnotherMethodAnno
        public void doWork() {}
    }

    static class DirectClass {
        @TestMethodAnno
        public void doWork() {}
    }

    static class SubClass extends BaseClass implements ChildInterface {
        // Inherits doWork from BaseClass — no direct annotation
        @Override
        public void doWork() {}
    }

    static class InterfaceOnlyClass implements ParentInterface {
        // Method has no direct annotation — annotation comes from interface only
        @Override
        public void doWork() {}
    }

    static class NoAnnotationClass {
        public void doWork() {}
    }

    // --- Hierarchy fixtures for class annotation tests ---

    @TestClassAnno
    interface AnnotatedInterface {}

    @TestClassAnno
    static class AnnotatedBaseClass {}

    static class SubClassOfAnnotatedBase extends AnnotatedBaseClass {}

    static class ImplementsAnnotatedInterface implements AnnotatedInterface {}

    // --- Helpers ---

    private static Method method(Class<?> clazz) throws NoSuchMethodException {
        return clazz.getMethod("doWork");
    }

    // --- Method annotation tests ---

    @Nested
    @DisplayName("resolveMethodAnnotations")
    class ResolveMethodAnnotations {

        @Test
        @DisplayName("returns annotation declared directly on the method")
        void returnsDirectAnnotation() throws NoSuchMethodException {
            List<java.lang.annotation.Annotation> annotations =
                    AnnotationResolver.resolveMethodAnnotations(method(DirectClass.class));

            assertTrue(
                    annotations.stream().anyMatch(a -> a instanceof TestMethodAnno),
                    "Expected @TestMethodAnno on direct class method");
        }

        @Test
        @DisplayName("returns annotation inherited from superclass method")
        void returnsAnnotationFromSuperclassMethod() throws NoSuchMethodException {
            List<java.lang.annotation.Annotation> annotations =
                    AnnotationResolver.resolveMethodAnnotations(method(SubClass.class));

            assertTrue(
                    annotations.stream().anyMatch(a -> a instanceof AnotherMethodAnno),
                    "Expected @AnotherMethodAnno inherited from BaseClass.doWork()");
        }

        @Test
        @DisplayName("returns annotation inherited from directly implemented interface")
        void returnsAnnotationFromDirectInterface() throws NoSuchMethodException {
            List<java.lang.annotation.Annotation> annotations =
                    AnnotationResolver.resolveMethodAnnotations(method(InterfaceOnlyClass.class));

            assertTrue(
                    annotations.stream().anyMatch(a -> a instanceof TestMethodAnno),
                    "Expected @TestMethodAnno inherited from ParentInterface.doWork()");
        }

        @Test
        @DisplayName("returns annotation from parent interface (interface hierarchy)")
        void returnsAnnotationFromParentInterface() throws NoSuchMethodException {
            // ChildInterface extends ParentInterface which has @TestMethodAnno on doWork()
            // SubClass implements ChildInterface — should still resolve the annotation
            List<java.lang.annotation.Annotation> annotations =
                    AnnotationResolver.resolveMethodAnnotations(method(SubClass.class));

            assertTrue(
                    annotations.stream().anyMatch(a -> a instanceof TestMethodAnno),
                    "Expected @TestMethodAnno transitively from ParentInterface via ChildInterface");
        }

        @Test
        @DisplayName("returns empty list when no annotations exist anywhere in the hierarchy")
        void returnsEmptyListForNoAnnotations() throws NoSuchMethodException {
            List<java.lang.annotation.Annotation> annotations =
                    AnnotationResolver.resolveMethodAnnotations(method(NoAnnotationClass.class));

            assertTrue(annotations.isEmpty(), "Expected empty list for unannotated method");
        }

        @Test
        @DisplayName("does not duplicate annotations present in both class and interface")
        void noDuplicatesWhenAnnotationAppearsInMultiplePlaces() throws NoSuchMethodException {
            // SubClass.doWork() inherits @TestMethodAnno (from ParentInterface via ChildInterface)
            // and @AnotherMethodAnno (from BaseClass). No duplicate should appear.
            List<java.lang.annotation.Annotation> annotations =
                    AnnotationResolver.resolveMethodAnnotations(method(SubClass.class));

            long testMethodAnnoCount = annotations.stream()
                    .filter(a -> a instanceof TestMethodAnno)
                    .count();
            assertEquals(1, testMethodAnnoCount, "Expected @TestMethodAnno to appear exactly once (no duplicates)");
        }

        @Test
        @DisplayName("returns an immutable list")
        void returnsImmutableList() throws NoSuchMethodException {
            List<java.lang.annotation.Annotation> annotations =
                    AnnotationResolver.resolveMethodAnnotations(method(DirectClass.class));

            assertThrows(UnsupportedOperationException.class, () -> annotations.add(null));
        }
    }

    // --- Class annotation tests ---

    @Nested
    @DisplayName("resolveClassAnnotations")
    class ResolveClassAnnotations {

        @Test
        @DisplayName("returns annotation declared directly on the class")
        void returnsDirectAnnotation() {
            List<java.lang.annotation.Annotation> annotations =
                    AnnotationResolver.resolveClassAnnotations(AnnotatedBaseClass.class);

            assertTrue(
                    annotations.stream().anyMatch(a -> a instanceof TestClassAnno),
                    "Expected @TestClassAnno on annotated class");
        }

        @Test
        @DisplayName("returns annotation inherited from superclass")
        void returnsAnnotationFromSuperclass() {
            List<java.lang.annotation.Annotation> annotations =
                    AnnotationResolver.resolveClassAnnotations(SubClassOfAnnotatedBase.class);

            assertTrue(
                    annotations.stream().anyMatch(a -> a instanceof TestClassAnno),
                    "Expected @TestClassAnno inherited from AnnotatedBaseClass");
        }

        @Test
        @DisplayName("returns annotation from implemented interface")
        void returnsAnnotationFromInterface() {
            List<java.lang.annotation.Annotation> annotations =
                    AnnotationResolver.resolveClassAnnotations(ImplementsAnnotatedInterface.class);

            assertTrue(
                    annotations.stream().anyMatch(a -> a instanceof TestClassAnno),
                    "Expected @TestClassAnno from AnnotatedInterface");
        }

        @Test
        @DisplayName("does not duplicate annotation present on both superclass and interface")
        void noDuplicatesWhenAnnotationAppearsInMultiplePlaces() {
            // A class that both extends AnnotatedBaseClass and implements AnnotatedInterface
            // would see @TestClassAnno twice without deduplication.
            class BothAnnotatedSources extends AnnotatedBaseClass implements AnnotatedInterface {}

            List<java.lang.annotation.Annotation> annotations =
                    AnnotationResolver.resolveClassAnnotations(BothAnnotatedSources.class);

            long count =
                    annotations.stream().filter(a -> a instanceof TestClassAnno).count();
            assertEquals(1, count, "Expected @TestClassAnno to appear exactly once (no duplicates)");
        }

        @Test
        @DisplayName("returns empty list for a class with no annotations anywhere in the hierarchy")
        void returnsEmptyListForNoAnnotations() {
            List<java.lang.annotation.Annotation> annotations =
                    AnnotationResolver.resolveClassAnnotations(NoAnnotationClass.class);

            assertTrue(annotations.isEmpty(), "Expected empty list for unannotated class");
        }

        @Test
        @DisplayName("returns an immutable list")
        void returnsImmutableList() {
            List<java.lang.annotation.Annotation> annotations =
                    AnnotationResolver.resolveClassAnnotations(AnnotatedBaseClass.class);

            assertThrows(UnsupportedOperationException.class, () -> annotations.add(null));
        }
    }

    // --- Parameter annotation fixtures ---

    /** Parameter-level test annotation. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface TestParamAnno {
        String value() default "";
    }

    /** Second parameter-level annotation for multi-annotation tests. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.PARAMETER)
    @interface OtherParamAnno {}

    interface ParamInterface {
        void op(@TestParamAnno("from-iface") String first, @OtherParamAnno int second);
    }

    interface ParamChildInterface extends ParamInterface {
        @Override
        void op(String first, int second);
    }

    static class ParamBaseClass {
        public void op(@TestParamAnno("from-super") String first, int second) {}
    }

    /** Concrete impl whose annotations live on the interface (impl is bare). */
    static class ParamInterfaceImpl implements ParamInterface {
        @Override
        public void op(String first, int second) {}
    }

    /** Concrete impl whose annotations live on the superclass. */
    static class ParamSuperImpl extends ParamBaseClass {
        @Override
        public void op(String first, int second) {}
    }

    /** Concrete impl with direct annotations that should win over interface. */
    static class ParamDirectImpl implements ParamInterface {
        @Override
        public void op(@TestParamAnno("from-direct") String first, int second) {}
    }

    /** Direct + super + interface all carry annotations on parameter 0; direct wins for same type. */
    static class ParamMixedImpl extends ParamBaseClass implements ParamInterface {
        @Override
        public void op(@TestParamAnno("from-direct") String first, int second) {}
    }

    static class ParamNoAnnoImpl {
        public void op(String first, int second) {}
    }

    private static Method paramMethod(Class<?> clazz) throws NoSuchMethodException {
        return clazz.getMethod("op", String.class, int.class);
    }

    @Nested
    @DisplayName("resolveParameterAnnotations")
    class ResolveParameterAnnotations {

        @Test
        @DisplayName("returns interface-declared annotations when impl parameter is bare")
        void interfaceDeclared() throws NoSuchMethodException {
            java.lang.annotation.Annotation[] annos =
                    AnnotationResolver.resolveParameterAnnotations(paramMethod(ParamInterfaceImpl.class), 0);

            TestParamAnno tpa = findAnnotation(annos, TestParamAnno.class);
            assertNotNull(tpa, "Expected @TestParamAnno from ParamInterface.op param 0");
            assertEquals("from-iface", tpa.value());
        }

        @Test
        @DisplayName("returns superclass-declared annotations when impl parameter is bare")
        void superclassDeclared() throws NoSuchMethodException {
            java.lang.annotation.Annotation[] annos =
                    AnnotationResolver.resolveParameterAnnotations(paramMethod(ParamSuperImpl.class), 0);

            TestParamAnno tpa = findAnnotation(annos, TestParamAnno.class);
            assertNotNull(tpa, "Expected @TestParamAnno from ParamBaseClass.op param 0");
            assertEquals("from-super", tpa.value());
        }

        @Test
        @DisplayName("direct annotation wins over interface for the same annotation type")
        void directWinsOverInterface() throws NoSuchMethodException {
            java.lang.annotation.Annotation[] annos =
                    AnnotationResolver.resolveParameterAnnotations(paramMethod(ParamDirectImpl.class), 0);

            TestParamAnno tpa = findAnnotation(annos, TestParamAnno.class);
            assertNotNull(tpa);
            assertEquals("from-direct", tpa.value(), "Direct param annotation should win over interface");
        }

        @Test
        @DisplayName("merges across direct + super + interface; first-by-type lookup yields direct")
        void mergesAcrossLevels() throws NoSuchMethodException {
            java.lang.annotation.Annotation[] annos =
                    AnnotationResolver.resolveParameterAnnotations(paramMethod(ParamMixedImpl.class), 0);

            // Insertion order is direct → super → interface; first-by-type lookup yields direct.
            TestParamAnno tpa = findAnnotation(annos, TestParamAnno.class);
            assertNotNull(tpa);
            assertEquals("from-direct", tpa.value(), "Direct param annotation should appear first");

            // Same-type instances with differing member values are all retained (LinkedHashSet
            // equality dedupes by member values, not by annotation type). Three distinct values
            // (from-direct, from-super, from-iface) → three retained instances.
            long count = java.util.Arrays.stream(annos)
                    .filter(a -> a instanceof TestParamAnno)
                    .count();
            assertEquals(
                    3,
                    count,
                    "All three distinct @TestParamAnno instances are retained; first-by-type lookup gives direct.");
        }

        @Test
        @DisplayName("identical-value annotations across levels are deduplicated to one entry")
        void identicalValuesDeduped() throws NoSuchMethodException {
            // Both impl and interface declare @TestParamAnno("same") on parameter 0.
            class IdenticalIface implements ParamInterface {
                @Override
                public void op(@TestParamAnno("from-iface") String first, int second) {}
            }
            // Sanity: ParamInterface.op param 0 already carries @TestParamAnno("from-iface");
            // when the impl override carries the same value, the LinkedHashSet collapses them.
            java.lang.annotation.Annotation[] annos =
                    AnnotationResolver.resolveParameterAnnotations(paramMethod(IdenticalIface.class), 0);

            long count = java.util.Arrays.stream(annos)
                    .filter(a -> a instanceof TestParamAnno)
                    .count();
            assertEquals(1, count, "Identical @TestParamAnno values should dedupe to one entry");
        }

        @Test
        @DisplayName("walks parent interfaces transitively")
        void transitiveInterface() throws NoSuchMethodException {
            class ChildImpl implements ParamChildInterface {
                @Override
                public void op(String first, int second) {}
            }

            java.lang.annotation.Annotation[] annos =
                    AnnotationResolver.resolveParameterAnnotations(paramMethod(ChildImpl.class), 0);

            assertNotNull(
                    findAnnotation(annos, TestParamAnno.class),
                    "Expected @TestParamAnno transitively from ParamInterface via ParamChildInterface");
        }

        @Test
        @DisplayName("returns annotations for parameter index > 0")
        void nonZeroIndex() throws NoSuchMethodException {
            java.lang.annotation.Annotation[] annos =
                    AnnotationResolver.resolveParameterAnnotations(paramMethod(ParamInterfaceImpl.class), 1);

            assertNotNull(findAnnotation(annos, OtherParamAnno.class), "Expected @OtherParamAnno on parameter 1");
        }

        @Test
        @DisplayName("returns empty array for a bare parameter with no annotations anywhere")
        void emptyForUnannotated() throws NoSuchMethodException {
            java.lang.annotation.Annotation[] annos =
                    AnnotationResolver.resolveParameterAnnotations(paramMethod(ParamNoAnnoImpl.class), 0);

            assertEquals(0, annos.length, "Expected empty array for unannotated parameter");
        }

        @Test
        @DisplayName("throws IndexOutOfBoundsException for negative index")
        void negativeIndexThrows() throws NoSuchMethodException {
            assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> AnnotationResolver.resolveParameterAnnotations(paramMethod(ParamInterfaceImpl.class), -1));
        }

        @Test
        @DisplayName("throws IndexOutOfBoundsException for index >= parameterCount")
        void overflowIndexThrows() throws NoSuchMethodException {
            assertThrows(
                    IndexOutOfBoundsException.class,
                    () -> AnnotationResolver.resolveParameterAnnotations(paramMethod(ParamInterfaceImpl.class), 2));
        }
    }

    @SuppressWarnings("unchecked")
    @Nullable
    private static <A extends java.lang.annotation.Annotation> A findAnnotation(
            java.lang.annotation.Annotation[] annos, Class<A> type) {
        for (java.lang.annotation.Annotation a : annos) {
            if (type.isInstance(a)) return (A) a;
        }
        return null;
    }

    // --- Meta-annotation fixtures ---

    /** A marker annotation used as a meta-annotation target in composition tests. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.TYPE, ElementType.METHOD})
    @interface MetaTarget {}

    /** A skip marker annotation used as a meta-annotation target. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.TYPE, ElementType.METHOD})
    @interface MetaSkip {}

    /**
     * Composed annotation: {@code @NormalizedInput} is meta-annotated with {@code @MetaTarget}.
     * Placing {@code @NormalizedInput} on a field should allow discovery of {@code @MetaTarget}.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.TYPE, ElementType.METHOD})
    @MetaTarget
    @interface NormalizedInput {}

    /**
     * Composed annotation: {@code @SkipProcessing} is meta-annotated with {@code @MetaSkip}.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.TYPE, ElementType.METHOD})
    @MetaSkip
    @interface SkipProcessing {}

    /**
     * Annotation that is itself meta-annotated with {@code @NormalizedInput} (depth-2 chain).
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.TYPE})
    @NormalizedInput
    @interface DeepComposed {}

    // --- Cyclic meta-annotation fixture ---

    /** Annotation A that is meta-annotated with annotation B (forming a cycle with B). */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE})
    @interface CyclicA {}

    /** Annotation B that is meta-annotated with annotation A (forming a cycle with A). */
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.ANNOTATION_TYPE})
    @CyclicA
    @interface CyclicB {}

    /** Holder class with various field annotations for meta-annotation tests. */
    static class MetaHolder {
        @NormalizedInput
        String composed;

        @MetaTarget
        String direct;

        @SkipProcessing
        String skipped;

        @DeepComposed
        String deep;

        @CyclicB
        String cyclic;
    }

    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @MetaTarget
    @interface NormalizedType {}

    @NormalizedInput
    static class MetaAnnotatedClass {}

    // --- findMetaAnnotation tests ---

    @Nested
    @DisplayName("findMetaAnnotation")
    class FindMetaAnnotation {

        @Test
        @DisplayName("finds directly present annotation on AnnotatedElement")
        void findsDirectAnnotationOnElement() throws NoSuchFieldException {
            Field field = MetaHolder.class.getDeclaredField("direct");
            MetaTarget result = AnnotationResolver.findMetaAnnotation(field, MetaTarget.class);
            assertNotNull(result, "expected @MetaTarget directly on field");
        }

        @Test
        @DisplayName("finds annotation via meta-annotation on AnnotatedElement")
        void findsMetaAnnotationOnElement() throws NoSuchFieldException {
            Field field = MetaHolder.class.getDeclaredField("composed");
            // @NormalizedInput is meta-annotated with @MetaTarget
            MetaTarget result = AnnotationResolver.findMetaAnnotation(field, MetaTarget.class);
            assertNotNull(result, "expected @MetaTarget discovered via @NormalizedInput meta-annotation");
        }

        @Test
        @DisplayName("finds annotation via meta-annotation in pre-resolved list")
        void findsMetaAnnotationInList() throws NoSuchFieldException {
            Field field = MetaHolder.class.getDeclaredField("composed");
            List<java.lang.annotation.Annotation> annotations = List.of(field.getAnnotations());
            MetaTarget result = AnnotationResolver.findMetaAnnotation(annotations, MetaTarget.class);
            assertNotNull(result, "expected @MetaTarget discovered via @NormalizedInput in annotation list");
        }

        @Test
        @DisplayName("finds annotation via skip meta-annotation on AnnotatedElement")
        void findsSkipMetaAnnotation() throws NoSuchFieldException {
            Field field = MetaHolder.class.getDeclaredField("skipped");
            MetaSkip result = AnnotationResolver.findMetaAnnotation(field, MetaSkip.class);
            assertNotNull(result, "expected @MetaSkip discovered via @SkipProcessing meta-annotation");
        }

        @Test
        @DisplayName("direct annotation takes precedence over meta-annotation in list")
        void directAnnotationTakesPrecedenceInList() throws NoSuchFieldException {
            Field direct = MetaHolder.class.getDeclaredField("direct");
            List<java.lang.annotation.Annotation> annotations = List.of(direct.getAnnotations());
            MetaTarget result = AnnotationResolver.findMetaAnnotation(annotations, MetaTarget.class);
            assertNotNull(result);
            // The direct @MetaTarget instance is the same object as the one on the field
            assertSame(direct.getAnnotation(MetaTarget.class), result);
        }

        @Test
        @DisplayName("returns null when annotation is absent from element and meta-annotations")
        void returnsNullWhenAbsent() throws NoSuchFieldException {
            Field field = MetaHolder.class.getDeclaredField("skipped");
            // @SkipProcessing carries @MetaSkip but not @MetaTarget
            MetaTarget result = AnnotationResolver.findMetaAnnotation(field, MetaTarget.class);
            assertNull(result, "expected null — @MetaTarget not present directly or via meta-annotation");
        }

        @Test
        @DisplayName("cyclic meta-annotations terminate without error")
        void cyclicMetaAnnotationsTerminate() throws NoSuchFieldException {
            Field field = MetaHolder.class.getDeclaredField("cyclic");
            // @CyclicB → @CyclicA → (no @MetaTarget) — should not loop forever
            MetaTarget result = AnnotationResolver.findMetaAnnotation(field, MetaTarget.class);
            assertNull(result, "expected null — cycle terminates cleanly with no match");
        }

        @Test
        @DisplayName("java.lang.annotation.* annotations are skipped during traversal")
        void jdkAnnotationsAreSkipped() throws NoSuchFieldException {
            // @NormalizedInput carries @Retention and @Target (java.lang.annotation.*)
            // those must be skipped to avoid StackOverflow / spurious matches
            Field field = MetaHolder.class.getDeclaredField("composed");
            // Retention and Target should never be returned as @MetaTarget
            MetaTarget result = AnnotationResolver.findMetaAnnotation(field, MetaTarget.class);
            assertNotNull(result, "meta-annotation traversal should succeed despite JDK annotations");
        }

        @Test
        @DisplayName("finds annotation via type-level meta-annotation on AnnotatedElement")
        void findsMetaAnnotationOnType() {
            MetaTarget result = AnnotationResolver.findMetaAnnotation(MetaAnnotatedClass.class, MetaTarget.class);
            assertNotNull(result, "expected @MetaTarget discovered via @NormalizedInput on type");
        }

        @Test
        @DisplayName("finds deep composed annotation (depth-2 meta-annotation chain)")
        void findsDeepComposedAnnotation() throws NoSuchFieldException {
            Field field = MetaHolder.class.getDeclaredField("deep");
            // @DeepComposed → @NormalizedInput → @MetaTarget
            MetaTarget result = AnnotationResolver.findMetaAnnotation(field, MetaTarget.class);
            assertNotNull(result, "expected @MetaTarget at depth-2 via @DeepComposed → @NormalizedInput");
        }
    }
}
