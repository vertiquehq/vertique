// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.sanitization.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.ProcessorTestHarness.Result;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for annotation collection behaviour in {@code AnnotationCollector}.
 *
 * <p>Verifies that field-level chain annotations, inherited fields, record components,
 * meta-annotations, and conflict detection all behave correctly.
 */
class AnnotationCollectorTest {

    // --- Shared resource wrapper ---

    private static JavaFileObject resourceFor(String paramFqn) {
        String simpleName = paramFqn.substring(paramFqn.lastIndexOf('.') + 1);
        String pkg = paramFqn.substring(0, paramFqn.lastIndexOf('.'));
        return SourceFiles.inline(pkg + ".BodyResource", """
                package %s;
                import jakarta.ws.rs.POST;
                import jakarta.ws.rs.Path;
                @Path("/body")
                public class BodyResource {
                    @POST
                    public String create(%s body) { return null; }
                }
                """.formatted(pkg, simpleName));
    }

    // --- Field-level annotations ---

    @Nested
    @DisplayName("field-level annotations")
    class FieldLevelAnnotations {

        @Test
        @DisplayName("field-level @Canonicalize chain captured in processor constants")
        void fieldLevelCanonChain_capturedInProcessor() {
            JavaFileObject dto = SourceFiles.inline("com.example.ChainDto", """
                    package com.example;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    import dev.vertique.sanitization.canonicalize.LowerCaseCanonicalizer;
                    public class ChainDto {
                        @Canonicalize({TrimCanonicalizer.class, LowerCaseCanonicalizer.class})
                        public String slug;
                    }
                    """);

            ProcessorTestHarness.run(new SanitizationProcessor(), dto, resourceFor("com.example.ChainDto"))
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.ChainDto_InputProcessor", "TrimCanonicalizer.class")
                    .assertGeneratedSourceContains(
                            "com.example.ChainDto_InputProcessor", "LowerCaseCanonicalizer.class");
        }

        @Test
        @DisplayName("field-level @Sanitize chain captured in processor constants")
        void fieldLevelSanitChain_capturedInProcessor() {
            JavaFileObject dto = SourceFiles.inline("com.example.SanitDto", """
                    package com.example;
                    import dev.vertique.core.sanitization.Sanitize;
                    import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
                    public class SanitDto {
                        @Sanitize(StripControlCharsSanitizer.class)
                        public String content;
                    }
                    """);

            ProcessorTestHarness.run(new SanitizationProcessor(), dto, resourceFor("com.example.SanitDto"))
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.SanitDto_InputProcessor", "StripControlCharsSanitizer.class");
        }
    }

    // --- Inheritance ---

    @Nested
    @DisplayName("inheritance of annotated fields")
    class Inheritance {

        @Test
        @DisplayName("subclass inherits annotated field from superclass — processor contains inherited field")
        void subclassInheritsAnnotatedField_capturedInProcessor() {
            JavaFileObject base = SourceFiles.inline("com.example.BaseDto", """
                    package com.example;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    public class BaseDto {
                        @Canonicalize(TrimCanonicalizer.class)
                        public String name;
                    }
                    """);
            JavaFileObject child = SourceFiles.inline("com.example.ChildDto", """
                    package com.example;
                    public class ChildDto extends BaseDto {
                        public String extra;
                    }
                    """);

            ProcessorTestHarness.run(new SanitizationProcessor(), base, child, resourceFor("com.example.ChildDto"))
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.ChildDto_InputProcessor", "TrimCanonicalizer.class")
                    .assertGeneratedSourceContains("com.example.ChildDto_InputProcessor", "\"name\"");
        }
    }

    // --- Record components ---

    @Nested
    @DisplayName("record components with annotations")
    class RecordComponents {

