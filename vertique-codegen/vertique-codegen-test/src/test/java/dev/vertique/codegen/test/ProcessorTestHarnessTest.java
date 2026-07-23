// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.vertique.codegen.test.fixtures.SourceFiles;
import javax.tools.JavaFileObject;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

/**
 * Tests for {@link ProcessorTestHarness}, including the PRD CG-001 acceptance-criterion #2
 * smoke test using {@link DeprecatedSmokeProcessor}.
 */
class ProcessorTestHarnessTest {

    // --- Smoke test (PRD AC #2) ---

    @Test
    void smokeTest_deprecatedProcessor_generatesMarker() {
        JavaFileObject source = SourceFiles.inline("com.example.MyOldApi", """
                        package com.example;

                        @Deprecated
                        public class MyOldApi {}
                        """);

        ProcessorTestHarness.run(new DeprecatedSmokeProcessor(), source)
                .assertSuccess()
                .assertGeneratedSourceContains(
                        "dev.vertique.codegen.test.generated.MyOldApiMarker", "// generated for com.example.MyOldApi");
    }

    // --- assertSuccess / assertFailed ---

    @Test
    void assertSuccess_passesForValidSource() {
        JavaFileObject source = SourceFiles.inline("com.example.Valid", """
                        package com.example;
                        public class Valid {}
                        """);

        ProcessorTestHarness.run(new DeprecatedSmokeProcessor(), source).assertSuccess();
    }

    @Test
    void assertFailed_passesForInvalidSource() {
        JavaFileObject source = SourceFiles.inline("com.example.Broken", """
                        package com.example;
                        public class Broken {
                            this is not valid java
                        }
                        """);

        ProcessorTestHarness.run(new DeprecatedSmokeProcessor(), source).assertFailed();
    }

    @Test
    void assertSuccess_throwsAssertionFailedErrorForBrokenSource() {
        JavaFileObject source = SourceFiles.inline("com.example.Broken", """
                        package com.example;
                        public class Broken {
                            this is not valid java
                        }
                        """);

        assertThrows(AssertionFailedError.class, () -> ProcessorTestHarness.run(new DeprecatedSmokeProcessor(), source)
                .assertSuccess());
    }

    @Test
    void assertFailed_throwsAssertionFailedErrorForValidSource() {
        JavaFileObject source = SourceFiles.inline("com.example.Valid", """
                        package com.example;
                        public class Valid {}
                        """);

        assertThrows(AssertionFailedError.class, () -> ProcessorTestHarness.run(new DeprecatedSmokeProcessor(), source)
                .assertFailed());
    }

    // --- assertGeneratedSourceContains ---

    @Test
    void assertGeneratedSourceContains_throwsWhenSnippetMissing() {
        JavaFileObject source = SourceFiles.inline("com.example.MyOldApi", """
                        package com.example;

                        @Deprecated
                        public class MyOldApi {}
                        """);

        assertThrows(AssertionFailedError.class, () -> ProcessorTestHarness.run(new DeprecatedSmokeProcessor(), source)
                .assertSuccess()
                .assertGeneratedSourceContains(
                        "dev.vertique.codegen.test.generated.MyOldApiMarker", "// this snippet does not exist"));
    }

    @Test
    void assertGeneratedSourceContains_throwsWhenFileNotGenerated() {
        // Use a non-deprecated type — no marker should be generated
        JavaFileObject source = SourceFiles.inline("com.example.NewApi", """
                        package com.example;
                        public class NewApi {}
                        """);

        assertThrows(AssertionFailedError.class, () -> ProcessorTestHarness.run(new DeprecatedSmokeProcessor(), source)
                .assertSuccess()
                .assertGeneratedSourceContains(
                        "dev.vertique.codegen.test.generated.NewApiMarker", "// generated for com.example.NewApi"));
    }

    // --- assertErrorMessage ---

