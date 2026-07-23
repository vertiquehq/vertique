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
 * Tests for the transitive-closure participation rules in {@code DtoScanner}.
 *
 * <p>Verifies which types are included in (or excluded from) the generated processor set based
 * on the direct-root rule, the subtree-annotation rule, and the cycle-protection rule.
 */
class DtoScannerTest {

    // --- Shared resource wrapper ---

    /**
     * Returns a JAX-RS resource that posts a body parameter of the given FQN.
     *
     * @param paramFqn the fully-qualified name of the body parameter type
     * @return source file for a {@code @Path} resource with a {@code @POST} method
     */
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

    // --- Direct root: unconditional emit ---

    @Nested
    @DisplayName("direct root: unconditional emit")
    class DirectRoot {

        @Test
        @DisplayName("@BODY root with no annotations — processor emitted unconditionally")
        void bodyRootWithNoAnnotations_emitted() {
            JavaFileObject dto = SourceFiles.inline("com.example.PlainDto", """
                    package com.example;
                    public class PlainDto {
                        public String value;
                    }
                    """);

            ProcessorTestHarness.run(new SanitizationProcessor(), dto, resourceFor("com.example.PlainDto"))
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.PlainDto_InputProcessor", "targetType()");
        }
    }

    // --- Nested DTO: conditional emit based on subtree annotations ---

    @Nested
    @DisplayName("nested DTO: conditional emit based on subtree annotations")
    class NestedDto {