        @Test
        @DisplayName("record component annotated with @Canonicalize — processor captures the chain")
        void recordComponent_canonAnnotation_capturedInProcessor() {
            JavaFileObject record = SourceFiles.inline("com.example.ItemRecord", """
                    package com.example;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    public record ItemRecord(
                        @Canonicalize(TrimCanonicalizer.class) String title,
                        int quantity
                    ) {}
                    """);

            ProcessorTestHarness.run(new SanitizationProcessor(), record, resourceFor("com.example.ItemRecord"))
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.ItemRecord_InputProcessor", "TrimCanonicalizer.class")
                    .assertGeneratedSourceContains("com.example.ItemRecord_InputProcessor", "\"title\"");
        }
    }

    // --- Meta-annotations ---

    @Nested
    @DisplayName("meta-annotation support")
    class MetaAnnotations {

        @Test
        @DisplayName("multi-hop composed annotation (@A -> @B -> @Canonicalize) — recognized on field")
        void multiHopComposedAnnotation_recognizedOnField() {
            // Pins the recursive findMetaAnnotation behavior introduced after a follow-up
            // review found that single-hop walking would silently drop the chain when a DTO
            // used a deeper composition like @MyDoubleAlias which itself is meta-annotated
            // with @MyAlias which in turn carries @Canonicalize. The reflective walker
            // (AnnotationResolver) walks recursively; the codegen scanner must too.
            JavaFileObject inner = SourceFiles.inline("com.example.MyAlias", """
                    package com.example;
                    import java.lang.annotation.*;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    @Target({ElementType.ANNOTATION_TYPE, ElementType.FIELD, ElementType.RECORD_COMPONENT})
                    @Retention(RetentionPolicy.RUNTIME)
                    @Canonicalize(TrimCanonicalizer.class)
                    public @interface MyAlias {}
                    """);
            JavaFileObject outer = SourceFiles.inline("com.example.MyDoubleAlias", """
                    package com.example;
                    import java.lang.annotation.*;
                    @Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
                    @Retention(RetentionPolicy.RUNTIME)
                    @MyAlias
                    public @interface MyDoubleAlias {}
                    """);
            JavaFileObject dto = SourceFiles.inline("com.example.MultiHopDto", """
                    package com.example;
                    public class MultiHopDto {
                        @MyDoubleAlias
                        public String value;
                    }
                    """);

            ProcessorTestHarness.run(
                            new SanitizationProcessor(), inner, outer, dto, resourceFor("com.example.MultiHopDto"))
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.MultiHopDto_InputProcessor", "TrimCanonicalizer.class");
        }

        @Test
        @DisplayName("custom composed annotation carrying @Canonicalize — recognized on field")
        void composedAnnotationWithCanonicalize_recognizedOnField() {
            JavaFileObject composedAnnotation = SourceFiles.inline("com.example.Trimmed", """
                    package com.example;
                    import java.lang.annotation.*;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    @Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
                    @Retention(RetentionPolicy.RUNTIME)
                    @Canonicalize(TrimCanonicalizer.class)
                    public @interface Trimmed {}
                    """);
            JavaFileObject dto = SourceFiles.inline("com.example.ComposedDto", """
                    package com.example;
                    public class ComposedDto {
                        @Trimmed
                        public String username;
                    }
                    """);

            ProcessorTestHarness.run(
                            new SanitizationProcessor(),
                            composedAnnotation,
                            dto,
                            resourceFor("com.example.ComposedDto"))
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.ComposedDto_InputProcessor", "TrimCanonicalizer.class");
        }
    }

    // --- Collection element resolution ---

    /**
     * Pins the element rule of {@code AnnotationCollector.extractCollectionElementType} and of
     * {@code RestBodyDiscovery}: a collection's element is {@code E} in its {@code Collection<E>}
     * <em>supertype binding</em>, not "type argument 0" of the declared type.
     *
     * <p>The three shapes below separate the two rules. {@code Pair<A, B> extends ArrayList<A>}
     * makes argument 0 accidentally right; {@code Fixed<T> extends ArrayList<String>} makes it wrong
     * even at arity one — the element Jackson binds is {@code String}, never the declared argument;
     * {@code Weird<A, B> extends ArrayList<B>} makes it outright wrong. The reflective
     * {@code TypeClassifier.elementType} in {@code vertique-input-processing} resolves the same
     * three shapes the same way, so the generated and reflective paths classify them identically.
     */
    @Nested
    @DisplayName("collection element type resolution")
    class CollectionElementResolution {