    @Test
    void assertErrorMessage_passesWhenErrorContainsSubstring() {
        JavaFileObject source = SourceFiles.inline("com.example.Broken", """
                        package com.example;

                        @Deprecated
                        public class Broken {}
                        """);

        // Use a processor we control to emit a known error string; assertErrorMessage relying on
        // javac's own diagnostic wording would be locale- and JDK-version-fragile.
        ProcessorTestHarness.run(new ErrorEmittingProcessor("vertique-codegen sentinel error"), source)
                .assertFailed()
                .assertErrorMessage("vertique-codegen sentinel error");
    }

    @Test
    void assertErrorMessage_throwsWhenNoMatchingError() {
        JavaFileObject source = SourceFiles.inline("com.example.Valid", """
                        package com.example;
                        public class Valid {}
                        """);

        assertThrows(AssertionFailedError.class, () -> ProcessorTestHarness.run(new DeprecatedSmokeProcessor(), source)
                .assertErrorMessage("this error does not exist"));
    }

    // --- assertNoWarnings ---

    @Test
    void assertNoWarnings_passesForCleanCompilation() {
        // Doubles as a regression guard: DeprecatedSmokeProcessor declares
        // @SupportedSourceVersion(RELEASE_21). Without the harness pinning --release 21, a host
        // JDK newer than 21 would emit a "supported source version less than -source" warning
        // that would flip this assertion to failure.
        JavaFileObject source = SourceFiles.inline("com.example.Clean", """
                        package com.example;
                        public class Clean {}
                        """);

        ProcessorTestHarness.run(new DeprecatedSmokeProcessor(), source)
                .assertSuccess()
                .assertNoWarnings();
    }

    // --- Result record is not null ---

    @Test
    void run_returnsNonNullResult() {
        JavaFileObject source = SourceFiles.inline("com.example.Foo", """
                        package com.example;
                        public class Foo {}
                        """);

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(new DeprecatedSmokeProcessor(), source);

        assertNotNull(result);
        assertNotNull(result.compilation());
    }

    // --- Options overload ---

    @Test
    void run_withOptions_setsProcessorOption() {
        JavaFileObject source = SourceFiles.inline("com.example.Foo", """
                        package com.example;
                        public class Foo {}
                        """);

        // Just verify this overload doesn't throw and produces a result
        ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                new DeprecatedSmokeProcessor(),
                java.util.Map.of("vertique.codegen.package", "com.example.gen"),
                source);

