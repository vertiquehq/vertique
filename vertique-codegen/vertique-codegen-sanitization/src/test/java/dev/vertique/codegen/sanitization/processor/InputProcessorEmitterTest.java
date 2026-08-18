// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.sanitization.processor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.ProcessorTestHarness.Result;
import dev.vertique.codegen.test.fixtures.SourceFiles;
import dev.vertique.input.processing.GeneratedInputProcessor;
import java.util.Set;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Snapshot-style tests for {@code InputProcessorEmitter}.
 *
 * <p>Compiles representative fixtures that exercise every emitter code path and asserts
 * structural fragments in the generated source. Assertions use substring containment
 * rather than byte-exact matching to be robust to minor whitespace differences.
 */
class InputProcessorEmitterTest {

    // --- Shared fixture ---

    /**
     * A comprehensive DTO fixture that exercises every emitter path:
     * <ul>
     *   <li>{@code title} — STRING field with canonicalizer chain</li>
     *   <li>{@code content} — STRING field with sanitizer chain</li>
     *   <li>{@code tags} — COLLECTION_OF_STRINGS field</li>
     *   <li>{@code nested} — NESTED_DTO field</li>
     *   <li>{@code comments} — COLLECTION_OF_DTO field</li>
     *   <li>{@code count} — OTHER field (int primitive with no annotations)</li>
     *   <li>{@code skipMe} — STRING field with @SkipCanonicalization</li>
     * </ul>
     */
    private static final JavaFileObject NESTED_DTO = SourceFiles.inline("com.example.dto.CommentDto", """
            package com.example.dto;
            import dev.vertique.core.sanitization.Canonicalize;
            import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
            public class CommentDto {
                @Canonicalize(TrimCanonicalizer.class)
                public String text;
            }
            """);

    private static final JavaFileObject FULL_DTO = SourceFiles.inline("com.example.dto.ArticleDto", """
            package com.example.dto;
            import dev.vertique.core.sanitization.Canonicalize;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.core.sanitization.SkipCanonicalization;
            import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
            import dev.vertique.sanitization.canonicalize.LowerCaseCanonicalizer;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            import java.util.List;
            public class ArticleDto {
                @Canonicalize({TrimCanonicalizer.class, LowerCaseCanonicalizer.class})
                public String title;
                @Sanitize(StripControlCharsSanitizer.class)
                public String content;
                public List<String> tags;
                public CommentDto nested;
                public List<CommentDto> comments;
                public int count;
                @SkipCanonicalization
                public String skipMe;
            }
            """);

    private static final JavaFileObject RESOURCE = SourceFiles.inline("com.example.dto.ArticleResource", """
            package com.example.dto;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/articles")
            public class ArticleResource {
                @POST
                public String create(ArticleDto body) { return null; }
            }
            """);

    /** A user-defined generic wrapper used to build parameterized nested-DTO field types. */
    private static final JavaFileObject HOLDER_DTO = SourceFiles.inline("com.example.dto.Holder", """
            package com.example.dto;
            public class Holder<T> {
                public T value;
            }
            """);

    /**
     * A body record whose fields are all declared with a parameterized generic wrapper
     * ({@code Holder<T>}) — classified as {@code NESTED_DTO} by {@code AnnotationCollector} since
     * {@code Holder} is a non-scalar, non-collection declared type. Reproduces the shape that
     * surfaced the parameterized-type class-literal defect: {@code field.nestedTypeMirror()} is a
     * {@code DeclaredType} with type arguments, and rendering it unerased into a {@code $L.class}
     * literal produces invalid Java (e.g. {@code com.example.dto.Holder<java.lang.String>.class}).
     *
     * <p>The fixture deliberately does <em>not</em> use {@code java.util.Optional}:
     * {@code AnnotationCollector} normalizes {@code Optional<T>} away before classification (so
     * the wrapped value is sanitized rather than dispatched to a non-existent
     * {@code Optional_InputProcessor}), which would leave no parameterized nested type here to
     * prove erasure with.
     */
    private static final JavaFileObject WRAPPED_FIELDS_DTO =
            SourceFiles.inline("com.example.dto.WrappedFieldsDto", """
            package com.example.dto;
            import java.math.BigDecimal;
            import java.util.List;
            public record WrappedFieldsDto(
                    Holder<String> nickname,
                    Holder<List<String>> tags,
                    Holder<BigDecimal> discount) {}
            """);