        /** The element type each collection shape must resolve to. */
        private static final JavaFileObject LEAF_DTO = SourceFiles.inline("com.example.el.ElLeafDto", """
                package com.example.el;
                import dev.vertique.core.sanitization.Sanitize;
                import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
                public class ElLeafDto {
                    @Sanitize(StripControlCharsSanitizer.class)
                    public String text;
                }
                """);

        /** A second DTO, so a wrong argument index picks a distinguishable class. */
        private static final JavaFileObject OTHER_DTO = SourceFiles.inline("com.example.el.ElOtherDto", """
                package com.example.el;
                import dev.vertique.core.sanitization.Sanitize;
                import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
                public class ElOtherDto {
                    @Sanitize(StripControlCharsSanitizer.class)
                    public String label;
                }
                """);

        /** Two type arguments, element bound to the first — argument 0 is accidentally right here. */
        private static final JavaFileObject PAIR = SourceFiles.inline("com.example.el.ElPair", """
                package com.example.el;
                import java.util.ArrayList;
                public class ElPair<A, B> extends ArrayList<A> {}
                """);

        /** One type argument, element fixed to {@code String} — argument 0 is wrong at arity one. */
        private static final JavaFileObject FIXED = SourceFiles.inline("com.example.el.ElFixed", """
                package com.example.el;
                import java.util.ArrayList;
                public class ElFixed<T> extends ArrayList<String> {}
                """);

        /** Two type arguments, element bound to the second — argument 0 is outright wrong. */
        private static final JavaFileObject WEIRD = SourceFiles.inline("com.example.el.ElWeird", """
                package com.example.el;
                import java.util.ArrayList;
                public class ElWeird<A, B> extends ArrayList<B> {}
                """);

        /**
         * A concrete, non-generic subtype whose element is fixed by its own declaration. Unlike
         * {@link #FIXED}, this type has no type parameters at all, so a field declared with this
         * type is a plain {@code Class} use site — {@code dt.getTypeArguments()} is empty not
         * because the collection is raw, but because {@code ElDtos} declares no type parameters of
         * its own to instantiate.
         */
        private static final JavaFileObject NON_GENERIC = SourceFiles.inline("com.example.el.ElDtos", """
                package com.example.el;
                import java.util.ArrayList;
                public final class ElDtos extends ArrayList<ElLeafDto> {}
                """);

        /**
         * An outer/inner pair where the inner class's {@code Collection<E>} binding comes from the
         * enclosing instance's type argument rather than from any type argument local to
         * {@code Inner} itself — {@code Inner} declares no type parameters of its own, so its use
         * site's own type-argument list is empty even though the owner type {@code ElOuter<Dto>}
         * carries the binding.
         */
        private static final JavaFileObject OWNER_BOUND_OUTER = SourceFiles.inline("com.example.el.ElOuter", """
                package com.example.el;
                import java.util.ArrayList;
                public class ElOuter<T> {
                    public class Inner extends ArrayList<T> {}
                }
                """);

