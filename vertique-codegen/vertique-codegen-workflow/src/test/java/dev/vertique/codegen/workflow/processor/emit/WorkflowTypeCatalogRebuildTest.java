// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen.workflow.processor.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.vertique.codegen.workflow.processor.WorkflowContractProcessor;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that a second build of a module that already used the processor, without cleaning its output first, is
 * not mistaken for a build that collides with a dependency's generated module.
 *
 * <p>The module is compiled twice with the real compiler. Both passes write class files to the same output directory
 * and generated sources to the same directory, and the second pass finds the first pass's output on its classpath,
 * as a repeated build in the same working directory does.
 */
class WorkflowTypeCatalogRebuildTest {

    private static final String MODULE_FQN = "com.acme.GeneratedWorkflowTypeCatalogModule";

    private record Build(boolean success, List<String> errors, boolean generatedModule) {}

    @Test
    @DisplayName("a second build over the first build's class files compiles and regenerates the module")
    void secondBuildOverTheFirstBuildsClassFilesRegeneratesTheModule(@TempDir Path work) throws IOException {
        List<Path> sources = writeSources(work.resolve("src"));
        Path classes = Files.createDirectories(work.resolve("classes"));
        Path generated = Files.createDirectories(work.resolve("generated"));

        Build first = compile(sources, classes, generated, false);
        Build second = compile(sources, classes, generated, false);

        assertTrue(first.success(), "the first build must compile but got " + first.errors());
        assertTrue(first.generatedModule(), "the first build must generate the module");
        assertEquals(List.of(), second.errors(), "the second build must report no error");
        assertTrue(second.success(), "the second build must compile");
        assertTrue(second.generatedModule(), "the second build must generate the module again");
    }

    @Test
    @DisplayName(
            "a second build that also finds the first build's generated source compiles and regenerates the module")
    void secondBuildThatAlsoFindsTheFirstBuildsGeneratedSourceRegeneratesTheModule(@TempDir Path work)
            throws IOException {
        List<Path> sources = writeSources(work.resolve("src"));
        Path classes = Files.createDirectories(work.resolve("classes"));
        Path generated = Files.createDirectories(work.resolve("generated"));

        Build first = compile(sources, classes, generated, true);
        Build second = compile(sources, classes, generated, true);

        assertTrue(first.success(), "the first build must compile but got " + first.errors());
        assertTrue(first.generatedModule(), "the first build must generate the module");
        assertEquals(List.of(), second.errors(), "the second build must report no error");
        assertTrue(second.success(), "the second build must compile");
        assertTrue(second.generatedModule(), "the second build must generate the module again");
    }

    // --- Helpers ---

    private static List<Path> writeSources(Path dir) throws IOException {
        Path pkg = Files.createDirectories(dir.resolve("com/acme"));
        Path state = pkg.resolve("OrderState.java");
        Files.writeString(state, """
                package com.acme;
                import dev.vertique.workflow.types.WorkflowType;
                @WorkflowType("order")
                public class OrderState {}
                """);
        Path paid = pkg.resolve("Paid.java");
        Files.writeString(paid, """
                package com.acme;
                import dev.vertique.workflow.types.WorkflowType;
                @WorkflowType("order.paid")
                public class Paid {}
                """);
        return List.of(state, paid);
    }

    /**
     * Runs the workflow processor over the sources with the output directory on the classpath and, when asked, the
     * generated-source directory on the source path.
     */
    private static Build compile(List<Path> sources, Path classes, Path generated, boolean generatedOnSourcePath)
            throws IOException {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null, null)) {
            files.setLocation(StandardLocation.CLASS_OUTPUT, List.of(classes.toFile()));
            files.setLocation(StandardLocation.SOURCE_OUTPUT, List.of(generated.toFile()));
            List<File> classpath = new ArrayList<>();
            for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
                classpath.add(new File(entry));
            }
            classpath.add(classes.toFile());
            files.setLocation(StandardLocation.CLASS_PATH, classpath);
            if (generatedOnSourcePath) {
                files.setLocation(StandardLocation.SOURCE_PATH, List.of(generated.toFile()));
            }
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    files,
                    diagnostics,
                    List.of("--release", "21"),
                    null,
                    files.getJavaFileObjectsFromPaths(sources));
            task.setProcessors(List.of(new WorkflowContractProcessor()));
            boolean success = task.call();
            List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> d.getMessage(null))
                    .toList();
            boolean generatedModule = Files.exists(generated.resolve(MODULE_FQN.replace('.', '/') + ".java"));
            return new Build(success, errors, generatedModule);
        }
    }
}