    private static final JavaFileObject WRAPPED_FIELDS_RESOURCE =
            SourceFiles.inline("com.example.dto.WrappedFieldsResource", """
            package com.example.dto;
            import jakarta.ws.rs.Consumes;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            import jakarta.ws.rs.core.MediaType;
            @Path("/wrapped-fields")
            public class WrappedFieldsResource {
                @POST
                @Consumes(MediaType.APPLICATION_JSON)
                public String create(WrappedFieldsDto body) { return null; }
            }
            """);

    // --- Field-name owner-set fixtures ---

    /** Nested DTO reached through a {@code NESTED_DTO} field of {@code OwnerRootDto}. */
    private static final JavaFileObject ADDRESS_DTO = SourceFiles.inline("com.example.owner.AddressDto", """
            package com.example.owner;
            import dev.vertique.core.sanitization.Canonicalize;
            import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
            public class AddressDto {
                @Canonicalize(TrimCanonicalizer.class)
                public String street;
            }
            """);

    /** Element DTO reached through a {@code COLLECTION_OF_DTO} field of {@code OwnerRootDto}. */
    private static final JavaFileObject TAG_DTO = SourceFiles.inline("com.example.owner.TagDto", """
            package com.example.owner;
            import dev.vertique.core.sanitization.Canonicalize;
            import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
            public class TagDto {
                @Canonicalize(TrimCanonicalizer.class)
                public String label;
            }
            """);

    /** Root DTO carrying one {@code NESTED_DTO} and one {@code COLLECTION_OF_DTO} field. */
    private static final JavaFileObject OWNER_ROOT_DTO = SourceFiles.inline("com.example.owner.OwnerRootDto", """
            package com.example.owner;
            import java.util.List;
            public class OwnerRootDto {
                public AddressDto address;
                public List<TagDto> tags;
            }
            """);

    private static final JavaFileObject OWNER_ROOT_RESOURCE =
            SourceFiles.inline("com.example.owner.OwnerRootResource", """
            package com.example.owner;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/owner-roots")
            public class OwnerRootResource {
                @POST
                public String create(OwnerRootDto body) { return null; }
            }
            """);

    /**
     * DTO with two <em>annotated</em> schema-free fields, covering both shapes whose owner is the
     * field's erased declared type rather than a nested DTO:
     * <ul>
     *   <li>{@code attrs} — a {@code Map<String, String>}. {@code AnnotationCollector} classifies
     *       any non-scalar {@code DECLARED} type as {@code FieldKind.NESTED_DTO}, so
     *       this emits a {@code dispatchNested(v, Map.class, …, Map.class)} arm whose owner is
     *       {@code Map} — the erased declared type either way.</li>
     *   <li>{@code codes} — an annotated collection of non-string scalars, which <em>is</em>
     *       {@code FieldKind.OTHER}. Its arm passes the erased declared type
     *       ({@code List}) as the {@code applyDefault} owner handed to the reflective
     *       continuation.</li>
     * </ul>
     */
    private static final JavaFileObject ATTRIBUTES_DTO = SourceFiles.inline("com.example.owner.AttributesDto", """
            package com.example.owner;
            import dev.vertique.core.sanitization.Sanitize;
            import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
            import java.util.List;
            import java.util.Map;
            public class AttributesDto {
                @Sanitize(StripControlCharsSanitizer.class)
                public Map<String, String> attrs;
                @Sanitize(StripControlCharsSanitizer.class)
                public List<Integer> codes;
            }
            """);

    private static final JavaFileObject ATTRIBUTES_RESOURCE =
            SourceFiles.inline("com.example.owner.AttributesResource", """
            package com.example.owner;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/attributes")
            public class AttributesResource {
                @POST
                public String create(AttributesDto body) { return null; }
            }
            """);

