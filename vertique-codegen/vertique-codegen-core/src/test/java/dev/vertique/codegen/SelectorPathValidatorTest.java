// SPDX-FileCopyrightText: 2026 Koivisto Capital Oy
// SPDX-License-Identifier: EUPL-1.2

package dev.vertique.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.util.ElementFilter;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * T003 TP-001 — verifies every shared selector grammar violation through a real annotation-processing
 * compilation and proves that family prefixes change only the diagnostic prefix.
 */
class SelectorPathValidatorTest {

    private static final List<String> FAMILIES = List.of("cache key", "rate-limit key");

    @ParameterizedTest(name = "{0}")
    @MethodSource("grammarViolations")
    @DisplayName("reports every grammar violation with the family prefix")
    void reportsEveryGrammarViolationWithTheFamilyPrefix(
            String rowName, String path, String fixture, String expectedSuffix) {
        String firstDiagnostic = null;

        for (String family : FAMILIES) {
            // Given: one invalid selector fixture and one family prefix.
            CompilationResult result = compile(fixture, new SelectorProbeProcessor(family, path));

            // When: the probe processor validates the fixture method's selector path.
            // Then: the exact family-prefixed violation is reported.
            String expected = family + " " + expectedSuffix;
            assertFalse(result.success(), rowName + " must reject the invalid selector");
            String actual = result.errorMessages().stream()
                    .filter(message -> message.equals(expected))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Expected diagnostic '" + expected + "' but got " + result.errorMessages()));
            assertEquals(expected, actual, rowName + " must report its own violation kind");

            if (firstDiagnostic == null) {
                firstDiagnostic = actual.substring("cache key ".length());
            } else {
                assertEquals(
                        firstDiagnostic,
                        actual.substring("rate-limit key ".length()),
                        rowName + " must differ only by its family prefix");
            }
        }
    }

    static Stream<Arguments> grammarViolations() {
        return Stream.of(
                Arguments.of("blankPath", "", probeSource("String value"), "selector path must not be blank"),
                Arguments.of(
                        "pathExceeds256Characters",
                        "x".repeat(257),
                        probeSource("String value"),
                        "selector path must not exceed 256 characters"),
                Arguments.of(
                        "pathHasNineSegments",
                        "0.a.b.c.d.e.f.g.h",
                        probeSource("String value"),
                        "property paths are limited to eight segments including the root parameter"),
                Arguments.of(
                        "pathHasInvalidIdentifier",
                        "0.bad-name",
                        probeSource("String value"),
                        "property path contains an invalid identifier: bad-name"),
                Arguments.of(
                        "rootDoesNotResolveToParameter",
                        "missing",
                        probeSource("String value"),
                        "selector does not resolve to a method parameter: missing"),
                Arguments.of(
                        "pathHasNoAccessor",
                        "0.missing",
                        probeSource("Holder value", "static final class Holder {}"),
                        "property is not an accessible record or bean accessor: missing"),
                Arguments.of(
                        "pathEndsInUnsupportedScalar",
                        "0",
                        probeSource("Object value"),
                        "selector must end in a supported scalar type"));
    }

    private static String probeSource(String parameter) {
        return probeSource(parameter, "");
    }

    private static String probeSource(String parameter, String additionalType) {
        return """
                package com.example;

                public class ProbeBean {
                    public void probe(%s) {}
                %s
                }
                """.formatted(parameter, additionalType);
    }

    private static CompilationResult compile(String fixture, SelectorProbeProcessor processor) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of("--release", "21", "-proc:only"),
                    null,
                    List.of(new InMemorySource("com.example.ProbeBean", fixture)));
            task.setProcessors(List.of(processor));
            boolean success = Boolean.TRUE.equals(task.call());
            return CompilationResult.from(success, diagnostics);
        } catch (IOException e) {
            throw new AssertionError("Could not close the in-memory compiler", e);
        }
    }

    private record CompilationResult(boolean success, List<String> errorMessages) {

        private static CompilationResult from(boolean success, DiagnosticCollector<JavaFileObject> diagnostics) {
            return new CompilationResult(
                    success,
                    diagnostics.getDiagnostics().stream()
                            .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                            .map(diagnostic -> diagnostic.getMessage(Locale.ROOT))
                            .toList());
        }
    }

    @SupportedAnnotationTypes("*")
    @SupportedSourceVersion(SourceVersion.RELEASE_21)
    private static final class SelectorProbeProcessor extends AbstractProcessor {

        private final String family;
        private final String path;

        private SelectorProbeProcessor(String family, String path) {
            this.family = family;
            this.path = path;
        }

        @Override
        public boolean process(java.util.Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
            if (roundEnvironment.processingOver()) {
                return false;
            }
            TypeElement bean = processingEnv.getElementUtils().getTypeElement("com.example.ProbeBean");
            ExecutableElement method = ElementFilter.methodsIn(bean.getEnclosedElements()).stream()
                    .filter(candidate -> candidate.getSimpleName().contentEquals("probe"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("Probe fixture is missing probe()"));

            // Given: the compiler's real processing environment and the fixture method.
            CodegenContext context = new CodegenContext(processingEnv);

            // When: the shared validator processes the supplied selector path.
            boolean valid = new SelectorPathValidator(context, family).validate(method, new String[] {path});

            // Then: invalid rows must return false as well as emit the compiler diagnostic.
            if (valid) {
                processingEnv
                        .getMessager()
                        .printMessage(Diagnostic.Kind.ERROR, "probe unexpectedly accepted an invalid selector", method);
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
