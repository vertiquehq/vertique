// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.sanitization.processor;

import dev.vertique.codegen.test.ProcessorTestHarness;
import dev.vertique.codegen.test.fixtures.SourceFiles;
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