        @Test
        @DisplayName("nested DTO with no annotations in subtree — NOT emitted")
        void nestedDtoWithNoAnnotations_notEmitted() {
            JavaFileObject nested = SourceFiles.inline("com.example.NestedDto", """
                    package com.example;
                    public class NestedDto {
                        public String data;
                    }
                    """);
            JavaFileObject root = SourceFiles.inline("com.example.RootDto", """
                    package com.example;
                    public class RootDto {
                        public NestedDto nested;
                    }
                    """);

            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), nested, root, resourceFor("com.example.RootDto"));
            result.assertSuccess();
            // Root is emitted unconditionally
            result.assertGeneratedSourceContains("com.example.RootDto_InputProcessor", "targetType()");
            // Nested has no annotations anywhere — NOT emitted
            assertNoInputProcessorGenerated(result, "com.example.NestedDto_InputProcessor");
        }

        @Test
        @DisplayName("mid-tier DTO with no direct annotations — skipped; subtree NOT traversed further")
        void nestedDtoWithDeeplyNestedAnnotation_emitted() {
            // The BFS walks an un-annotated mid-tier DTO without emitting it, so the annotated
            // leaf two levels deep is reachable and gets its own _InputProcessor. At runtime,
            // RootDto -> MidDto goes through the dispatcher's reflective continuation (because
            // MidDto has no generated processor), then MidDto -> DeepDto re-engages the
            // generated path. Both endpoints participate in the optimization.
            JavaFileObject deepNested = SourceFiles.inline("com.example.DeepDto", """
                    package com.example;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    public class DeepDto {
                        @Canonicalize(TrimCanonicalizer.class)
                        public String value;
                    }
                    """);
            JavaFileObject mid = SourceFiles.inline("com.example.MidDto", """
                    package com.example;
                    public class MidDto {
                        public DeepDto deep;
                    }
                    """);
            JavaFileObject root = SourceFiles.inline("com.example.RootDto", """
                    package com.example;
                    public class RootDto {
                        public MidDto mid;
                    }
                    """);

            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), deepNested, mid, root, resourceFor("com.example.RootDto"));
            result.assertSuccess();
            // Direct @BODY root is always emitted.
            result.assertGeneratedSourceContains("com.example.RootDto_InputProcessor", "targetType()");
            // The annotated leaf IS emitted, even though the mid-tier carrier is not.
            result.assertGeneratedSourceContains("com.example.DeepDto_InputProcessor", "targetType()");
            // The un-annotated mid-tier carrier is not emitted — reflective continuation handles it.
            assertNoInputProcessorGenerated(result, "com.example.MidDto_InputProcessor");
        }

        @Test
        @DisplayName("nested DTO whose own type has @Sanitize — emitted")
        void nestedDtoWithTypeLevelSanitize_emitted() {
            JavaFileObject nested = SourceFiles.inline("com.example.SanitizedNested", """
                    package com.example;
                    import dev.vertique.core.sanitization.Sanitize;
                    import dev.vertique.sanitization.sanitize.StripControlCharsSanitizer;
                    @Sanitize(StripControlCharsSanitizer.class)
                    public class SanitizedNested {
                        public String data;
                    }
                    """);
            JavaFileObject root = SourceFiles.inline("com.example.RootWithSanitized", """
                    package com.example;
                    public class RootWithSanitized {
                        public SanitizedNested nested;
                    }
                    """);

            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), nested, root, resourceFor("com.example.RootWithSanitized"));
            result.assertSuccess();
            result.assertGeneratedSourceContains("com.example.RootWithSanitized_InputProcessor", "targetType()");
            result.assertGeneratedSourceContains("com.example.SanitizedNested_InputProcessor", "targetType()");
        }

        @Test
        @DisplayName("nested DTO with only @SkipCanonicalization (no @Canonicalize) — emitted")
        void nestedDtoWithOnlySkipCanonicalization_emitted() {
            JavaFileObject nested = SourceFiles.inline("com.example.SkipDto", """
                    package com.example;
                    import dev.vertique.core.sanitization.SkipCanonicalization;
                    @SkipCanonicalization
                    public class SkipDto {
                        public String data;
                    }
                    """);
            JavaFileObject root = SourceFiles.inline("com.example.RootWithSkip", """
                    package com.example;
                    public class RootWithSkip {
                        public SkipDto nested;
                    }
                    """);

            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), nested, root, resourceFor("com.example.RootWithSkip"));
            result.assertSuccess();
            result.assertGeneratedSourceContains("com.example.SkipDto_InputProcessor", "targetType()");
        }

        @Test
        @DisplayName("List<NestedDto> field promotes element type to emit set when subtree participates")
        void listOfNestedDtoField_elementEmitted() {
            JavaFileObject elem = SourceFiles.inline("com.example.ElemDto", """
                    package com.example;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    public class ElemDto {
                        @Canonicalize(TrimCanonicalizer.class)
                        public String name;
                    }
                    """);
            JavaFileObject root = SourceFiles.inline("com.example.ListRootDto", """
                    package com.example;
                    import java.util.List;
                    public class ListRootDto {
                        public List<ElemDto> items;
                    }
                    """);

            Result result = ProcessorTestHarness.run(
                    new SanitizationProcessor(), elem, root, resourceFor("com.example.ListRootDto"));
            result.assertSuccess();
            result.assertGeneratedSourceContains("com.example.ListRootDto_InputProcessor", "targetType()");
            result.assertGeneratedSourceContains("com.example.ElemDto_InputProcessor", "targetType()");
        }
    }

    // --- Cycle protection ---

    @Nested
    @DisplayName("cycle protection")
    class CycleProtection {

        @Test
        @DisplayName("self-referencing DTO — compiles without infinite loop")
        void selfReferencingDto_compilesCleanly() {
            JavaFileObject dto = SourceFiles.inline("com.example.TreeNode", """
                    package com.example;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    public class TreeNode {
                        @Canonicalize(TrimCanonicalizer.class)
                        public String label;
                        public TreeNode parent;
                    }
                    """);

            ProcessorTestHarness.run(new SanitizationProcessor(), dto, resourceFor("com.example.TreeNode"))
                    .assertSuccess()
                    .assertGeneratedSourceContains("com.example.TreeNode_InputProcessor", "targetType()");
        }

        @Test
        @DisplayName("mutual cycle between two DTOs — compiles without infinite loop")
        void mutualCycle_compilesCleanly() {
            JavaFileObject a = SourceFiles.inline("com.example.CycleA", """
                    package com.example;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    public class CycleA {
                        @Canonicalize(TrimCanonicalizer.class)
                        public String name;
                        public CycleB partner;
                    }
                    """);
            JavaFileObject b = SourceFiles.inline("com.example.CycleB", """
                    package com.example;
                    import dev.vertique.core.sanitization.Canonicalize;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    public class CycleB {
                        @Canonicalize(TrimCanonicalizer.class)
                        public String data;
                        public CycleA back;
                    }
                    """);

            Result result =
                    ProcessorTestHarness.run(new SanitizationProcessor(), a, b, resourceFor("com.example.CycleA"));
            result.assertSuccess();
            result.assertGeneratedSourceContains("com.example.CycleA_InputProcessor", "targetType()");
            result.assertGeneratedSourceContains("com.example.CycleB_InputProcessor", "targetType()");
        }
    }

    // --- External type cutoff ---

    @Nested
    @DisplayName("external type cutoff")
    class ExternalTypeCutoff {

        @Test
        @DisplayName("nested type from JAR (external to CU) — no processor generated for it")
        void externalNestedType_notEmitted() {
            // TrimCanonicalizer is on the classpath (from vertique-sanitization) but was not
            // compiled in this CU. The scanner should NOT generate a processor for it.
            // We verify by using it as a nested field type in a DTO.
            JavaFileObject dto = SourceFiles.inline("com.example.WrapperDto", """
                    package com.example;
                    import dev.vertique.sanitization.canonicalize.TrimCanonicalizer;
                    public class WrapperDto {
                        public TrimCanonicalizer embedded;
                    }
                    """);

            Result result =
                    ProcessorTestHarness.run(new SanitizationProcessor(), dto, resourceFor("com.example.WrapperDto"));
            result.assertSuccess();
            // WrapperDto itself is in the CU, so it is emitted (direct root)
            result.assertGeneratedSourceContains("com.example.WrapperDto_InputProcessor", "targetType()");
            // TrimCanonicalizer is external (class-file origin) — no processor for it
            assertNoInputProcessorGenerated(
                    result, "dev.vertique.sanitization.canonicalize.TrimCanonicalizer_InputProcessor");
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