        @Test
        @DisplayName("a multi-argument collection subtype resolves its element through the supertype binding")
        void pairElementResolvesThroughTheSupertypeBinding() {
            JavaFileObject dto = SourceFiles.inline("com.example.el.PairFieldDto", """
                    package com.example.el;
                    public class PairFieldDto {
                        public ElPair<ElLeafDto, ElOtherDto> items;
                    }
                    """);

            ProcessorTestHarness.run(
                            new SanitizationProcessor(),
                            LEAF_DTO,
                            OTHER_DTO,
                            PAIR,
                            dto,
                            resourceFor("com.example.el.PairFieldDto"))
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.el.PairFieldDto_InputProcessor", "dispatchObjectCollection(v, ElLeafDto.class")
                    .assertGeneratedSourceContains(
                            "com.example.el.PairFieldDto_InputProcessor", "owners.add(ElLeafDto.class)");
        }

        @Test
        @DisplayName("a collection subtype that fixes its element ignores the declared type argument")
        void fixedElementIsTheSupertypeArgumentNotTheDeclaredOne() {
            JavaFileObject dto = SourceFiles.inline("com.example.el.FixedFieldDto", """
                    package com.example.el;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    public class FixedFieldDto {
                        @Canonicalize(TrimCanonicalizer.class)
                        public ElFixed<ElLeafDto> items;
                    }
                    """);

            ProcessorTestHarness.run(
                            new SanitizationProcessor(),
                            LEAF_DTO,
                            FIXED,
                            dto,
                            resourceFor("com.example.el.FixedFieldDto"))
                    .assertSuccess()
                    // ElFixed<T> extends ArrayList<String>, so the field is a collection of strings.
                    .assertGeneratedSourceContains(
                            "com.example.el.FixedFieldDto_InputProcessor",
                            "case \"items\" -> out.put(k, applyStringCollection(")
                    .assertGeneratedSourceDoesNotContain(
                            "com.example.el.FixedFieldDto_InputProcessor", "ElLeafDto.class");
        }

        @Test
        @DisplayName("a collection subtype whose element is its second argument resolves to that argument")
        void weirdElementResolvesToTheSecondArgument() {
            JavaFileObject dto = SourceFiles.inline("com.example.el.WeirdFieldDto", """
                    package com.example.el;
                    public class WeirdFieldDto {
                        public ElWeird<ElOtherDto, ElLeafDto> items;
                    }
                    """);

            ProcessorTestHarness.run(
                            new SanitizationProcessor(),
                            LEAF_DTO,
                            OTHER_DTO,
                            WEIRD,
                            dto,
                            resourceFor("com.example.el.WeirdFieldDto"))
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.el.WeirdFieldDto_InputProcessor",
                            "dispatchObjectCollection(v, ElLeafDto.class")
                    .assertGeneratedSourceDoesNotContain(
                            "com.example.el.WeirdFieldDto_InputProcessor", "ElOtherDto.class");
        }

        @Test
        @DisplayName("a non-generic subtype resolves its element from a plain-Class use site")
        void nonGenericSubtypeResolvesItsElementFromAPlainClassUseSite() {
            JavaFileObject dto = SourceFiles.inline("com.example.el.NonGenericFieldDto", """
                    package com.example.el;
                    public class NonGenericFieldDto {
                        public ElDtos items;
                    }
                    """);

            ProcessorTestHarness.run(
                            new SanitizationProcessor(),
                            LEAF_DTO,
                            NON_GENERIC,
                            dto,
                            resourceFor("com.example.el.NonGenericFieldDto"))
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.el.NonGenericFieldDto_InputProcessor",
                            "dispatchObjectCollection(v, ElLeafDto.class")
                    .assertGeneratedSourceContains(
                            "com.example.el.NonGenericFieldDto_InputProcessor", "owners.add(ElLeafDto.class)");
        }