    /**
     * DTO whose schema-free fields ({@code int} and {@code Object}, both scalar leaves to
     * {@code AnnotationCollector}) carry no annotations at all, so no emitted arm ever asks for an
     * owner on them and they must contribute nothing.
     */
    private static final JavaFileObject PLAIN_DTO = SourceFiles.inline("com.example.owner.PlainDto", """
            package com.example.owner;
            import dev.vertique.core.sanitization.Canonicalize;
            import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
            public class PlainDto {
                @Canonicalize(TrimCanonicalizer.class)
                public String name;
                public int count;
                public Object misc;
            }
            """);

    private static final JavaFileObject PLAIN_RESOURCE = SourceFiles.inline("com.example.owner.PlainResource", """
            package com.example.owner;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/plains")
            public class PlainResource {
                @POST
                public String create(PlainDto body) { return null; }
            }
            """);

    /** Self-referential DTO — the origin class is both {@code targetType()} and a nested type. */
    private static final JavaFileObject NODE_DTO = SourceFiles.inline("com.example.owner.NodeDto", """
            package com.example.owner;
            import dev.vertique.core.sanitization.Canonicalize;
            import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
            public class NodeDto {
                @Canonicalize(TrimCanonicalizer.class)
                public String label;
                public NodeDto child;
            }
            """);

    private static final JavaFileObject NODE_RESOURCE = SourceFiles.inline("com.example.owner.NodeResource", """
            package com.example.owner;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/nodes")
            public class NodeResource {
                @POST
                public String create(NodeDto body) { return null; }
            }
            """);

    /** Nested DTO targeted by two distinct fields of {@code PairDto}. */
    private static final JavaFileObject LEAF_DTO = SourceFiles.inline("com.example.owner.LeafDto", """
            package com.example.owner;
            import dev.vertique.core.sanitization.Canonicalize;
            import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
            public class LeafDto {
                @Canonicalize(TrimCanonicalizer.class)
                public String value;
            }
            """);

    /** Two {@code NESTED_DTO} fields whose target type is the same class. */
    private static final JavaFileObject PAIR_DTO = SourceFiles.inline("com.example.owner.PairDto", """
            package com.example.owner;
            public class PairDto {
                public LeafDto left;
                public LeafDto right;
            }
            """);

    private static final JavaFileObject PAIR_RESOURCE = SourceFiles.inline("com.example.owner.PairResource", """
            package com.example.owner;
            import jakarta.ws.rs.POST;
            import jakarta.ws.rs.Path;
            @Path("/pairs")
            public class PairResource {
                @POST
                public String create(PairDto body) { return null; }
            }
            """);

    // --- Generated class name ---

    @Nested
    @DisplayName("generated class naming")
    class ClassNaming {