        assertNotNull(result);
    }

    // --- Iterable<Processor> + options overload ---

    /**
     * Verifies that the {@code run(Iterable, Map, JavaFileObject...)} overload threads {@code -A}
     * options to every processor in the collection.
     *
     * <p>Two in-test processors are used:
     * <ul>
     *   <li>{@code OptionReadingProcessor} — reads the {@code test.sentinel} option and emits an
     *       error if it is absent or blank, proving the option reached it.</li>
     *   <li>{@link DeprecatedSmokeProcessor} — present to confirm a second processor coexists
     *       without conflict and can still generate files in the same compilation.</li>
     * </ul>
     */
    @Test
    void run_iterableWithOptions_optionReachesAllProcessors() {
        JavaFileObject source = SourceFiles.inline("com.example.Subject", """
                        package com.example;

                        @Deprecated
                        public class Subject {}
                        """);

        // OptionReadingProcessor reads "test.sentinel" option and emits an ERROR if absent,
        // proving the option reached it. DeprecatedSmokeProcessor generates a marker file,
        // proving both processors ran.
        ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                java.util.List.of(new OptionReadingProcessor("test.sentinel"), new DeprecatedSmokeProcessor()),
                java.util.Map.of("test.sentinel", "hello-from-harness"),
                source);

        // Both processors run; OptionReadingProcessor should succeed because the option is set.
        result.assertSuccess();

        // DeprecatedSmokeProcessor still generates the marker — second processor ran fine.
        result.assertGeneratedSourceContains(
                "dev.vertique.codegen.test.generated.SubjectMarker", "// generated for com.example.Subject");
    }

    @Test
    void run_iterableWithOptions_missingOptionCausesExpectedError() {
        JavaFileObject source = SourceFiles.inline("com.example.Subject2", """
                        package com.example;
                        public class Subject2 {}
                        """);

        // When the option is absent, OptionReadingProcessor emits a predictable error
        ProcessorTestHarness.Result result = ProcessorTestHarness.run(
                java.util.List.of(new OptionReadingProcessor("test.sentinel")),
                java.util.Map.of(), // no options
                source);

        result.assertFailed().assertErrorMessage("test.sentinel option not set");
    }

    // --- loadGeneratedClass tests ---

    /**
     * Verifies that {@link ProcessorTestHarness.Result#loadGeneratedClass} returns a non-null
     * {@link Class} whose {@link Class#getName()} matches the requested FQN.
     *
     * <p>Uses {@link GeneratingProcessor} which emits a minimal no-arg-constructor class at
     * {@code com.example.Generated} when it sees {@code @Deprecated} on a type.
     */
    @Test
    void loadGeneratedClass_returnsGeneratedClass() {
        JavaFileObject source = SourceFiles.inline("com.example.Subject", """
                        package com.example;

                        @Deprecated
                        public class Subject {}
                        """);

        ProcessorTestHarness.Result result =
                ProcessorTestHarness.run(new GeneratingProcessor("com.example", "Generated"), source);
        result.assertSuccess();

        Class<?> generated = result.loadGeneratedClass("com.example.Generated");

        assertNotNull(generated);
        org.junit.jupiter.api.Assertions.assertEquals("com.example.Generated", generated.getName());
    }

    /**
     * Verifies that {@link ProcessorTestHarness.Result#loadGeneratedClass} throws an
     * {@link AssertionFailedError} with a useful message when the requested FQN was not
     * produced by the compilation.
     */
    @Test
    void loadGeneratedClass_throwsOnMissing() {
        JavaFileObject source = SourceFiles.inline("com.example.Subject", """
                        package com.example;
                        public class Subject {}
                        """);

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(new DeprecatedSmokeProcessor(), source);
        result.assertSuccess();

        assertThrows(AssertionFailedError.class, () -> result.loadGeneratedClass("com.example.DoesNotExist"));
    }

    /**
     * Verifies that a class loaded via {@link ProcessorTestHarness.Result#loadGeneratedClass} is
     * fully usable — its no-arg constructor can be reflectively invoked to produce a live instance.
     *
     * <p>This proves the class is loaded into a context where it can be instantiated, not merely
     * resolved as a stub.
     */
    @Test
    void loadGeneratedClass_canInstantiateNoArgConstructor() throws Exception {
        JavaFileObject source = SourceFiles.inline("com.example.Target", """
                        package com.example;

                        @Deprecated
                        public class Target {}
                        """);

        ProcessorTestHarness.Result result =
                ProcessorTestHarness.run(new GeneratingProcessor("com.example", "Generated"), source);
        result.assertSuccess();

        Class<?> generated = result.loadGeneratedClass("com.example.Generated");
        Object instance = generated.getDeclaredConstructor().newInstance();

        assertNotNull(instance);
    }

    /**
     * Verifies that classes produced by the compilation are defined by the harness's own
     * classloader rather than the parent test-classpath loader. Asserts the
     * {@code defineClass} call site sits inside the harness — a precondition for the
     * collision-behaviour tests below to mean anything.
     *
     * <p>The actual parent-classpath collision proofs live in
     * {@link #loadGeneratedClass_rejectsClassPresentOnParentButNotProduced} and
     * {@link #loadGeneratedClass_returnsCompiledBytesWhenFqnExistsOnBoth}.
     */
    @Test
    void loadGeneratedClass_classLoaderIsHarnessLoader() {
        JavaFileObject source = SourceFiles.inline("com.example.Subject", """
                        package com.example;

                        @Deprecated
                        public class Subject {}
                        """);

        ProcessorTestHarness.Result result =
                ProcessorTestHarness.run(new GeneratingProcessor("com.example", "Generated"), source);
        result.assertSuccess();

        Class<?> generated = result.loadGeneratedClass("com.example.Generated");

        ClassLoader loader = generated.getClassLoader();
        assertNotNull(loader, "ClassLoader should not be null for a non-bootstrap class");
        // The harness loader is a private static inner class — we can't reference it by name,
        // but we can assert that the loader is NOT the test-classloader parent, confirming
        // that the harness defined the class rather than delegating up.
        org.junit.jupiter.api.Assertions.assertNotSame(
                ProcessorTestHarness.class.getClassLoader(),
                loader,
                "Expected class to be defined by the harness classloader, not the parent");
    }

    /**
     * Verifies that user-supplied source fixture classes compiled during the same run are also
     * loadable via {@link ProcessorTestHarness.Result#loadGeneratedClass}.
     *
     * <p>This test documents that {@link com.google.testing.compile.Compilation#generatedFiles()}
     * includes {@code .class} files for both processor-generated sources <em>and</em> user-supplied
     * inline-fixture sources, so a single classloader covers both categories.
     *
     * <p>If this test fails it means user-source {@code .class} files are not exposed in
     * {@code generatedFiles()}, and the roundtrip strategy would need adjustment (e.g., move
     * fixtures to real test-source directories on the test classpath).
     */
    @Test
    void loadGeneratedClass_loadsUserSourceClassFromInlineFixture() throws Exception {
        // com.example.Subject is a user-supplied inline fixture — not generated by a processor.
        JavaFileObject source = SourceFiles.inline("com.example.Subject", """
                        package com.example;
                        public class Subject {
                            public String hello() { return "hello"; }
                        }
                        """);

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(new DeprecatedSmokeProcessor(), source);
        result.assertSuccess();

        Class<?> subjectClass = result.loadGeneratedClass("com.example.Subject");
        assertNotNull(subjectClass);
        org.junit.jupiter.api.Assertions.assertEquals("com.example.Subject", subjectClass.getName());

        // Prove it is fully usable — call the method reflectively
        Object instance = subjectClass.getDeclaredConstructor().newInstance();
        Object returned = subjectClass.getMethod("hello").invoke(instance);
        org.junit.jupiter.api.Assertions.assertEquals("hello", returned);
    }

    /**
     * Strict-contract regression: an FQN that exists on the parent (test) classpath but was NOT
     * produced by this compilation must be rejected by
     * {@link ProcessorTestHarness.Result#loadGeneratedClass}, not silently returned via parent
     * delegation. {@link CollisionFixture} is on the test classpath, so a naive "delegate to
     * the loader" implementation would happily return its parent-classpath copy.
     */
    @Test
    void loadGeneratedClass_rejectsClassPresentOnParentButNotProduced() {
        JavaFileObject source = SourceFiles.inline("com.example.Subject", """
                        package com.example;
                        public class Subject {}
                        """);

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(new DeprecatedSmokeProcessor(), source);
        result.assertSuccess();

        // CollisionFixture is on the test classpath but the compilation above didn't produce it.
        // The strict contract requires loadGeneratedClass to reject this — even though the parent
        // classpath could happily resolve the FQN.
        assertThrows(
                AssertionFailedError.class,
                () -> result.loadGeneratedClass("dev.vertique.codegen.test.CollisionFixture"));
    }

    /**
     * True parent-classpath collision regression: when an FQN is produced by the compilation
     * <em>and</em> exists on the parent (test) classpath, the loader must return the in-memory
     * compiled bytes — not the parent's copy. The two copies are distinguished by the value of
     * {@link CollisionFixture#SOURCE} (parent says {@code "test-classpath"}; the inline fixture
     * says {@code "compiled-bytes"}).
     */
    @Test
    void loadGeneratedClass_returnsCompiledBytesWhenFqnExistsOnBoth() throws Exception {
        // Inline source uses the same FQN as the parent-classpath CollisionFixture but with a
        // different SOURCE value. The compilation produces a .class file whose bytes carry the
        // overridden value; we expect that copy to win over the parent's.
        JavaFileObject collidingSource = SourceFiles.inline("dev.vertique.codegen.test.CollisionFixture", """
                        package dev.vertique.codegen.test;
                        public final class CollisionFixture {
                            public static final String SOURCE = "compiled-bytes";
                            private CollisionFixture() {}
                        }
                        """);

        ProcessorTestHarness.Result result = ProcessorTestHarness.run(new DeprecatedSmokeProcessor(), collidingSource);
        result.assertSuccess();

        Class<?> compiledFixture = result.loadGeneratedClass("dev.vertique.codegen.test.CollisionFixture");
        Object marker = compiledFixture.getField("SOURCE").get(null);

        org.junit.jupiter.api.Assertions.assertEquals(
                "compiled-bytes",
                marker,
                "loadGeneratedClass returned the parent-classpath copy instead of the compiled bytes");
        // Sanity: the parent's copy is unaffected and still says "test-classpath".
        org.junit.jupiter.api.Assertions.assertEquals("test-classpath", CollisionFixture.SOURCE);
    }

    // --- In-test fixture processors ---

    /**
     * In-test fixture processor that generates a single named source file when it encounters a
     * {@code @Deprecated} annotation. Used to prove that {@link ProcessorTestHarness.Result#loadGeneratedClass}
     * can load a processor-generated class.
     *
     * <p>The generated class is a minimal public class with a no-arg constructor in the requested
     * package and with the requested simple name.
     */
    @javax.annotation.processing.SupportedAnnotationTypes("java.lang.Deprecated")
    @javax.annotation.processing.SupportedSourceVersion(javax.lang.model.SourceVersion.RELEASE_21)
    static final class GeneratingProcessor extends javax.annotation.processing.AbstractProcessor {

        private final String packageName;
        private final String simpleName;

        GeneratingProcessor(String packageName, String simpleName) {
            this.packageName = packageName;
            this.simpleName = simpleName;
        }

        @Override
        public boolean process(
                java.util.Set<? extends javax.lang.model.element.TypeElement> annotations,
                javax.annotation.processing.RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()
                    || roundEnv.getElementsAnnotatedWith(Deprecated.class).isEmpty()) {
                return false;
            }
            String fqn = packageName + "." + simpleName;
            try {
                javax.tools.JavaFileObject file = processingEnv.getFiler().createSourceFile(fqn);
                try (java.io.PrintWriter pw = new java.io.PrintWriter(file.openWriter())) {
                    pw.println("package " + packageName + ";");
                    pw.println("/** Generated by GeneratingProcessor. */");
                    pw.println("public class " + simpleName + " {");
                    pw.println("    public " + simpleName + "() {}");
                    pw.println("}");
                }
            } catch (java.io.IOException e) {
                processingEnv
                        .getMessager()
                        .printMessage(javax.tools.Diagnostic.Kind.ERROR, "Failed to generate: " + e.getMessage());
            }
            return false;
        }
    }

    /**
     * In-test fixture processor used to verify that {@code -A} options are threaded through when
     * running multiple processors via
     * {@link ProcessorTestHarness#run(Iterable, java.util.Map, javax.tools.JavaFileObject...)}.
     *
     * <p>Reads the option named by {@link #optionKey} on the first processing round. If the option
     * is absent or blank it emits a compilation {@link javax.tools.Diagnostic.Kind#ERROR} with the
     * message "{@code <key> option not set}". This gives the test a deterministic signal that the
     * option reached the processor.
     */
    @javax.annotation.processing.SupportedAnnotationTypes("*")
    @javax.annotation.processing.SupportedSourceVersion(javax.lang.model.SourceVersion.RELEASE_21)
    static final class OptionReadingProcessor extends javax.annotation.processing.AbstractProcessor {

        private final String optionKey;

        OptionReadingProcessor(String optionKey) {
            this.optionKey = optionKey;
        }

        @Override
        public java.util.Set<String> getSupportedOptions() {
            return java.util.Set.of(optionKey);
        }

        @Override
        public boolean process(
                java.util.Set<? extends javax.lang.model.element.TypeElement> annotations,
                javax.annotation.processing.RoundEnvironment roundEnv) {
            if (roundEnv.processingOver()) {
                return false;
            }
            String value = processingEnv.getOptions().get(optionKey);
            if (value == null || value.isBlank()) {
                processingEnv
                        .getMessager()
                        .printMessage(javax.tools.Diagnostic.Kind.ERROR, optionKey + " option not set");
            }
            return false;
        }
    }
}