        @Test
        @DisplayName("an owner-bound inner class resolves its element through the parameterized owner type")
        void ownerBoundInnerClassResolvesItsElementThroughTheOwnerType() {
            JavaFileObject dto = SourceFiles.inline("com.example.el.OwnerBoundFieldDto", """
                    package com.example.el;
                    public class OwnerBoundFieldDto {
                        public ElOuter<ElLeafDto>.Inner items;
                    }
                    """);

            ProcessorTestHarness.run(
                            new SanitizationProcessor(),
                            LEAF_DTO,
                            OWNER_BOUND_OUTER,
                            dto,
                            resourceFor("com.example.el.OwnerBoundFieldDto"))
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.el.OwnerBoundFieldDto_InputProcessor",
                            "dispatchObjectCollection(v, ElLeafDto.class")
                    .assertGeneratedSourceContains(
                            "com.example.el.OwnerBoundFieldDto_InputProcessor", "owners.add(ElLeafDto.class)");
        }

        @Test
        @DisplayName("body discovery resolves a non-generic subtype's element through the same rule")
        void bodyDiscoveryResolvesTheNonGenericSubtypesElement() {
            JavaFileObject resource = SourceFiles.inline("com.example.el.NonGenericBodyResource", """
                    package com.example.el;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    @Path("/non-generic")
                    public class NonGenericBodyResource {
                        @POST
                        public String create(ElDtos body) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), LEAF_DTO, NON_GENERIC, resource);
            result.assertSuccess()
                    .assertGeneratedSourceContains("com.example.el.ElLeafDto_InputProcessor", "ElLeafDto");
        }

        @Test
        @DisplayName("body discovery resolves an owner-bound inner class's element through the same rule")
        void bodyDiscoveryResolvesTheOwnerBoundInnerClassesElement() {
            JavaFileObject resource = SourceFiles.inline("com.example.el.OwnerBoundBodyResource", """
                    package com.example.el;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    @Path("/owner-bound")
                    public class OwnerBoundBodyResource {
                        @POST
                        public String create(ElOuter<ElLeafDto>.Inner body) { return null; }
                    }
                    """);

            Result result =
                    ProcessorTestHarness.run(new SanitizationProcessor(), LEAF_DTO, OWNER_BOUND_OUTER, resource);
            result.assertSuccess()
                    .assertGeneratedSourceContains("com.example.el.ElLeafDto_InputProcessor", "ElLeafDto");
        }

        @Test
        @DisplayName("ordinary collection and array shapes are unchanged")
        void ordinaryCollectionShapesAreUnchanged() {
            JavaFileObject dto = SourceFiles.inline("com.example.el.OrdinaryFieldDto", """
                    package com.example.el;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    import java.util.List;
                    public class OrdinaryFieldDto {
                        public List<ElLeafDto> list;
                        public ElLeafDto[] array;
                        @Canonicalize(TrimCanonicalizer.class)
                        public List<String> tags;
                        @Canonicalize(TrimCanonicalizer.class)
                        public List raw;
                        @Canonicalize(TrimCanonicalizer.class)
                        public List<List<ElLeafDto>> nested;
                    }
                    """);

            ProcessorTestHarness.run(
                            new SanitizationProcessor(), LEAF_DTO, dto, resourceFor("com.example.el.OrdinaryFieldDto"))
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.el.OrdinaryFieldDto_InputProcessor", "case \"list\" -> {")
                    .assertGeneratedSourceContains(
                            "com.example.el.OrdinaryFieldDto_InputProcessor",
                            "dispatchObjectCollection(v, ElLeafDto.class")
                    .assertGeneratedSourceContains(
                            "com.example.el.OrdinaryFieldDto_InputProcessor", "case \"array\" -> {")
                    // A String element routes to the string-collection arm, never element dispatch.
                    .assertGeneratedSourceContains(
                            "com.example.el.OrdinaryFieldDto_InputProcessor",
                            "case \"tags\" -> out.put(k, applyStringCollection(")
                    // A raw collection and a nested container carry no element schema, so both take
                    // the annotated-OTHER arm rather than element-wise dispatch.
                    .assertGeneratedSourceContains(
                            "com.example.el.OrdinaryFieldDto_InputProcessor",
                            "case \"raw\" -> out.put(k, GeneratedSupport.applyDefault(")
                    .assertGeneratedSourceContains(
                            "com.example.el.OrdinaryFieldDto_InputProcessor",
                            "case \"nested\" -> out.put(k, GeneratedSupport.applyDefault(");
        }

        @Test
        @DisplayName("body discovery resolves a body collection's element through the same rule")
        void bodyDiscoveryResolvesTheSameElementType() {
            JavaFileObject resource = SourceFiles.inline("com.example.el.WeirdBodyResource", """
                    package com.example.el;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    @Path("/weird")
                    public class WeirdBodyResource {
                        @POST
                        public String create(ElWeird<ElOtherDto, ElLeafDto> body) { return null; }
                    }
                    """);

            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), LEAF_DTO, OTHER_DTO, WEIRD, resource);
            result.assertSuccess()
                    .assertGeneratedSourceContains("com.example.el.ElLeafDto_InputProcessor", "ElLeafDto");
            assertNoInputProcessorGenerated(result, "com.example.el.ElOtherDto_InputProcessor");
        }
    }

    // --- Map-typed fields ---

    /**
     * Pins the {@code Map} rule of {@code AnnotationCollector.buildFieldModel}: a {@code Map}-typed
     * field is <em>schema-free</em>, not a nested DTO.
     *
     * <p>The reflective engine's {@code InputPolicyMetadataResolver.isDescendableObject} excludes
     * {@code Map} deliberately — its keys are arbitrary, so it carries no statically known property
     * set — and drops an unannotated {@code Map} field entirely. When APT classified the same field
     * as {@code NESTED_DTO} the generated path emitted a {@code dispatchNested(v, Map.class, …)} arm
     * and declared {@code Map} as an owner, so one application reported a different
     * {@code InputValueContext.ownerType} for keys inside a {@code Map} field depending on whether
     * codegen was active. {@code FieldKind.OTHER}'s javadoc already listed {@code Map} among its
     * kinds; these tests hold the two paths together.
     */
    @Nested
    @DisplayName("Map-typed field classification")
    class MapTypedFields {

        @Test
        @DisplayName("unannotated Map field is not classified as a nested DTO — no field model at all")
        void mapTypedFieldIsNotClassifiedAsANestedDto() {
            JavaFileObject dto = SourceFiles.inline("com.example.MapDto", """
                    package com.example;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    import java.util.Map;
                    public class MapDto {
                        @Canonicalize(TrimCanonicalizer.class)
                        public String name;
                        public Map<String, String> attrs;
                    }
                    """);

            ProcessorTestHarness.run(new SanitizationProcessor(), dto, resourceFor("com.example.MapDto"))
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.MapDto_InputProcessor", "case \"name\"")
                    .assertGeneratedSourceDoesNotContain("com.example.MapDto_InputProcessor", "\"attrs\"")
                    .assertGeneratedSourceDoesNotContain("com.example.MapDto_InputProcessor", "owners.add(Map.class)");
        }

        @Test
        @DisplayName("annotated Map field is schema-free (OTHER) carrying its erased declared type")
        void annotatedMapTypedFieldIsSchemaFreeWithItsDeclaredType() {
            JavaFileObject dto = SourceFiles.inline("com.example.AnnotatedMapDto", """
                    package com.example;
                    import dev.vertique.core.sanitization.Sanitize;
                    import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
                    import java.util.Map;
                    public class AnnotatedMapDto {
                        @Sanitize(StripControlCharsSanitizer.class)
                        public Map<String, String> attrs;
                    }
                    """);

            ProcessorTestHarness.run(new SanitizationProcessor(), dto, resourceFor("com.example.AnnotatedMapDto"))
                    .assertSuccess()
                    // The OTHER arm routes through applyDefault, never dispatchNested.
                    .assertGeneratedSourceContains(
                            "com.example.AnnotatedMapDto_InputProcessor",
                            "case \"attrs\" -> out.put(k, GeneratedSupport.applyDefault(")
                    .assertGeneratedSourceDoesNotContain(
                            "com.example.AnnotatedMapDto_InputProcessor", "dispatchNested(")
                    // The erased declared type is handed to applyDefault as InputValueContext
                    // provenance for the reflective continuation, never as a field-name owner — its
                    // own metadata declares no fields, so no projection could ever be run against it.
                    .assertGeneratedSourceDoesNotContain(
                            "com.example.AnnotatedMapDto_InputProcessor", "owners.add(Map.class)");
        }

        @Test
        @DisplayName("Map subtype field classifies like Map — the test is assignability, not an FQN match")
        void mapSubtypeFieldIsClassifiedLikeMap() {
            JavaFileObject dto = SourceFiles.inline("com.example.MapSubtypeDto", """
                    package com.example;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    import java.util.HashMap;
                    public class MapSubtypeDto {
                        @Canonicalize(TrimCanonicalizer.class)
                        public String name;
                        public HashMap<String, String> attrs;
                    }
                    """);

            ProcessorTestHarness.run(new SanitizationProcessor(), dto, resourceFor("com.example.MapSubtypeDto"))
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.MapSubtypeDto_InputProcessor", "case \"name\"")
                    .assertGeneratedSourceDoesNotContain("com.example.MapSubtypeDto_InputProcessor", "\"attrs\"")
                    .assertGeneratedSourceDoesNotContain(
                            "com.example.MapSubtypeDto_InputProcessor", "owners.add(HashMap.class)");
        }
    }

    // --- Conflict detection ---

    @Nested
    @DisplayName("annotation conflict detection")
    class ConflictDetection {

        @Test
        @DisplayName("@Canonicalize and @SkipCanonicalization on same field — compilation error")
        void canonAndSkipCanon_sameField_compilationError() {
            JavaFileObject dto = SourceFiles.inline("com.example.ConflictDto", """
                    package com.example;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.core.sanitization.SkipCanonicalization;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    public class ConflictDto {
                        @Canonicalize(TrimCanonicalizer.class)
                        @SkipCanonicalization
                        public String name;
                    }
                    """);

            ProcessorTestHarness.run(new SanitizationProcessor(), dto, resourceFor("com.example.ConflictDto"))
                    .assertFailed()
                    .assertErrorMessage("mutually exclusive");
        }

        @Test
        @DisplayName("@Sanitize and @SkipSanitization on same field — compilation error")
        void sanitAndSkipSanit_sameField_compilationError() {
            JavaFileObject dto = SourceFiles.inline("com.example.SanitConflictDto", """
                    package com.example;
                    import dev.vertique.core.sanitization.Sanitize;
                    import dev.vertique.core.sanitization.SkipSanitization;
                    import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
                    public class SanitConflictDto {
                        @Sanitize(StripControlCharsSanitizer.class)
                        @SkipSanitization
                        public String content;
                    }
                    """);

            ProcessorTestHarness.run(new SanitizationProcessor(), dto, resourceFor("com.example.SanitConflictDto"))
                    .assertFailed()
                    .assertErrorMessage("mutually exclusive");
        }

        @Test
        @DisplayName("conflict error message contains the field name for easy diagnosis")
        void conflictErrorMessage_containsFieldName() {
            JavaFileObject dto = SourceFiles.inline("com.example.FieldNameConflictDto", """
                    package com.example;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.core.sanitization.SkipCanonicalization;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    public class FieldNameConflictDto {
                        @Canonicalize(TrimCanonicalizer.class)
                        @SkipCanonicalization
                        public String mySpecialField;
                    }
                    """);

            ProcessorTestHarness.run(new SanitizationProcessor(), dto, resourceFor("com.example.FieldNameConflictDto"))
                    .assertFailed()
                    .assertErrorMessage("mySpecialField");
        }
    }

    // --- Helper ---

    /**
     * Asserts that the compilation did NOT generate a source file for the given FQN.
     *
     * @param result the compilation result
     * @param fqn    the FQN that should NOT have been generated
     */
    private static void assertNoInputProcessorGenerated(Result result, String fqn) {
        boolean generated = result.compilation().generatedSourceFile(fqn).isPresent();
        if (generated) {
            throw new org.opentest4j.AssertionFailedError(
                    "Expected no generated source for '" + fqn + "' but one was found.");
        }
    }
}