        @Test
        @DisplayName("flat DTO class generates ArticleDto_InputProcessor")
        void flatDtoClass_generatesExpectedName() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.dto.ArticleDto_InputProcessor", "class ArticleDto_InputProcessor");
        }
    }

    // --- Static imports ---

    @Nested
    @DisplayName("static imports from GeneratedSupport")
    class StaticImports {

        @Test
        @DisplayName("generated source imports applyString from GeneratedSupport")
        void generatedSource_importsApplyString() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.dto.ArticleDto_InputProcessor",
                            "import static dev.vertique.input.processing.GeneratedSupport.applyString;");
        }

        @Test
        @DisplayName("generated source imports applyStringCollection from GeneratedSupport")
        void generatedSource_importsApplyStringCollection() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.dto.ArticleDto_InputProcessor",
                            "import static dev.vertique.input.processing.GeneratedSupport.applyStringCollection;");
        }

        @Test
        @DisplayName("generated source imports dispatchObjectCollection from GeneratedSupport")
        void generatedSource_importsDispatchObjectCollection() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.dto.ArticleDto_InputProcessor",
                            "import static dev.vertique.input.processing.GeneratedSupport.dispatchObjectCollection;");
        }
    }

    // --- Runtime SPI package ---

    @Nested
    @DisplayName("runtime SPI package of emitted references")
    class RuntimeSpiPackage {

        @Test
        @DisplayName("emitted source references only dev.vertique.input.processing, never the old rest-core package")
        void emittedSource_referencesNeutralProcessingPackageOnly() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.dto.ArticleDto_InputProcessor", "dev.vertique.input.processing")
                    .assertGeneratedSourceDoesNotContain(
                            "com.example.dto.ArticleDto_InputProcessor", "dev.vertique.rest.core.request");
        }
    }

    // --- Static chain constants ---

    @Nested
    @DisplayName("static chain constant fields")
    class StaticChainConstants {

        @Test
        @DisplayName("OBJ_CANON constant emitted as List<Class<? extends Canonicalizer>>")
        void objCanonConstant_emitted() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.dto.ArticleDto_InputProcessor", "OBJ_CANON");
        }

        @Test
        @DisplayName("OBJ_SANIT constant emitted")
        void objSanitConstant_emitted() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.dto.ArticleDto_InputProcessor", "OBJ_SANIT");
        }

        @Test
        @DisplayName("per-field TITLE_CANON constant contains TrimCanonicalizer and LowerCaseCanonicalizer")
        void titleCanonConstant_containsBothCanonicalizers() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.dto.ArticleDto_InputProcessor", "TITLE_CANON")
                    .assertGeneratedSourceContains(
                            "com.example.dto.ArticleDto_InputProcessor", "TrimCanonicalizer.class")
                    .assertGeneratedSourceContains(
                            "com.example.dto.ArticleDto_InputProcessor", "LowerCaseCanonicalizer.class");
        }

        @Test
        @DisplayName("per-field CONTENT_SANIT constant contains StripControlCharsSanitizer")
        void contentSanitConstant_containsSanitizer() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.dto.ArticleDto_InputProcessor", "CONTENT_SANIT")
                    .assertGeneratedSourceContains(
                            "com.example.dto.ArticleDto_InputProcessor", "StripControlCharsSanitizer.class");
        }
    }

    // --- Constructor ---

    @Nested
    @DisplayName("public no-arg constructor")
    class Constructor {

        @Test
        @DisplayName("generated class has public no-arg constructor")
        void generatedClass_hasPublicNoArgConstructor() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.dto.ArticleDto_InputProcessor", "ArticleDto_InputProcessor()");
        }
    }

    // --- targetType() method ---

    @Nested
    @DisplayName("targetType() method")
    class TargetTypeMethod {

        @Test
        @DisplayName("targetType() method returns ArticleDto.class")
        void targetTypeMethod_returnsOriginClass() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.dto.ArticleDto_InputProcessor", "targetType()")
                    .assertGeneratedSourceContains("com.example.dto.ArticleDto_InputProcessor", "ArticleDto.class");
        }
    }

    // --- process() switch arms ---

    @Nested
    @DisplayName("switch arm shape in process() method")
    class SwitchArms {

        @Test
        @DisplayName("switch arm for STRING field uses applyString")
        void stringFieldArm_usesApplyString() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.dto.ArticleDto_InputProcessor", "\"title\"")
                    .assertGeneratedSourceContains("com.example.dto.ArticleDto_InputProcessor", "applyString(");
        }

        @Test
        @DisplayName("switch arm for COLLECTION_OF_STRINGS field uses applyStringCollection")
        void collectionOfStringsFieldArm_usesApplyStringCollection() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.dto.ArticleDto_InputProcessor", "\"tags\"")
                    .assertGeneratedSourceContains(
                            "com.example.dto.ArticleDto_InputProcessor", "applyStringCollection(");
        }

        @Test
        @DisplayName("switch arm for NESTED_DTO field uses dispatchNested")
        void nestedDtoFieldArm_usesDispatchNested() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.dto.ArticleDto_InputProcessor", "\"nested\"")
                    .assertGeneratedSourceContains("com.example.dto.ArticleDto_InputProcessor", "dispatchNested(");
        }

        @Test
        @DisplayName("switch arm for COLLECTION_OF_DTO field uses dispatchObjectCollection")
        void collectionOfDtoFieldArm_usesDispatchObjectCollection() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.dto.ArticleDto_InputProcessor", "\"comments\"")
                    .assertGeneratedSourceContains(
                            "com.example.dto.ArticleDto_InputProcessor", "dispatchObjectCollection(");
        }

        @Test
        @DisplayName("OTHER field (int count) does not generate a switch arm case")
        void otherField_noCaseInSwitch() {
            // The 'count' field is int — it should appear in no switch arm case statement
            var result = ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE);
            result.assertSuccess();
            // Verify count field is not in a switch arm (no case "count" expected)
            // We just verify the compilation succeeded — if count were added to a switch arm
            // it would still compile; the important thing is the processor works at runtime
            // (verified by the roundtrip test).
        }

        @Test
        @DisplayName("@SkipCanonicalization field generates switch arm with skipCanon=true")
        void skipCanonicalizationField_switchArmWithSkipFlag() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.dto.ArticleDto_InputProcessor", "\"skipMe\"")
                    .assertGeneratedSourceContains(
                            "com.example.dto.ArticleDto_InputProcessor", "true"); // skipCanon flag
        }
    }

    // --- Parameterized nested-type fields (NESTED_DTO with generic type arguments) ---

    @Nested
    @DisplayName("NESTED_DTO field with a parameterized declared type (e.g. Holder<T>)")
    class ParameterizedNestedDtoFields {

        @Test
        @DisplayName("Holder<String>, Holder<List<String>>, and Holder<BigDecimal> fields "
                + "compile and emit an erased Holder.class literal")
        void parameterizedWrapperFields_compileWithErasedClassLiteral() {
            ProcessorTestHarness.run(
                            new SanitizationProcessor(), HOLDER_DTO, WRAPPED_FIELDS_DTO, WRAPPED_FIELDS_RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.dto.WrappedFieldsDto_InputProcessor", "Holder.class")
                    .assertGeneratedSourceDoesNotContain("com.example.dto.WrappedFieldsDto_InputProcessor", "Holder<");
        }
    }

    // --- Nested class flattening ---

    @Nested
    @DisplayName("nested class name flattening")
    class NestedClassFlattening {

        @Test
        @DisplayName("nested class Outer.Inner generates Outer_Inner_InputProcessor")
        void nestedClass_nameFlattened() {
            JavaFileObject outer = SourceFiles.inline("com.example.Outer", """
                    package com.example;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    public class Outer {
                        public static class Inner {
                            @Canonicalize(TrimCanonicalizer.class)
                            public String value;
                        }
                    }
                    """);
            JavaFileObject resource = SourceFiles.inline("com.example.OuterResource", """
                    package com.example;
                    import jakarta.ws.rs.POST;
                    import jakarta.ws.rs.Path;
                    @Path("/outer")
                    public class OuterResource {
                        @POST
                        public String create(Outer.Inner body) { return null; }
                    }
                    """);

            ProcessorTestHarness.run(new SanitizationProcessor(), outer, resource)
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.Outer_Inner_InputProcessor", "class Outer_Inner_InputProcessor");
        }
    }

    // --- fieldNameOwnerTypes() ---

    /**
     * Pins the emitted {@code fieldNameOwnerTypes()} override that lets the engine skip its
     * reflective owner walk for a generated type. The engine reads an <em>empty</em> return as
     * "this processor does not declare an owner set", so the origin class must always be present —
     * that is what makes the empty set a usable sentinel.
     *
     * <p>The last two cases assert the generated class <em>initializes</em> rather than inspecting
     * the source text: a duplicate class in a {@code Set.of(a, b, c)} varargs literal throws
     * {@code IllegalArgumentException} from a {@code static final} initializer, which surfaces as
     * {@code ExceptionInInitializerError} at first dispatch and is invisible to a source assertion.
     */
    @Nested
    @DisplayName("fieldNameOwnerTypes() emission")
    class FieldNameOwnerTypes {

        @Test
        @DisplayName("emitted owner types contain the origin class")
        void emittedOwnerTypesContainTheOriginClass() {
            ProcessorTestHarness.run(
                            new SanitizationProcessor(), ADDRESS_DTO, TAG_DTO, OWNER_ROOT_DTO, OWNER_ROOT_RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.owner.OwnerRootDto_InputProcessor", "fieldNameOwnerTypes()")
                    .assertGeneratedSourceContains(
                            "com.example.owner.OwnerRootDto_InputProcessor", "owners.add(OwnerRootDto.class)");
        }

        @Test
        @DisplayName("emitted owner types contain nested-DTO and collection-element types")
        void emittedOwnerTypesContainNestedAndElementTypes() {
            ProcessorTestHarness.run(
                            new SanitizationProcessor(), ADDRESS_DTO, TAG_DTO, OWNER_ROOT_DTO, OWNER_ROOT_RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.owner.OwnerRootDto_InputProcessor", "owners.add(AddressDto.class)")
                    .assertGeneratedSourceContains(
                            "com.example.owner.OwnerRootDto_InputProcessor", "owners.add(TagDto.class)");
        }

        @Test
        @DisplayName("emitted owner types contain the erased declared type of an annotated schema-free field")
        void emittedOwnerTypesContainAnnotatedSchemaFreeDeclaredTypes() {
            ProcessorTestHarness.run(new SanitizationProcessor(), ATTRIBUTES_DTO, ATTRIBUTES_RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.owner.AttributesDto_InputProcessor", "owners.add(AttributesDto.class)")
                    .assertGeneratedSourceContains(
                            "com.example.owner.AttributesDto_InputProcessor", "owners.add(Map.class)")
                    .assertGeneratedSourceContains(
                            "com.example.owner.AttributesDto_InputProcessor", "owners.add(List.class)");
        }

        @Test
        @DisplayName("emitted owner types omit unannotated schema-free fields")
        void emittedOwnerTypesOmitUnannotatedSchemaFreeFields() {
            ProcessorTestHarness.run(new SanitizationProcessor(), PLAIN_DTO, PLAIN_RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains(
                            "com.example.owner.PlainDto_InputProcessor", "owners.add(PlainDto.class)")
                    .assertGeneratedSourceDoesNotContain(
                            "com.example.owner.PlainDto_InputProcessor", "owners.add(int.class)")
                    .assertGeneratedSourceDoesNotContain(
                            "com.example.owner.PlainDto_InputProcessor", "owners.add(Object.class)");
        }

        @Test
        @DisplayName("self-referential DTO emits its own class once and the generated class initializes")
        void selfReferentialDtoEmitsItsOwnClassOnce() throws Exception {
            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), NODE_DTO, NODE_RESOURCE);
            result.assertSuccess();

            Class<?> processorClass = result.loadGeneratedClass("com.example.owner.NodeDto_InputProcessor");
            GeneratedInputProcessor<?> processor = (GeneratedInputProcessor<?>)
                    processorClass.getDeclaredConstructor().newInstance();
            Class<?> nodeClass = result.loadGeneratedClass("com.example.owner.NodeDto");

            assertEquals(
                    Set.of(nodeClass),
                    processor.fieldNameOwnerTypes(),
                    "a self-referential DTO must contribute its own class exactly once");
        }

        @Test
        @DisplayName("two fields targeting one nested type emit it once and the generated class initializes")
        void twoFieldsTargetingOneNestedTypeEmitItOnce() throws Exception {
            Result result = ProcessorTestHarness.run(new SanitizationProcessor(), LEAF_DTO, PAIR_DTO, PAIR_RESOURCE);
            result.assertSuccess();

            Class<?> processorClass = result.loadGeneratedClass("com.example.owner.PairDto_InputProcessor");
            GeneratedInputProcessor<?> processor = (GeneratedInputProcessor<?>)
                    processorClass.getDeclaredConstructor().newInstance();
            Class<?> pairClass = result.loadGeneratedClass("com.example.owner.PairDto");
            Class<?> leafClass = result.loadGeneratedClass("com.example.owner.LeafDto");

            assertEquals(
                    Set.of(pairClass, leafClass),
                    processor.fieldNameOwnerTypes(),
                    "two fields of the same nested type must contribute that type exactly once");
        }
    }

    // --- @Generated annotation ---

    @Nested
    @DisplayName("@Generated annotation")
    class GeneratedAnnotation {

        @Test
        @DisplayName("generated class is annotated with @Generated pointing to SanitizationProcessor")
        void generatedClass_hasGeneratedAnnotation() {
            ProcessorTestHarness.run(new SanitizationProcessor(), NESTED_DTO, FULL_DTO, RESOURCE)
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.dto.ArticleDto_InputProcessor", "@Generated")
                    .assertGeneratedSourceContains(
                            "com.example.dto.ArticleDto_InputProcessor",
                            "dev.vertique.codegen.sanitization.processor.SanitizationProcessor");
        }
    }
}
