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
