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
 * T003 TP-003 — verifies every shared proxyability violation through a real annotation-processing
 * compilation, including abstract methods and per-class diagnostic memoization.
 */
class ProxyabilityValidatorTest {

    private static final List<String> FAMILIES = List.of("cacheable", "rate-limited");

    @ParameterizedTest(name = "{0}")
    @MethodSource("proxyabilityViolations")
    @DisplayName("reports every proxyability violation with the family prefix")
    void reportsEveryProxyabilityViolationWithTheFamilyPrefix(
            String rowName, String fixture, String expectedSuffix, boolean memoized) {
        String firstDiagnostic = null;

        for (String family : FAMILIES) {
            // Given: one invalid proxyability shape and one family prefix.
            CompilationResult result = compile(fixture, new ProxyabilityProbeProcessor(family));
            String expected = family + " " + expectedSuffix;

            // When: the probe processor validates every fixture method.
            // Then: the exact family-prefixed violation is reported.
            assertFalse(result.success(), rowName + " must reject the invalid proxyability shape");
            long matchingDiagnostics = result.errorMessages().stream()
                    .filter(message -> message.equals(expected))
                    .count();
            assertEquals(
                    1,
                    matchingDiagnostics,
                    rowName
                            + (memoized
                                    ? " must report the co-located class violation only once"
                                    : " must report the expected violation count"));

            String actual = result.errorMessages().stream()
                    .filter(message -> message.equals(expected))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Expected diagnostic '" + expected + "' but got " + result.errorMessages()));
            if (firstDiagnostic == null) {
                firstDiagnostic = actual.substring("cacheable ".length());
            } else {
                assertEquals(
                        firstDiagnostic,
                        actual.substring("rate-limited ".length()),
                        rowName + " must differ only by its family prefix");
            }
        }
    }

    static Stream<Arguments> proxyabilityViolations() {
        return Stream.of(
                Arguments.of(
                        "enclosingClassIsNotPublic",
                        """
                        package com.example;

                        class ProbeBean {
                            @jakarta.inject.Inject ProbeBean() {}
                            public void probe() {}
                        }
                        """,
                        "methods must be declared on a public Dagger-managed class",
                        false),
                Arguments.of("enclosingClassIsFinal", """
                        package com.example;

                        public final class ProbeBean {
                            @jakarta.inject.Inject public ProbeBean() {}
                            public void probe() {}
                        }
                        """, "methods cannot be declared on a final class", false),
                Arguments.of(
                        "classHasNoInjectConstructor", """
                        package com.example;

                        public class ProbeBean {
                            public ProbeBean() {}
                            public void probe() {}
                        }
                        """, "methods require exactly one @Inject constructor", false),
                Arguments.of(
                        "classHasMultipleInjectConstructors",
                        """
                        package com.example;

                        public class ProbeBean {
                            @jakarta.inject.Inject public ProbeBean() {}
                            @jakarta.inject.Inject public ProbeBean(String value) {}
                            public void probe() {}
                        }
                        """,
                        "methods require exactly one @Inject constructor",
                        false),
                Arguments.of("methodIsFinal", """
                        package com.example;

                        public class ProbeBean {
                            @jakarta.inject.Inject public ProbeBean() {}
                            public final void probe() {}
                        }
                        """, "methods must be instance methods that can be overridden", false),
                Arguments.of("methodIsPrivate", """
                        package com.example;

                        public class ProbeBean {
                            @jakarta.inject.Inject public ProbeBean() {}
                            private void probe() {}
                        }
                        """, "methods must be instance methods that can be overridden", false),
                Arguments.of("methodIsStatic", """
                        package com.example;

                        public class ProbeBean {
                            @jakarta.inject.Inject public ProbeBean() {}
                            public static void probe() {}
                        }
                        """, "methods must be instance methods that can be overridden", false),
                Arguments.of("methodIsAbstract", """
                        package com.example;

                        public abstract class ProbeBean {
                            @jakarta.inject.Inject public ProbeBean() {}
                            public abstract void probe();
                        }
                        """, "methods must be instance methods that can be overridden", false),
                Arguments.of(
                        "invalidClassIsMemoizedAcrossCoLocatedMethods",
                        """
                        package com.example;

                        class ProbeBean {
                            @jakarta.inject.Inject ProbeBean() {}
                            public void first() {}
                            public void second() {}
                        }
                        """,
                        "methods must be declared on a public Dagger-managed class",
                        true));
    }

    private static CompilationResult compile(String fixture, ProxyabilityProbeProcessor processor) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, null)) {
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    List.of("--release", "21", "-proc:only"),
                    null,
                    List.of(
                            new InMemorySource("com.example.ProbeBean", fixture),
                            new InMemorySource(
                                    "jakarta.inject.Inject", "package jakarta.inject; public @interface Inject {}")));
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
    private static final class ProxyabilityProbeProcessor extends AbstractProcessor {

        private final String family;

        private ProxyabilityProbeProcessor(String family) {
            this.family = family;
        }

        @Override
        public boolean process(java.util.Set<? extends TypeElement> annotations, RoundEnvironment roundEnvironment) {
            if (roundEnvironment.processingOver()) {
                return false;
            }
            TypeElement bean = processingEnv.getElementUtils().getTypeElement("com.example.ProbeBean");
            CodegenContext context = new CodegenContext(processingEnv);
            ProxyabilityValidator validator = new ProxyabilityValidator(context, family);

            // Given: all methods on the compiled fixture's enclosing class.
            for (ExecutableElement method : ElementFilter.methodsIn(bean.getEnclosedElements())) {
                // When: the shared validator validates each co-located method.
                boolean valid = validator.validate(method);

                // Then: an invalid fixture must not be accepted silently.
                if (valid) {
                    processingEnv
                            .getMessager()
                            .printMessage(
                                    Diagnostic.Kind.ERROR, "probe unexpectedly accepted an invalid shape", method);
                }
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
