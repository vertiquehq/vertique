// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests the one rule that names the class generated for a signal source interface, through a real
 * annotation-processing compilation of inline sources.
 */
class SignalSourceNamingTest {

    private static final String PACKAGE = "dev.vertique.examples";

    @Test
    @DisplayName("the generated name is <Interface>_SignalSource in the package, and joins a nested interface by _")
    void generatedNameIsStable() {
        // Given a top-level interface, a nested interface Outer.Inner, and an interface in the unnamed package
        List<JavaFileObject> sources = List.of(
                source(PACKAGE + ".Top", "package " + PACKAGE + ";\n\npublic interface Top {}\n"),
                source(
                        PACKAGE + ".Outer",
                        "package " + PACKAGE + ";\n\npublic final class Outer {\n    public interface Inner {}\n}\n"),
                source("Bare", "public interface Bare {}\n"));

        // When the rule is asked, from a processor, for the name of each
        Map<String, String> names = observe(sources);

        // Then the names are <Interface>_SignalSource in the interface's package, Outer_Inner_SignalSource nested,
        // and the simple name alone in the unnamed package
        assertEquals(PACKAGE + ".Top_SignalSource", names.get(PACKAGE + ".Top"));
        assertEquals(PACKAGE + ".Outer_Inner_SignalSource", names.get(PACKAGE + ".Outer.Inner"));
        assertEquals("Bare_SignalSource", names.get("Bare"));
    }

    // --- harness ---

    private static final List<String> NAMED = List.of(PACKAGE + ".Top", PACKAGE + ".Outer.Inner", "Bare");

    private static Map<String, String> observe(List<JavaFileObject> sources) {
        RuleProbeProcessor processor = new RuleProbeProcessor();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fileManager, diagnostics, List.of("--release", "21", "-proc:only"), null, sources);
            task.setProcessors(List.of(processor));
            boolean success = Boolean.TRUE.equals(task.call());
            assertTrue(success, () -> "the fixtures must compile but reported " + diagnostics.getDiagnostics());
        } catch (IOException e) {
            throw new AssertionError("Could not close the in-memory compiler", e);
        }
        return processor.generatedName;
    }

    private static JavaFileObject source(String fqn, String content) {
        return new InMemorySource(fqn, content);
    }

    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    private static final class RuleProbeProcessor extends AbstractProcessor {

        private final Map<String, String> generatedName = new LinkedHashMap<>();

        @Override
        public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
            if (roundEnvironment.processingOver() || !generatedName.isEmpty()) {
                return false;
            }
            for (String name : NAMED) {
                TypeElement type = processingEnv.getElementUtils().getTypeElement(name);
                generatedName.put(name, SignalSourceNaming.generatedName(type));
            }
            return false;
        }
    }

    private static final class InMemorySource extends SimpleJavaFileObject {

        private final String source;

        private InMemorySource(String className, String source) {
            super(
                    URI.create("string:///" + className.replace('.', '/') + JavaFileObject.Kind.SOURCE.extension),
                    JavaFileObject.Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
